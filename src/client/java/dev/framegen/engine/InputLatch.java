package dev.framegen.engine;

import dev.framegen.FrameGenConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.Mth;
import org.jspecify.annotations.Nullable;
import org.lwjgl.sdl.SDLEvents;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_Event;
import org.lwjgl.sdl.SDL_MouseMotionEvent;

/**
 * "Responsive camera": the newest mouse movement, for the presenter to turn the camera with.
 *
 * <p>Minecraft reads input once per real frame (at the start of the frame) and turns the player with it. Mouse motion
 * that arrives while the frame renders waits in SDL's queue until the next frame. A few times per frame, the render
 * thread (SDL's main thread) pumps the queue and <em>peeks</em> at the waiting motion events without removing them: the
 * game still receives every event exactly as before. Applying that pending motion to the frame's camera rotation with
 * the game's own sensitivity formula gives the rotation the next real frame will have, available now. The presenter
 * shows generated frames with this rotation, while the world itself is still interpolated as usual: turning reacts
 * sooner, and since it is real input (not a prediction), it never overshoots.
 */
public final class InputLatch {
	/** The camera orientation including all mouse motion received up to {@code nanos}. */
	public record Sample(float yaw, float pitch, long nanos) {
	}

	private static final int MAX_EVENTS = 512;

	private volatile @Nullable Sample latest;
	private SDL_Event.@Nullable Buffer events;
	private boolean eligible;
	private float baseYaw;
	private float basePitch;
	private double factorX;
	private double factorY;
	private int windowId;

	/**
	 * Start of the level render: the camera of this frame already contains all input up to this frame's event poll.
	 * Decides whether latching applies (first person, playing, plain mouse look) and stores the base rotation.
	 */
	void beginFrame(CameraRenderState camera) {
		Minecraft mc = Minecraft.getInstance();
		FrameGenConfig config = FrameGenConfig.get();
		var player = mc.player;
		this.eligible = config.enabled && config.responsiveCamera && player != null && mc.getCameraEntity() == player
			&& camera.isFirstPerson && !camera.isPanoramicMode && mc.mouseHandler.isMouseGrabbed() && mc.gui.screen() == null && mc.isWindowActive()
			&& !mc.options.smoothCamera && !player.isSleeping() && !player.isDeadOrDying() && player.getVehicle() == null;
		if (!this.eligible) {
			this.latest = null;
			return;
		}
		this.baseYaw = camera.yRot;
		this.basePitch = camera.xRot;
		// Same formula as MouseHandler.turnPlayer + Entity.turn.
		double ss = mc.options.sensitivity().get() * 0.6 + 0.2;
		double sens = ss * ss * ss * (player.isScoping() ? 1.0 : 8.0);
		this.factorX = 0.15 * sens * (mc.options.invertMouseX().get() ? -1.0 : 1.0);
		this.factorY = 0.15 * sens * (mc.options.invertMouseY().get() ? -1.0 : 1.0);
		this.windowId = SDLVideo.SDL_GetWindowID(mc.getWindow().handle());
		this.sample();
	}

	/** Render thread: pump SDL and publish the rotation including the motion waiting in the queue. */
	void sample() {
		if (!this.eligible) return;
		if (this.events == null) {
			this.events = SDL_Event.malloc(MAX_EVENTS);
		}
		SDLEvents.SDL_PumpEvents();
		SDL_Event.Buffer buffer = this.events;
		buffer.clear();
		int n = SDLEvents.SDL_PeepEvents(buffer, SDLEvents.SDL_PEEKEVENT, SDLEvents.SDL_EVENT_MOUSE_MOTION, SDLEvents.SDL_EVENT_MOUSE_MOTION);
		double dx = 0.0;
		double dy = 0.0;
		for (int i = 0; i < n; i++) {
			SDL_MouseMotionEvent motion = buffer.get(i).motion();
			if (motion.windowID() != this.windowId) continue;
			dx += motion.xrel();
			dy += motion.yrel();
		}
		float yaw = (float) (this.baseYaw + dx * this.factorX);
		float pitch = Mth.clamp((float) (this.basePitch + dy * this.factorY), -90.0f, 90.0f);
		this.latest = new Sample(yaw, pitch, System.nanoTime());
	}

	/** Render thread: latching doesn't apply to this frame (menu open, no level, frame generation off...). */
	void invalidate() {
		this.eligible = false;
		this.latest = null;
	}

	/** Presenter thread: the newest sample, or null if latching doesn't apply right now. */
	@Nullable Sample latest() {
		return this.latest;
	}

	void close() {
		this.latest = null;
		if (this.events != null) {
			this.events.free();
			this.events = null;
		}
	}
}
