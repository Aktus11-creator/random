package dev.framegen.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL32C;

/**
 * Hand-off point between the render thread (producer) and the presenter thread (consumer).
 *
 * <p>Rules that keep the two GL contexts from stepping on each other:
 * <ul>
 * <li>Only the render thread creates, resizes or destroys slot textures, and only while a slot is not in use.</li>
 * <li>A published record belongs to the exchange until the presenter {@link #take takes} it; after that it belongs to
 * the presenter until it {@link #release releases} the record's slots with a fence.</li>
 * <li>The render thread may drop records the presenter has not taken yet (it is producing faster than they can be
 * shown); it then deletes their ready fence itself since nobody else has seen it.</li>
 * </ul>
 */
public final class FrameExchange {
	static final int MAX_WORLD_SLOTS = 6;
	static final int MAX_OVERLAY_SLOTS = 5;

	private final ReentrantLock lock = new ReentrantLock();
	private final Condition changed = this.lock.newCondition();
	private final List<GpuSlot> worldSlots = new ArrayList<>();
	private final List<GpuSlot> overlaySlots = new ArrayList<>();
	private final ArrayDeque<FrameRecord> pending = new ArrayDeque<>();
	private long nextId = 1;
	private long lastDisplayedId;
	private boolean closed;

	public FrameRecord newRecord() {
		this.lock.lock();
		try {
			return new FrameRecord(this.nextId++);
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Render thread: reserve a free slot of the given kind and make sure its textures have the right size.
	 * Returns null if the presenter holds every slot for longer than {@code timeoutMs}.
	 */
	public @Nullable GpuSlot acquire(GpuSlot.Kind kind, int width, int height, long timeoutMs) {
		List<GpuSlot> slots = kind == GpuSlot.Kind.WORLD ? this.worldSlots : this.overlaySlots;
		int max = kind == GpuSlot.Kind.WORLD ? MAX_WORLD_SLOTS : MAX_OVERLAY_SLOTS;
		GpuSlot slot = null;
		long fence = 0L;
		this.lock.lock();
		try {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
			while (slot == null) {
				// Prefer a free slot that already has the right size, then any free slot.
				for (GpuSlot candidate : slots) {
					if (!candidate.inUse && candidate.width == width && candidate.height == height) {
						slot = candidate;
						break;
					}
				}
				if (slot == null) {
					for (GpuSlot candidate : slots) {
						if (!candidate.inUse) {
							slot = candidate;
							break;
						}
					}
				}
				if (slot == null && slots.size() < max) {
					slot = new GpuSlot(kind, slots.size());
					slots.add(slot);
				}
				if (slot == null) {
					long remaining = deadline - System.nanoTime();
					if (remaining <= 0L || this.closed) {
						return null;
					}
					try {
						this.changed.awaitNanos(remaining);
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
						return null;
					}
				}
			}
			slot.inUse = true;
			fence = slot.releaseFence;
			slot.releaseFence = 0L;
		} finally {
			this.lock.unlock();
		}

		boolean resize = slot.width != width || slot.height != height || slot.color == null;
		if (resize && fence != 0L) {
			// The textures are about to be deleted: make sure the presenter's last reads have actually finished.
			GL32C.glClientWaitSync(fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, TimeUnit.MILLISECONDS.toNanos(100));
			GL32C.glDeleteSync(fence);
			fence = 0L;
		}
		slot.awaitRelease(fence);
		slot.ensure(width, height);
		return slot;
	}

	/** Render thread: give back a slot that was acquired but never published. */
	public void abandon(@Nullable GpuSlot slot) {
		if (slot == null) return;
		this.lock.lock();
		try {
			slot.inUse = false;
			this.changed.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	/** Render thread: hand a fully recorded frame to the presenter. Its GPU work must already be fenced. */
	public void publish(FrameRecord record, long readyFence) {
		record.readyFence = readyFence;
		this.lock.lock();
		try {
			// Keep at most one frame waiting: if the presenter hasn't picked up the previous one yet, it never will.
			while (!this.pending.isEmpty()) {
				FrameRecord dropped = this.pending.pollFirst();
				if (dropped.readyFence != 0L) {
					GL32C.glDeleteSync(dropped.readyFence);
					dropped.readyFence = 0L;
				}
				this.freeLocked(dropped.world, 0L);
				this.freeLocked(dropped.overlay, 0L);
			}
			this.pending.addLast(record);
			this.changed.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	/** Presenter thread: move every pending record into {@code out}. */
	void take(List<FrameRecord> out) {
		this.lock.lock();
		try {
			while (!this.pending.isEmpty()) {
				out.add(this.pending.pollFirst());
			}
		} finally {
			this.lock.unlock();
		}
	}

	/** Presenter thread: block until something is published, the timeout passes, or the exchange is closed. */
	void awaitPublish(long timeoutNanos) {
		this.lock.lock();
		try {
			if (this.pending.isEmpty() && !this.closed) {
				this.changed.awaitNanos(timeoutNanos);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Presenter thread: return a slot. {@code fence} must be signalled once the presenter's GPU reads of the slot are
	 * done; the render thread waits for it before writing again. Ownership of the fence moves to the exchange.
	 */
	void release(@Nullable GpuSlot slot, long fence) {
		this.lock.lock();
		try {
			this.freeLocked(slot, fence);
		} finally {
			this.lock.unlock();
		}
	}

	private void freeLocked(@Nullable GpuSlot slot, long fence) {
		if (slot == null) {
			if (fence != 0L) GL32C.glDeleteSync(fence);
			return;
		}
		if (slot.releaseFence != 0L) {
			GL32C.glDeleteSync(slot.releaseFence);
		}
		slot.releaseFence = fence;
		slot.inUse = false;
		this.changed.signalAll();
	}

	/** Presenter thread: the frame with this id (or a newer one) is now on screen. */
	void markDisplayed(long id) {
		this.lock.lock();
		try {
			if (id > this.lastDisplayedId) {
				this.lastDisplayedId = id;
				this.changed.signalAll();
			}
		} finally {
			this.lock.unlock();
		}
	}

	/**
	 * Render thread: emulate V-Sync back-pressure when frames are passed straight through. Blocks until the frame
	 * {@code id - queueDepth} has been displayed (or the timeout passes).
	 */
	public void awaitDisplayed(long id, int queueDepth, long timeoutMs) {
		long target = id - queueDepth;
		this.lock.lock();
		try {
			long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
			while (this.lastDisplayedId < target && !this.closed) {
				long remaining = deadline - System.nanoTime();
				if (remaining <= 0L) break;
				this.changed.awaitNanos(remaining);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		} finally {
			this.lock.unlock();
		}
	}

	/** Wake every waiter and refuse further waiting. */
	void close() {
		this.lock.lock();
		try {
			this.closed = true;
			this.changed.signalAll();
		} finally {
			this.lock.unlock();
		}
	}

	/** Render thread, after the presenter thread has exited: delete every texture and fence. */
	void destroy() {
		this.lock.lock();
		try {
			for (FrameRecord record : this.pending) {
				if (record.readyFence != 0L) GL32C.glDeleteSync(record.readyFence);
			}
			this.pending.clear();
			for (List<GpuSlot> list : List.of(this.worldSlots, this.overlaySlots)) {
				for (GpuSlot slot : list) {
					if (slot.releaseFence != 0L) GL32C.glDeleteSync(slot.releaseFence);
					slot.releaseFence = 0L;
					slot.destroyTextures();
				}
				list.clear();
			}
		} finally {
			this.lock.unlock();
		}
	}
}
