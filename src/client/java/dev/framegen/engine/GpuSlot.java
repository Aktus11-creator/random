package dev.framegen.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.blaze3d.systems.RenderSystem;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL32C;

/**
 * A set of GPU textures that one captured frame is written into. Owned by the render thread (it creates,
 * resizes and destroys the textures); the presenter thread only ever reads the GL names while it holds the slot.
 *
 * <p>Ownership is tracked by {@link FrameExchange}: a slot is either free, being written by the render thread, or
 * referenced by a published {@link FrameRecord}. When the presenter gives a slot back it attaches a fence
 * ({@link #releaseFence}) that the render thread waits on (GPU side) before overwriting the textures.
 */
public final class GpuSlot {
	public enum Kind {
		/** World color + depth. */
		WORLD,
		/** Premultiplied overlay (HUD + hand), or the complete frame for opaque records. */
		OVERLAY
	}

	final Kind kind;
	final int index;
	@Nullable GpuTexture color;
	@Nullable GpuTexture depth;
	@Nullable GpuTextureView colorView;
	@Nullable GpuTextureView depthView;
	int glColor;
	int glDepth;
	int width;
	int height;
	/** Guarded by the exchange lock. */
	boolean inUse;
	/** GLsync written by the presenter when it releases the slot, consumed (waited + deleted) by the render thread. Guarded by the exchange lock. */
	long releaseFence;

	GpuSlot(Kind kind, int index) {
		this.kind = kind;
		this.index = index;
	}

	/** Render thread: make sure the textures exist with the given size. */
	void ensure(int width, int height) {
		if (this.color != null && this.width == width && this.height == height) {
			return;
		}
		this.destroyTextures();
		var device = RenderSystem.getDevice();
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
		String base = "FrameGen " + this.kind.name().toLowerCase() + " #" + this.index;
		this.color = device.createTexture(base + " / color", usage, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		this.colorView = device.createTextureView(this.color);
		this.glColor = ((GlTexture) this.color).glId();
		if (this.kind == Kind.WORLD) {
			this.depth = device.createTexture(base + " / depth", usage, GpuFormat.D32_FLOAT, width, height, 1, 1);
			this.depthView = device.createTextureView(this.depth);
			this.glDepth = ((GlTexture) this.depth).glId();
		}
		this.width = width;
		this.height = height;
	}

	/** Render thread: wait (on the GPU) for the presenter to finish reading this slot. Call before writing to it. */
	void awaitRelease(long fence) {
		if (fence != 0L) {
			GL32C.glWaitSync(fence, 0, GL32C.GL_TIMEOUT_IGNORED);
			GL32C.glDeleteSync(fence);
		}
	}

	void destroyTextures() {
		if (this.colorView != null) this.colorView.close();
		if (this.depthView != null) this.depthView.close();
		this.depthView = null;
		if (this.color != null) this.color.close();
		if (this.depth != null) this.depth.close();
		this.colorView = null;
		this.color = null;
		this.depth = null;
		this.glColor = 0;
		this.glDepth = 0;
		this.width = 0;
		this.height = 0;
	}

	public @Nullable GpuTexture color() {
		return this.color;
	}

	public @Nullable GpuTexture depth() {
		return this.depth;
	}

	public @Nullable GpuTextureView colorView() {
		return this.colorView;
	}
}
