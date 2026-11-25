package dev.framegen.engine;

import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.sdl.SDLVideo;

/**
 * The presentation thread. Owns a second OpenGL context (sharing textures with Minecraft's) that is current on the
 * game window, and is the only thing that ever swaps the window's buffers.
 *
 * <p>Minecraft renders into offscreen textures at whatever rate it can manage and publishes each finished frame
 * (color, depth, HUD overlay, camera matrices) through the {@link FrameExchange}. This thread runs at the display's
 * refresh rate: every refresh it picks the real frames surrounding the moment it wants to show, reprojects them into
 * the camera pose for that moment and composites the HUD on top. Because it is a separate thread with a separate GL
 * context, the driver can schedule these small passes between (or alongside) the game's own GPU work: that is what
 * makes the generated frames arrive evenly spaced instead of bunched up next to the real ones.
 */
public final class Presenter implements Runnable {
	private static final long NANOS_PER_SECOND = 1_000_000_000L;
	/** Real frames further apart than this are not interpolated between (a menu was open, or the game froze). */
	static final long STALE_FRAME_NANOS = 1_000_000_000L;
	static final boolean DEBUG_LOG = Boolean.getBoolean("framegen.debug");
	private long lastDebugLog;

	private final FrameExchange exchange;
	private final long window;
	private final long context;
	private final BooleanSupplier iconified;
	private final CountDownLatch started = new CountDownLatch(1);
	private volatile boolean running = true;
	private volatile @Nullable Throwable initFailure;
	private @Nullable Thread thread;

	// ---- Written by the render thread, read here -------------------------------------------------------------
	private volatile int surfaceWidth = 1;
	private volatile int surfaceHeight = 1;
	private volatile boolean vsync = true;
	private volatile double displayHz = 60.0;

	// ---- Statistics, written here, read by the HUD -----------------------------------------------------------
	private volatile float statOutputFps;
	private volatile float statRealFps;
	private volatile float statLatencyMs;
	private volatile float statPresentCostMs;
	private volatile boolean statGenerating;

	// ---- Presenter-thread state ------------------------------------------------------------------------------
	private GlProgram warp;
	private GlProgram passthrough;
	private GlProgram compose;
	private GlProgram invertQuad;
	private int vao;
	private int linearSampler;
	private int nearestSampler;
	private int appliedSwapInterval = Integer.MIN_VALUE;
	private final ArrayDeque<FrameRecord> inflight = new ArrayDeque<>();
	private final List<FrameRecord> history = new ArrayList<>();
	private final List<FrameRecord> taken = new ArrayList<>();
	private long lastShownRecordId;
	private long lastPresentNanos;
	/** Presentation delay of the playback clock (see {@link #chooseDisplayTime}). */
	private double delayNanos = -1.0;
	private boolean wasGenerating;
	private long lastClockNanos;
	private FrameGenConfig.@Nullable Mode clockMode;
	private double lastDisplaySim = Double.NEGATIVE_INFINITY;
	private double latencyEma = -1.0;
	private double intervalEma = -1.0;
	private double latencyHigh = -1.0;
	private double intervalHigh = -1.0;
	private int vsyncIneffectiveStreak;
	private boolean vsyncHonored = true;
	private long statWindowStart;
	private int statOutputFrames;
	private int statRealFrames;
	private double statCostAccum;
	private boolean glErrorLogged;

	private final Pose poseA = new Pose();
	private final Pose poseB = new Pose();
	private final Pose target = new Pose();
	private final Matrix4f fwd = new Matrix4f();
	private final Matrix4f fwdRot = new Matrix4f();
	private final Matrix4f inv = new Matrix4f();
	private final Matrix4f invRot = new Matrix4f();
	private final Matrix4f srcInv = new Matrix4f();
	private final Matrix4f tgt = new Matrix4f();
	/** Moving entities of the two source frames: a uniform buffer, {@link #ENTITY_VEC4S} vec4 per entity (see warp.fsh). */
	private static final int ENTITY_VEC4S = 6;
	private static final int MAX_MOVING = 32;
	private final org.joml.Vector4f tmpV = new org.joml.Vector4f();
	private final Matrix4f srcVp = new Matrix4f();
	/** Union rectangles of the last {@link #uploadMotion}: x0, y0, x1, y1 in source NDC and in target NDC. */
	private final float[] motionSrcRect = new float[4];
	private final float[] motionTgtRect = new float[4];
	/** A small ring, so the buffer being written was last read several frames ago (no waiting on the GPU). */
	private final int[] entUbos = new int[4];
	private int entUboIndex;
	private java.nio.@Nullable FloatBuffer entBuf;
	private volatile int statMovingEntities;

	private final InputLatch inputLatch;
	private volatile float statCameraDelayMs = Float.NaN;
	private double cameraDelayAccum;
	private int cameraDelaySamples;

	Presenter(FrameExchange exchange, long window, long context, BooleanSupplier iconified, InputLatch inputLatch) {
		this.inputLatch = inputLatch;
		this.exchange = exchange;
		this.window = window;
		this.context = context;
		this.iconified = iconified;
	}

	/** Starts the thread and waits until it has initialized its GL state. Returns false (thread gone) on failure. */
	boolean start() {
		Thread t = new Thread(this, "FrameGen Presenter");
		t.setDaemon(true);
		t.setPriority(Thread.MAX_PRIORITY);
		this.thread = t;
		t.start();
		try {
			if (!this.started.await(10, TimeUnit.SECONDS)) {
				FrameGenMod.LOGGER.error("FrameGen presenter thread did not start in time");
				this.stop();
				return false;
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
		if (this.initFailure != null) {
			FrameGenMod.LOGGER.error("FrameGen presenter failed to initialize", this.initFailure);
			return false;
		}
		return true;
	}

	void stop() {
		this.running = false;
		this.exchange.close();
		Thread t = this.thread;
		if (t != null) {
			try {
				t.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			if (t.isAlive()) {
				FrameGenMod.LOGGER.warn("FrameGen presenter thread did not stop in time");
			}
		}
	}

	boolean isAlive() {
		Thread t = this.thread;
		return t != null && t.isAlive();
	}

	void configure(int width, int height, boolean vsync) {
		this.surfaceWidth = Math.max(1, width);
		this.surfaceHeight = Math.max(1, height);
		this.vsync = vsync;
	}

	void setDisplayHz(double hz) {
		if (hz >= 20.0 && hz <= 1000.0) {
			this.displayHz = hz;
		}
	}

	double displayHz() {
		return this.displayHz;
	}

	public float outputFps() {
		return this.statOutputFps;
	}

	public float realFps() {
		return this.statRealFps;
	}

	public float latencyMs() {
		return this.statLatencyMs;
	}

	/** Age of the mouse input the camera was last turned with (responsive camera), NaN if not in use. */
	public float cameraDelayMs() {
		return this.statCameraDelayMs;
	}

	public float presentCostMs() {
		return this.statPresentCostMs;
	}

	public boolean isGenerating() {
		return this.statGenerating;
	}

	// ==========================================================================================================

	@Override
	public void run() {
		try {
			if (!SDLVideo.SDL_GL_MakeCurrent(this.window, this.context)) {
				throw new IllegalStateException("SDL_GL_MakeCurrent failed: " + SDLError.SDL_GetError());
			}
			GL.createCapabilities();
			this.initGl();
		} catch (Throwable t) {
			this.initFailure = t;
			this.running = false;
			try {
				SDLVideo.SDL_GL_MakeCurrent(this.window, 0L);
			} catch (Throwable ignored) {
			}
			this.started.countDown();
			return;
		}
		this.started.countDown();

		try {
			this.statWindowStart = System.nanoTime();
			while (this.running) {
				this.frame();
			}
		} catch (Throwable t) {
			FrameGenMod.LOGGER.error("FrameGen presenter crashed; the game will keep running without it", t);
			FrameGenEngine.onPresenterCrashed(t);
		} finally {
			this.cleanupGl();
			SDLVideo.SDL_GL_MakeCurrent(this.window, 0L);
			GL.setCapabilities(null);
		}
	}

	private void initGl() {
		// textureGather fetches the 2x2 depth footprint in one go (core in GL 4.0, or ARB_texture_gather).
		var caps = GL.getCapabilities();
		String version = null;
		String defines = "";
		if (caps.OpenGL40) {
			version = "#version 400 core";
			defines = "#define HAS_GATHER\n";
		} else if (caps.GL_ARB_texture_gather) {
			defines = "#extension GL_ARB_texture_gather : require\n#define HAS_GATHER\n";
		}
		this.warp = new GlProgram("warp", "fullscreen.vsh", "warp.fsh", version, defines);
		this.compose = new GlProgram("compose", "fullscreen.vsh", "compose.fsh");
		FrameGenMod.LOGGER.info("FrameGen presenter ready ({}, texture gather: {})", GL33C.glGetString(GL33C.GL_VERSION), !defines.isEmpty());
		this.passthrough = new GlProgram("passthrough", "fullscreen.vsh", "passthrough.fsh");
		this.invertQuad = new GlProgram("invert_quad", "invert_quad.vsh", "invert_quad.fsh");

		this.warp.use();
		this.warp.setInt("uColorA", 0);
		this.warp.setInt("uDepthA", 1);
		this.warp.setInt("uColorB", 2);
		this.warp.setInt("uDepthB", 3);
		this.warp.setInt("uOverlay", 4);
		this.warp.setInt("uVignetteTex", 5);
		this.warp.setInt("uOpaqueDepthA", 10);
		this.warp.setInt("uOpaqueDepthB", 11);
		this.warp.bindUniformBlock("FrameGenEntities", 0);
		this.passthrough.use();
		this.passthrough.setInt("uOverlay", 4);
		this.compose.use();
		this.compose.setInt("uWorld", 2);
		this.compose.setInt("uOverlay", 4);
		this.compose.setInt("uVignetteTex", 5);
		this.invertQuad.use();
		this.invertQuad.setInt("uTexture", 6);
		GL33C.glUseProgram(0);

		GL33C.glGenBuffers(this.entUbos);
		for (int ubo : this.entUbos) {
			GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, ubo);
			GL33C.glBufferData(GL33C.GL_UNIFORM_BUFFER, 2L * MAX_MOVING * ENTITY_VEC4S * 16, GL33C.GL_DYNAMIC_DRAW);
		}
		GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, 0);
		this.entBuf = org.lwjgl.system.MemoryUtil.memAllocFloat(2 * 4 * ENTITY_VEC4S * MAX_MOVING);
		this.vao = GL33C.glGenVertexArrays();
		this.linearSampler = GL33C.glGenSamplers();
		configureSampler(this.linearSampler, GL33C.GL_LINEAR);
		this.nearestSampler = GL33C.glGenSamplers();
		configureSampler(this.nearestSampler, GL33C.GL_NEAREST);

		GL33C.glDisable(GL33C.GL_DEPTH_TEST);
		GL33C.glDisable(GL33C.GL_CULL_FACE);
		GL33C.glDisable(GL33C.GL_SCISSOR_TEST);
		GL33C.glDisable(GL33C.GL_BLEND);
		GL33C.glDisable(GL33C.GL_FRAMEBUFFER_SRGB);
		int error = GL33C.glGetError();
		if (error != 0) {
			throw new IllegalStateException("GL error during presenter init: " + error);
		}
	}

	private static void configureSampler(int sampler, int filter) {
		GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_MIN_FILTER, filter);
		GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_MAG_FILTER, filter);
		GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_WRAP_S, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_WRAP_T, GL33C.GL_CLAMP_TO_EDGE);
		GL33C.glSamplerParameteri(sampler, GL33C.GL_TEXTURE_COMPARE_MODE, GL33C.GL_NONE);
	}

	private void cleanupGl() {
		try {
			// Make sure nothing still reads the slots before handing them back without fences.
			GL33C.glFinish();
			for (FrameRecord r : this.history) {
				this.releaseRecord(r, false);
			}
			this.history.clear();
			for (FrameRecord r : this.inflight) {
				if (r.readyFence != 0L) {
					GL33C.glDeleteSync(r.readyFence);
					r.readyFence = 0L;
				}
				this.releaseRecord(r, false);
			}
			this.inflight.clear();
			if (this.warp != null) this.warp.close();
			if (this.passthrough != null) this.passthrough.close();
			if (this.compose != null) this.compose.close();
			if (this.invertQuad != null) this.invertQuad.close();
			if (this.vao != 0) GL33C.glDeleteVertexArrays(this.vao);
			if (this.entUbos[0] != 0) GL33C.glDeleteBuffers(this.entUbos);
			if (this.entBuf != null) org.lwjgl.system.MemoryUtil.memFree(this.entBuf);
			this.entBuf = null;
			if (this.testFbo != 0) GL33C.glDeleteFramebuffers(this.testFbo);
			if (this.testTexture != 0) GL33C.glDeleteTextures(this.testTexture);
			if (this.testBlank != 0) GL33C.glDeleteTextures(this.testBlank);
			if (this.timerQueries[0] != 0) GL33C.glDeleteQueries(this.timerQueries);
			if (this.linearSampler != 0) GL33C.glDeleteSamplers(this.linearSampler);
			if (this.nearestSampler != 0) GL33C.glDeleteSamplers(this.nearestSampler);
		} catch (Throwable t) {
			FrameGenMod.LOGGER.warn("FrameGen presenter cleanup failed", t);
		}
	}

	// ==========================================================================================================

	private void frame() {
		if (this.iconified.getAsBoolean()) {
			this.ingest();
			this.exchange.awaitPublish(TimeUnit.MILLISECONDS.toNanos(50));
			return;
		}

		FrameGenConfig config = FrameGenConfig.get();
		this.ingest();
		FrameRecord newest = this.history.isEmpty() ? null : this.history.getLast();
		if (newest == null) {
			this.exchange.awaitPublish(TimeUnit.MILLISECONDS.toNanos(2));
			return;
		}

		boolean generate = config.enabled && newest.hasWorld();
		// Native mode: the game already delivers (close to) one real frame per display refresh, so generated frames
		// would only add latency and GPU work. Show each real frame once, exactly like vanilla presentation.
		boolean nativeMode = generate && this.updateNativeMode(config);
		this.statNative = nativeMode;
		if (nativeMode) {
			generate = false;
		}
		if (!generate && newest.id == this.lastShownRecordId) {
			// Nothing new to show and nothing to generate: wait for the game instead of re-presenting the same image.
			this.statGenerating = false;
			this.exchange.awaitPublish(TimeUnit.MICROSECONDS.toNanos(500));
			this.updateStats(System.nanoTime());
			return;
		}

		int interval = this.vsync ? 1 : 0;
		if (interval != this.appliedSwapInterval) {
			if (!SDLVideo.SDL_GL_SetSwapInterval(interval) && interval != 0) {
				FrameGenMod.LOGGER.warn("Couldn't enable V-Sync on the presenter context: {}", SDLError.SDL_GetError());
			}
			this.appliedSwapInterval = interval;
		}

		long start = System.nanoTime();
		int sw = this.surfaceWidth;
		int sh = this.surfaceHeight;
		GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
		// Place the game's image exactly like vanilla's swapchain blit does: 1:1 pixels, aligned to the top-left,
		// never stretched. Normally the frame and the window are the same size; if they ever differ (fullscreen mode
		// switches, display scaling, the borderless-fullscreen 1px padding...) the result still matches vanilla
		// instead of being zoomed.
		int w = newest.width > 0 ? newest.width : sw;
		int h = newest.height > 0 ? newest.height : sh;
		if (w != sw || h != sh) {
			this.logSizeMismatch(w, h, sw, sh);
			GL33C.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
			GL33C.glClear(GL33C.GL_COLOR_BUFFER_BIT);
		}
		GL33C.glViewport(0, h <= sh ? sh - h : 0, w, h);
		GL33C.glBindVertexArray(this.vao);

		this.beginGpuTimer();
		FrameRecord overlaySource = newest;
		if (generate) {
			this.statGenerating = true;
			this.drawGenerated(config, start, w, h);
			this.wasGenerating = true;
		} else {
			this.statGenerating = false;
			this.wasGenerating = false;
			this.lastDisplaySim = Double.NEGATIVE_INFINITY;
			if (newest.opaque) {
				this.drawOpaque(newest);
			} else {
				this.drawCompose(newest);
			}
		}
		this.drawInvertQuads(overlaySource);
		GL33C.glBindVertexArray(0);
		this.endGpuTimer();

		if (!this.glErrorLogged) {
			int error = GL33C.glGetError();
			if (error != 0) {
				this.glErrorLogged = true;
				FrameGenMod.LOGGER.warn("FrameGen presenter GL error {} (logged once)", error);
			}
		}

		long beforeSwap = System.nanoTime();
		this.statCostAccum += (beforeSwap - start) / 1e6;
		SDLVideo.SDL_GL_SwapWindow(this.window);
		if (config.lowLatencyPresent) {
			GL33C.glFinish();
		}
		long now = System.nanoTime();
		boolean newReal = newest.id != this.lastShownRecordId;
		this.lastShownRecordId = newest.id;
		this.exchange.markDisplayed(newest.id);
		this.releaseUnneeded(newest);

		this.statOutputFrames++;
		if (newReal) this.statRealFrames++;
		this.pace(config, generate, now);
		this.lastPresentNanos = System.nanoTime();
		this.updateStats(this.lastPresentNanos);
	}

	/** Moves published records in, polls their fences and builds the history of ready frames. */
	private long inflightSince;
	private boolean inflightWarned;

	private void ingest() {
		this.taken.clear();
		this.exchange.take(this.taken);
		this.inflight.addAll(this.taken);
		long now = System.nanoTime();
		// Frames whose GPU fence never signals would leave the screen frozen. Warn once, with details, if that happens.
		if (this.inflight.size() >= 3) {
			if (this.inflightSince == 0L) this.inflightSince = now;
			if (!this.inflightWarned && now - this.inflightSince > 2_000_000_000L) {
				this.inflightWarned = true;
				FrameGenMod.LOGGER.warn("FrameGen: {} frames have been waiting on the GPU for over 2 s (driver: {} / {}). "
					+ "Please report this with your latest.log.", this.inflight.size(), GL33C.glGetString(GL33C.GL_RENDERER), GL33C.glGetString(GL33C.GL_VERSION));
			}
		} else {
			this.inflightSince = 0L;
		}
		while (!this.inflight.isEmpty()) {
			FrameRecord r = this.inflight.peekFirst();
			int status = GL33C.glClientWaitSync(r.readyFence, 0, 0L);
			if (status != GL33C.GL_ALREADY_SIGNALED && status != GL33C.GL_CONDITION_SATISFIED) {
				if (status == GL33C.GL_WAIT_FAILED) {
					throw new IllegalStateException("glClientWaitSync failed");
				}
				break;
			}
			this.inflight.pollFirst();
			GL33C.glDeleteSync(r.readyFence);
			r.readyFence = 0L;
			r.readyNanos = now;
			this.onReady(r);
		}
	}

	private void onReady(FrameRecord r) {
		double latency = r.readyNanos - r.simNanos;
		this.latencyEma = this.latencyEma < 0 ? latency : this.latencyEma + (latency - this.latencyEma) * 0.1;
		this.latencyHigh = this.latencyHigh < 0 ? latency : smoothHigh(this.latencyHigh, latency);
		FrameRecord prevWorld = this.newestWorld();
		if (prevWorld != null && r.hasWorld() && !r.cut) {
			double interval = r.simNanos - prevWorld.simNanos;
			if (interval > 0 && interval < NANOS_PER_SECOND) {
				this.intervalEma = this.intervalEma < 0 ? interval : this.intervalEma + (interval - this.intervalEma) * 0.1;
				this.intervalHigh = this.intervalHigh < 0 ? interval : smoothHigh(this.intervalHigh, interval);
			}
		}
		if (r.hasWorld() && prevWorld != null && r.simNanos - prevWorld.simNanos > STALE_FRAME_NANOS) {
			r.cut = true; // stale history (menu was open, game hitched): don't interpolate across the gap
		}
		if (DEBUG_LOG && prevWorld != null && r.hasWorld() && !r.cut && r.readyNanos - this.lastSelfTest > 3_000_000_000L
			&& prevWorld.world.width == r.world.width && prevWorld.world.height == r.world.height) {
			this.lastSelfTest = r.readyNanos;
			this.selfTest(prevWorld, r);
			FrameRecord older = this.olderWorld(prevWorld);
			if (older != null && !prevWorld.cut && older.world.width == r.world.width && older.world.height == r.world.height) {
				this.interpolationTest(older, prevWorld, r);
			}
		}
		this.history.add(r);
		// Keep the three newest world frames (interpolation needs to bracket the display time) plus the newest record.
		int worlds = 0;
		for (int i = this.history.size() - 1; i >= 0; i--) {
			FrameRecord h = this.history.get(i);
			boolean keep = i == this.history.size() - 1 || (h.hasWorld() && worlds < 3);
			if (h.hasWorld()) worlds++;
			if (!keep) {
				this.history.remove(i);
				this.releaseRecord(h, true);
			}
		}
	}

	/** Rises quickly, decays slowly: a robust "recent maximum" for jittery frame times. */
	private static double smoothHigh(double current, double sample) {
		return sample > current ? current + (sample - current) * 0.5 : current + (sample - current) * 0.03;
	}

	private @Nullable FrameRecord newestWorld() {
		for (int i = this.history.size() - 1; i >= 0; i--) {
			if (this.history.get(i).hasWorld()) return this.history.get(i);
		}
		return null;
	}

	// ---- Drawing ----------------------------------------------------------------------------------------------

	private int loggedMismatchW = -1;
	private int loggedMismatchH = -1;
	private int loggedSurfaceW = -1;
	private int loggedSurfaceH = -1;

	private void logSizeMismatch(int w, int h, int sw, int sh) {
		if (w == this.loggedMismatchW && h == this.loggedMismatchH && sw == this.loggedSurfaceW && sh == this.loggedSurfaceH) return;
		this.loggedMismatchW = w;
		this.loggedMismatchH = h;
		this.loggedSurfaceW = sw;
		this.loggedSurfaceH = sh;
		FrameGenMod.LOGGER.info("FrameGen: game frame is {}x{} but the window surface is {}x{}; presenting 1:1 like vanilla", w, h, sw, sh);
	}

	private void drawOpaque(FrameRecord r) {
		this.passthrough.use();
		bindTexture(4, r.overlay.glColor, this.linearSampler);
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
	}

	/** A real frame with a separate world layer, shown without reprojection. */
	private void drawCompose(FrameRecord r) {
		GlProgram p = this.compose;
		p.use();
		p.setVec4("uWorldRect", 0.5f - 0.5f * r.overscanX, 0.5f - 0.5f * r.overscanY, 0.5f + 0.5f * r.overscanX, 0.5f + 0.5f * r.overscanY);
		bindTexture(2, r.world.glColor, this.linearSampler);
		bindTexture(4, r.overlay.glColor, this.linearSampler);
		if (r.vignette && r.vignetteTexture != 0) {
			p.setInt("uVignette", 1);
			p.setVec3("uVignetteColor", r.vignetteR, r.vignetteG, r.vignetteB);
			bindTexture(5, r.vignetteTexture, this.linearSampler);
		} else {
			p.setInt("uVignette", 0);
			bindTexture(5, r.world.glColor, this.linearSampler);
		}
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
	}

	private boolean nativeMode;
	private volatile boolean statNative;

	/** Interval between presented frames we aim for, in nanoseconds. */
	private double outputIntervalNanos(FrameGenConfig config) {
		if (this.vsync && this.vsyncHonored) return 1e9 / this.displayHz;
		double fps = config.targetOutputFps > 0 ? config.targetOutputFps : this.displayHz;
		return 1e9 / fps;
	}

	/** Hysteresis between generating and native presentation, based on the real frame interval. */
	private boolean updateNativeMode(FrameGenConfig config) {
		if (this.intervalEma <= 0) return this.nativeMode;
		double ratio = this.intervalEma / this.outputIntervalNanos(config);
		if (this.nativeMode && ratio > 1.30) {
			this.nativeMode = false;
		} else if (!this.nativeMode && ratio < 1.12) {
			this.nativeMode = true;
		}
		return this.nativeMode;
	}

	/** True while frame generation is on but the game is fast enough that real frames are shown as is. */
	public boolean isNative() {
		return this.statNative;
	}

	private void drawGenerated(FrameGenConfig config, long now, int w, int h) {
		List<FrameRecord> worlds = new ArrayList<>(4);
		for (FrameRecord r : this.history) {
			if (r.hasWorld()) worlds.add(r);
		}
		FrameRecord newest = worlds.getLast();
		double displaySim = this.chooseDisplayTime(config, now, newest);

		// Find A <= displaySim < B.
		FrameRecord a = null;
		FrameRecord b = null;
		for (int i = worlds.size() - 1; i >= 0; i--) {
			if (worlds.get(i).simNanos <= displaySim) {
				a = worlds.get(i);
				b = i + 1 < worlds.size() ? worlds.get(i + 1) : null;
				break;
			}
		}
		if (a == null) {
			// Display time is older than everything we have: show the oldest frame as is.
			FrameRecord only = worlds.getFirst();
			this.target.set(only);
			this.applyResponsiveCamera(config, now);
			this.drawWarp(config, null, only, 0.0, w, h, null, Double.NaN, 0.0);
			return;
		}
		if (b == null) {
			// Past the newest frame: extrapolate from the last two (unless that crosses a cut).
			if (worlds.size() >= 2 && !newest.cut) {
				b = newest;
				a = worlds.get(worlds.size() - 2);
			} else {
				this.target.set(newest);
				this.applyResponsiveCamera(config, now);
				this.drawWarp(config, null, newest, 0.0, w, h, null, Double.NaN, 0.0);
				return;
			}
		}
		if (b.cut) {
			// Never blend across a teleport/respawn: hold A until B is due, then switch.
			FrameRecord only = displaySim < b.simNanos ? a : b;
			this.target.set(only);
			this.drawWarp(config, null, only, 0.0, w, h, null, Double.NaN, 0.0);
			return;
		}

		double span = Math.max(1.0, b.simNanos - a.simNanos);
		double t = (displaySim - a.simNanos) / span;
		double maxT = 1.0 + Math.max(0.0, config.maxExtrapolation) * Math.min(1.0, (this.intervalEma > 0 ? this.intervalEma : span) / span);
		t = Math.max(0.0, Math.min(maxT, t));

		this.poseA.set(a);
		this.poseB.set(b);
		this.target.blend(this.poseA, this.poseB, t);
		this.applyResponsiveCamera(config, now);
		boolean useA = t < 1.0 || config.quality.backgroundSearch;
		this.drawWarp(config, useA ? a : null, b, Math.max(0.0, 1.0 - t), w, h, a, t, displaySim);
	}

	/**
	 * Responsive camera: turn the generated frame with the newest mouse input (see {@link InputLatch}) instead of the
	 * interpolated rotation. Only the rotation changes; the world is still interpolated, so it stays smooth.
	 */
	private void applyResponsiveCamera(FrameGenConfig config, long now) {
		InputLatch.Sample sample = config.responsiveCamera ? this.inputLatch.latest() : null;
		if (sample == null || now - sample.nanos() > 150_000_000L) {
			this.inputCount = 0;
			return;
		}
		// Mouse samples arrive in bursts (a few per real frame). Play them back one typical gap late and interpolate
		// between them, so the camera turns continuously instead of in steps. Never ahead of real input.
		InputLatch.Sample last = this.inputCount > 0 ? this.inputSamples[(this.inputHead + this.inputCount - 1) % this.inputSamples.length] : null;
		if (last == null || sample.nanos() != last.nanos()) {
			if (last != null) {
				double gap = Math.min(50e6, Math.max(1e6, sample.nanos() - last.nanos()));
				this.inputGapEma = this.inputGapEma <= 0 ? gap : this.inputGapEma + (gap - this.inputGapEma) * 0.1;
			}
			if (this.inputCount == this.inputSamples.length) {
				this.inputHead = (this.inputHead + 1) % this.inputSamples.length;
				this.inputCount--;
			}
			this.inputSamples[(this.inputHead + this.inputCount) % this.inputSamples.length] = sample;
			this.inputCount++;
		}
		double when = now - Math.max(1e6, this.inputGapEma);
		InputLatch.Sample before = null;
		InputLatch.Sample after = null;
		for (int i = 0; i < this.inputCount; i++) {
			InputLatch.Sample s = this.inputSamples[(this.inputHead + i) % this.inputSamples.length];
			if (s.nanos() <= when) {
				before = s;
			} else {
				after = s;
				break;
			}
		}
		float yaw;
		float pitch;
		double shownNanos;
		if (before == null) {
			InputLatch.Sample first = this.inputSamples[this.inputHead];
			yaw = first.yaw();
			pitch = first.pitch();
			shownNanos = first.nanos();
		} else if (after == null) {
			yaw = before.yaw();
			pitch = before.pitch();
			shownNanos = before.nanos();
		} else {
			double f = (when - before.nanos()) / Math.max(1.0, after.nanos() - before.nanos());
			yaw = (float) (before.yaw() + (after.yaw() - before.yaw()) * f);
			pitch = (float) (before.pitch() + (after.pitch() - before.pitch()) * f);
			shownNanos = when;
		}
		this.target.setYawPitch(yaw, pitch);
		this.cameraDelayAccum += (now - shownNanos) / 1e6;
		this.cameraDelaySamples++;
	}

	private final InputLatch.Sample[] inputSamples = new InputLatch.Sample[16];
	private int inputHead;
	private int inputCount;
	private double inputGapEma = -1.0;

	/**
	 * The playback clock. Returns the simulation timestamp to show at {@code now}.
	 *
	 * <p>Interpolation shows the world {@code delay} nanoseconds in the past, where {@code delay} is just large enough
	 * that the next real frame is normally ready before we need it (render latency + one frame interval). The delay is
	 * steered slowly so the motion speed never visibly changes; if a frame is late we hold rather than jump.
	 * Extrapolation uses only the render latency, so generated frames continue past the newest real frame.
	 */
	private double chooseDisplayTime(FrameGenConfig config, long now, FrameRecord newest) {
		double latency = Math.max(0.0, this.latencyHigh);
		double interval = this.intervalHigh > 0 ? this.intervalHigh : (this.intervalEma > 0 ? this.intervalEma : 1e9 / 60.0);
		double targetDelay = config.mode == FrameGenConfig.Mode.INTERPOLATE ? latency + interval * 1.05 + 1.0e6 : latency;
		long previousCall = this.lastClockNanos;
		this.lastClockNanos = now;
		boolean modeChanged = config.mode != this.clockMode;
		this.clockMode = config.mode;
		if (this.delayNanos < 0 || !this.wasGenerating || previousCall == 0L || modeChanged) {
			this.delayNanos = targetDelay;
			this.lastDisplaySim = Double.NEGATIVE_INFINITY;
		} else {
			// Glide towards the target so motion speed never visibly jumps: normally at most a 4% playback speed
			// change, but catch up faster when far off (after a hitch the delay must not stay high for long).
			double frameTime = previousCall > 0 ? Math.min(50e6, now - previousCall) : 0.0;
			double error = targetDelay - this.delayNanos;
			double rate = Math.abs(error) > 2.0 * interval ? 0.25 : 0.04;
			double maxStep = frameTime * rate;
			this.delayNanos += Math.max(-maxStep, Math.min(maxStep, error));
		}
		double sim = now - this.delayNanos;
		if (config.mode == FrameGenConfig.Mode.INTERPOLATE && sim > newest.simNanos) {
			// Starving: the next frame is late. Hold at the newest frame and lengthen the delay so it doesn't recur.
			this.delayNanos = now - newest.simNanos;
			sim = newest.simNanos;
		}
		if (sim < this.lastDisplaySim) {
			sim = this.lastDisplaySim; // never run backwards
		}
		this.lastDisplaySim = sim;
		return sim;
	}

	/**
	 * Draws the world reprojected into {@link #target} from frame {@code b} (and {@code a} if given, with temporal
	 * weight {@code weightA}), the vignette and the overlay of the newest frame. Moving entities are placed where they
	 * are at blend factor {@code u} between {@code pairA} and {@code b} (NaN: no entity motion), or, for entities only
	 * one frame has, where their velocity takes them at {@code targetSim}.
	 */
	private void drawWarp(FrameGenConfig config, @Nullable FrameRecord a, FrameRecord b, double weightA, int w, int h,
		@Nullable FrameRecord pairA, double u, double targetSim) {
		FrameRecord overlay = this.history.getLast();
		GlProgram p = this.warp;
		p.use();

		this.poseB.set(b);
		this.target.reprojection(this.poseB, this.fwd, this.fwdRot, this.inv, this.invRot);
		p.setMat4("uFwdB", this.fwd);
		p.setMat4("uFwdRotB", this.fwdRot);
		p.setMat4("uInvB", this.inv);
		p.setMat4("uInvRotB", this.invRot);
		p.setVec2("uSizeB", b.world.width, b.world.height);
		bindTexture(2, b.world.glColor, this.linearSampler);
		bindTexture(3, b.world.glDepth, this.nearestSampler);
		bindTexture(11, b.world.glOpaqueDepth, this.nearestSampler);
		boolean seeThrough = config.seeThroughFix && !this.testNoSeeThrough && a != null && a.hasOpaqueDepth && b.hasOpaqueDepth;
		p.setInt("uOpaqueA", seeThrough ? 1 : 0);
		p.setInt("uOpaqueB", seeThrough ? 1 : 0);
		boolean motion = config.movingObjects && !Double.isNaN(u);
		this.target.motionMatrices(this.poseB, this.srcInv, this.tgt);
		p.setMat4("uSrcInvB", this.srcInv);
		p.setMat4("uTgtB", this.tgt);
		int movingB = motion ? this.uploadMotion(1, b, pairA != b ? pairA : null, false, u, targetSim) : 0;
		p.setInt("uEntCountB", movingB);
		this.setMotionRects(p, "B", movingB);
		int movingA = 0;

		if (a != null) {
			this.poseA.set(a);
			this.target.reprojection(this.poseA, this.fwd, this.fwdRot, this.inv, this.invRot);
			p.setMat4("uFwdA", this.fwd);
			p.setMat4("uFwdRotA", this.fwdRot);
			p.setMat4("uInvA", this.inv);
			p.setMat4("uInvRotA", this.invRot);
			this.target.motionMatrices(this.poseA, this.srcInv, this.tgt);
			p.setMat4("uSrcInvA", this.srcInv);
			p.setMat4("uTgtA", this.tgt);
			movingA = motion ? this.uploadMotion(0, a, b, true, u, targetSim) : 0;
			this.setMotionRects(p, "A", movingA);
			p.setVec2("uSizeA", a.world.width, a.world.height);
			bindTexture(0, a.world.glColor, this.linearSampler);
			bindTexture(1, a.world.glDepth, this.nearestSampler);
			bindTexture(10, a.world.glOpaqueDepth, this.nearestSampler);
		} else {
			bindTexture(0, b.world.glColor, this.linearSampler);
			bindTexture(1, b.world.glDepth, this.nearestSampler);
			bindTexture(10, b.world.glOpaqueDepth, this.nearestSampler);
		}
		p.setInt("uEntCountA", movingA);
		this.statMovingEntities = Math.max(movingA, movingB);
		if (movingA + movingB > 0) this.flushMotion();
		GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, 0, this.entUbos[this.entUboIndex]);
		p.setInt("uUseA", a != null ? 1 : 0);
		p.setFloat("uWeightA", (float) weightA);
		p.setVec2("uOutSize", w, h);
		p.setInt("uIterations", config.quality.iterations);
		p.setInt("uBackgroundSearch", config.quality.backgroundSearch ? 1 : 0);
		p.setInt("uZeroToOne", b.zZeroToOne ? 1 : 0);
		p.setInt("uDebug", config.debugView && !this.testDraw ? 1 : 0);

		if (this.testDraw) {
			// Self-test: the world only, no HUD, no vignette.
			p.setInt("uVignette", 0);
			bindTexture(5, this.testBlank, this.linearSampler);
			bindTexture(4, this.testBlank, this.linearSampler);
			GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
			return;
		}
		if (overlay.vignette && overlay.vignetteTexture != 0) {
			p.setInt("uVignette", 1);
			p.setVec3("uVignetteColor", overlay.vignetteR, overlay.vignetteG, overlay.vignetteB);
			bindTexture(5, overlay.vignetteTexture, this.linearSampler);
		} else {
			p.setInt("uVignette", 0);
			bindTexture(5, b.world.glColor, this.linearSampler);
		}
		bindTexture(4, overlay.overlay.glColor, this.linearSampler);
		GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
	}

	/**
	 * Writes the entities of {@code src} that move between {@code src} and the generated moment into entity slot
	 * {@code slot} (0 = frame A, 1 = frame B) and
	 * returns their number. Entities that {@code other} (the other frame of the pair) also has are placed by
	 * interpolating between the two frames, exactly like the camera; the rest by their velocity. {@link #tgt} must hold
	 * the target matrix for {@code src} ({@link Pose#motionMatrices}).
	 */
	private int uploadMotion(int slot, FrameRecord src, @Nullable FrameRecord other, boolean srcIsA, double u, double targetSim) {
		java.nio.FloatBuffer buf = this.entBuf;
		if (src.entityCount == 0 || buf == null) return 0;
		buf.limit(buf.capacity()).position(slot * 4 * ENTITY_VEC4S * MAX_MOVING);
		this.srcInv.invert(this.srcVp);
		float[] sr = this.motionSrcRect;
		float[] tr = this.motionTgtRect;
		sr[0] = sr[1] = tr[0] = tr[1] = Float.MAX_VALUE;
		sr[2] = sr[3] = tr[2] = tr[3] = -Float.MAX_VALUE;
		double dt = Math.max(-0.25, Math.min(0.25, (targetSim - src.simNanos) / 1e9));
		int n = 0;
		for (int i = 0; i < src.entityCount && n < MAX_MOVING; i++) {
			double tx;
			double ty;
			double tz;
			double turnDeg = 0.0;
			int j = other != null ? other.findEntity(src.entityIds[i]) : -1;
			if (j >= 0) {
				// Displacement from frame A to frame B; this frame's share of it up to the generated moment.
				double sign = srcIsA ? 1.0 : -1.0;
				double dx = (other.entityPos[j * 3] - src.entityPos[i * 3]) * sign;
				double dy = (other.entityPos[j * 3 + 1] - src.entityPos[i * 3 + 1]) * sign;
				double dz = (other.entityPos[j * 3 + 2] - src.entityPos[i * 3 + 2]) * sign;
				// Moving more than about half its own size, or turning more than 25 degrees, between two real frames is
				// too much to reconstruct cleanly (parts of the model leave the box, the search can't follow the turn).
				// Showing the nearer real frame, as for static objects, looks better then.
				double limit = Math.max(0.5, 1.2 * src.entityBox[i * FrameRecord.BOX_FLOATS + 6]);
				if (dx * dx + dy * dy + dz * dz > limit * limit) continue;
				double share = srcIsA ? u : u - 1.0;
				tx = dx * share;
				ty = dy * share;
				tz = dz * share;
				float yawSrc = src.entityYaw[i];
				float yawOther = other.entityYaw[j];
				if (!Float.isNaN(yawSrc) && !Float.isNaN(yawOther)) {
					double dYaw = net.minecraft.util.Mth.wrapDegrees((double) (yawOther - yawSrc)) * sign;
					if (Math.abs(dYaw) > 25.0) continue;
					turnDeg = dYaw * share;
				}
			} else {
				tx = src.entityVel[i * 3] * dt;
				ty = src.entityVel[i * 3 + 1] * dt;
				tz = src.entityVel[i * 3 + 2] * dt;
				double limit = Math.max(0.5, 1.2 * src.entityBox[i * FrameRecord.BOX_FLOATS + 6]);
				if (tx * tx + ty * ty + tz * tz > limit * limit) continue;
			}
			if (tx * tx + ty * ty + tz * tz < 1e-8 && Math.abs(turnDeg) < 1e-3) continue;
			int b = i * FrameRecord.BOX_FLOATS;
			float cx = src.entityBox[b];
			float cz = src.entityBox[b + 1];
			float yMin = src.entityBox[b + 2];
			float yMax = src.entityBox[b + 3];
			float fx = src.entityBox[b + 4];
			float fz = src.entityBox[b + 5];
			float halfW = src.entityBox[b + 6];
			float halfL = src.entityBox[b + 7];
			float turn = (float) Math.toRadians(-turnDeg);
			buf.put(cx).put(cz).put(yMin).put(yMax);
			buf.put(fx).put(fz).put(halfW).put(halfL);
			buf.put((float) tx).put((float) ty).put((float) tz).put(0.0f);
			// The model is rotated by (180 - bodyRot) about +Y, so a body turn of +d turns the points by -d.
			buf.put(turn).put(0.0f).put(0.0f).put(0.0f);
			// Where the moved box lands on screen, and how far the entity moves on screen relative to static scenery
			// (so the shader can start its search from the object, see reprojectAll in warp.fsh).
			float cy = (yMin + yMax) * 0.5f;
			float x0 = Float.MAX_VALUE;
			float y0 = Float.MAX_VALUE;
			float x1 = -Float.MAX_VALUE;
			float y1 = -Float.MAX_VALUE;
			boolean behind = false;
			boolean srcBehind = false;
			float cos = (float) Math.cos(turn);
			float sin = (float) Math.sin(turn);
			for (int k = 0; k < 8; k++) {
				float along = (k & 1) == 0 ? -halfL : halfL;
				float side = (k & 4) == 0 ? -halfW : halfW;
				float rx = fx * along - fz * side;
				float rz = fz * along + fx * side;
				float py = (k & 2) == 0 ? yMin : yMax;
				this.tmpV.set(cx + rx, py, cz + rz, 1.0f);
				this.srcVp.transform(this.tmpV);
				if (this.tmpV.w <= 1e-4f) {
					srcBehind = true;
				} else {
					sr[0] = Math.min(sr[0], this.tmpV.x / this.tmpV.w - 0.02f);
					sr[1] = Math.min(sr[1], this.tmpV.y / this.tmpV.w - 0.02f);
					sr[2] = Math.max(sr[2], this.tmpV.x / this.tmpV.w + 0.02f);
					sr[3] = Math.max(sr[3], this.tmpV.y / this.tmpV.w + 0.02f);
				}
				this.tmpV.set(cx + cos * rx + sin * rz + (float) tx, py + (float) ty, cz - sin * rx + cos * rz + (float) tz, 1.0f);
				this.tgt.transform(this.tmpV);
				if (this.tmpV.w <= 1e-4f) {
					behind = true;
					continue;
				}
				x0 = Math.min(x0, this.tmpV.x / this.tmpV.w);
				y0 = Math.min(y0, this.tmpV.y / this.tmpV.w);
				x1 = Math.max(x1, this.tmpV.x / this.tmpV.w);
				y1 = Math.max(y1, this.tmpV.y / this.tmpV.w);
			}
			if (behind) {
				x0 = y0 = -2.0f;
				x1 = y1 = 2.0f;
			} else {
				x0 -= 0.02f;
				y0 -= 0.02f;
				x1 += 0.02f;
				y1 += 0.02f;
			}
			buf.put(x0).put(y0).put(x1).put(y1);
			tr[0] = Math.min(tr[0], x0);
			tr[1] = Math.min(tr[1], y0);
			tr[2] = Math.max(tr[2], x1);
			tr[3] = Math.max(tr[3], y1);
			if (srcBehind) {
				sr[0] = sr[1] = -2.0f;
				sr[2] = sr[3] = 2.0f;
			}
			this.tmpV.set(cx, cy, cz, 1.0f);
			this.tgt.transform(this.tmpV);
			float sx = 0.0f;
			float sy = 0.0f;
			if (this.tmpV.w > 1e-4f) {
				float ax = this.tmpV.x / this.tmpV.w;
				float ay = this.tmpV.y / this.tmpV.w;
				this.tmpV.set(cx + (float) tx, cy + (float) ty, cz + (float) tz, 1.0f);
				this.tgt.transform(this.tmpV);
				if (this.tmpV.w > 1e-4f) {
					sx = this.tmpV.x / this.tmpV.w - ax;
					sy = this.tmpV.y / this.tmpV.w - ay;
				}
			}
			buf.put(sx).put(sy).put(0.0f).put(0.0f);
			n++;
		}
		return n;
	}

	/**
	 * Sends the entity table written by {@link #uploadMotion} to the next buffer of the ring. Updating the buffer the
	 * previous draw reads would make some drivers wait for that draw to finish.
	 */
	private void flushMotion() {
		java.nio.FloatBuffer buf = this.entBuf;
		if (buf == null) return;
		buf.clear();
		this.entUboIndex = (this.entUboIndex + 1) % this.entUbos.length;
		GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, this.entUbos[this.entUboIndex]);
		GL33C.glBufferSubData(GL33C.GL_UNIFORM_BUFFER, 0L, buf);
		GL33C.glBindBuffer(GL33C.GL_UNIFORM_BUFFER, 0);
	}

	private void setMotionRects(GlProgram p, String frame, int moving) {
		if (moving == 0) {
			p.setVec4("uSrcRect" + frame, 2.0f, 2.0f, -2.0f, -2.0f);
			p.setVec4("uTgtRect" + frame, 2.0f, 2.0f, -2.0f, -2.0f);
			return;
		}
		float[] sr = this.motionSrcRect;
		float[] tr = this.motionTgtRect;
		p.setVec4("uSrcRect" + frame, sr[0], sr[1], sr[2], sr[3]);
		p.setVec4("uTgtRect" + frame, tr[0], tr[1], tr[2], tr[3]);
	}

	private void drawInvertQuads(FrameRecord r) {
		if (r.invertQuads.isEmpty() || r.width <= 0 || r.height <= 0) return;
		GlProgram p = this.invertQuad;
		p.use();
		GL33C.glEnable(GL33C.GL_BLEND);
		GL33C.glBlendEquation(GL33C.GL_FUNC_ADD);
		GL33C.glBlendFuncSeparate(GL33C.GL_ONE_MINUS_DST_COLOR, GL33C.GL_ONE_MINUS_SRC_COLOR, GL33C.GL_ONE, GL33C.GL_ZERO);
		float sx = 2.0f / r.width;
		float sy = 2.0f / r.height;
		for (FrameRecord.InvertQuad q : r.invertQuads) {
			p.setVec4("uRect", q.x0() * sx - 1.0f, 1.0f - q.y0() * sy, q.x1() * sx - 1.0f, 1.0f - q.y1() * sy);
			p.setVec4("uUvRect", q.u0(), q.v0(), q.u1(), q.v1());
			p.setVec4("uColor", q.r(), q.g(), q.b(), q.a());
			bindTexture(6, q.texture(), this.nearestSampler);
			GL33C.glDrawArrays(GL33C.GL_TRIANGLE_STRIP, 0, 4);
		}
		GL33C.glDisable(GL33C.GL_BLEND);
	}

	private static void bindTexture(int unit, int texture, int sampler) {
		GL33C.glActiveTexture(GL33C.GL_TEXTURE0 + unit);
		GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, texture);
		GL33C.glBindSampler(unit, sampler);
	}

	// ---- Self test (debug only) -------------------------------------------------------------------------------

	private long lastSelfTest;
	private int testFbo;
	private int testTexture;
	private int testBlank;
	private int testWidth;
	private int testHeight;

	/**
	 * Reprojects real frame {@code a} into the exact camera of real frame {@code b} and compares the result with
	 * {@code b} itself. Logs the PSNR of the reprojection next to the PSNR of simply reusing {@code a}: if the
	 * geometry (matrices, depth convention, camera-relative translation) is right, the first is much higher.
	 * Only differences from things that moved on their own and from disocclusions remain.
	 */
	private void selfTest(FrameRecord a, FrameRecord b) {
		int w = b.world.width;
		int h = b.world.height;
		if (this.testFbo == 0 || this.testWidth != w || this.testHeight != h) {
			if (this.testFbo == 0) {
				this.testFbo = GL33C.glGenFramebuffers();
				this.testBlank = GL33C.glGenTextures();
				GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, this.testBlank);
				GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, 1, 1, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, new int[]{0});
			}
			if (this.testTexture != 0) GL33C.glDeleteTextures(this.testTexture);
			this.testTexture = GL33C.glGenTextures();
			GL33C.glBindTexture(GL33C.GL_TEXTURE_2D, this.testTexture);
			GL33C.glTexImage2D(GL33C.GL_TEXTURE_2D, 0, GL33C.GL_RGBA8, w, h, 0, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);
			this.testWidth = w;
			this.testHeight = h;
		}
		GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, this.testFbo);
		GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, this.testTexture, 0);
		GL33C.glViewport(0, 0, w, h);
		GL33C.glBindVertexArray(this.vao);

		FrameGenConfig config = FrameGenConfig.get();
		GlProgram p = this.warp;
		p.use();
		// Output framed like B's world layer (margin included) so it can be compared with it pixel by pixel. Only the
		// part the screen shows is scored below: the margin is there to be used, not to be reconstructed.
		this.target.set(b).showAsRendered();
		this.poseA.set(a);
		this.target.reprojection(this.poseA, this.fwd, this.fwdRot, this.inv, this.invRot);
		p.setMat4("uFwdB", this.fwd);
		p.setMat4("uFwdRotB", this.fwdRot);
		p.setMat4("uInvB", this.inv);
		p.setMat4("uInvRotB", this.invRot);
		// A's entities moved to where they are in B (as the generated frames do).
		this.target.motionMatrices(this.poseA, this.srcInv, this.tgt);
		p.setMat4("uSrcInvB", this.srcInv);
		p.setMat4("uTgtB", this.tgt);
		int moving = config.movingObjects ? this.uploadMotion(1, a, b, true, 1.0, b.simNanos) : 0;
		p.setInt("uEntCountB", moving);
		this.setMotionRects(p, "B", moving);
		if (moving > 0) this.flushMotion();
		p.setInt("uEntCountA", 0);
		p.setInt("uOpaqueA", 0);
		p.setInt("uOpaqueB", 0);
		GL33C.glBindBufferBase(GL33C.GL_UNIFORM_BUFFER, 0, this.entUbos[this.entUboIndex]);
		p.setVec2("uSizeB", w, h);
		p.setVec2("uOutSize", w, h);
		p.setInt("uUseA", 0);
		p.setFloat("uWeightA", 0.0f);
		p.setInt("uIterations", config.quality.iterations);
		p.setInt("uBackgroundSearch", config.quality.backgroundSearch ? 1 : 0);
		p.setInt("uZeroToOne", a.zZeroToOne ? 1 : 0);
		p.setInt("uDebug", 0);
		p.setInt("uVignette", 0);
		bindTexture(0, a.world.glColor, this.linearSampler);
		bindTexture(1, a.world.glDepth, this.nearestSampler);
		bindTexture(2, a.world.glColor, this.linearSampler);
		bindTexture(3, a.world.glDepth, this.nearestSampler);
		bindTexture(4, this.testBlank, this.linearSampler);
		bindTexture(5, this.testBlank, this.linearSampler);
		java.nio.ByteBuffer warped = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer real = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer old = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer cameraOnly = moving > 0 ? org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4) : null;
		try {
			if (cameraOnly != null) {
				// Same pair without entity motion, to measure what it adds.
				p.setInt("uEntCountB", 0);
				GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
				GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, cameraOnly);
				p.setInt("uEntCountB", moving);
			}
			GL33C.glDrawArrays(GL33C.GL_TRIANGLES, 0, 3);
			GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, warped);
			GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, b.world.glColor, 0);
			GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, real);
			GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, a.world.glColor, 0);
			GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, old);
			if (!Double.isNaN(a.debugHitDistance)) {
				// Decode the depth at the screen center of A back to a distance and compare with the raycast.
				java.nio.FloatBuffer depth = org.lwjgl.system.MemoryUtil.memAllocFloat(1);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, 0, 0);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT, GL33C.GL_TEXTURE_2D, a.world.glDepth, 0);
				GL33C.glReadPixels(w / 2, h / 2, 1, 1, GL33C.GL_DEPTH_COMPONENT, GL33C.GL_FLOAT, depth);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_DEPTH_ATTACHMENT, GL33C.GL_TEXTURE_2D, 0, 0);
				float d = depth.get(0);
				org.lwjgl.system.MemoryUtil.memFree(depth);
				org.joml.Matrix4d invP = new org.joml.Matrix4d(a.projection).invert();
				org.joml.Vector4d v = invP.transform(new org.joml.Vector4d(0.0, 0.0, a.zZeroToOne ? d : d * 2.0 - 1.0, 1.0));
				double dist = Math.sqrt(v.x * v.x + v.y * v.y + v.z * v.z) / v.w;
				FrameGenMod.LOGGER.info("[self-test] center depth {} -> {} blocks, raycast says {} blocks (zZeroToOne={})",
					d, String.format("%.3f", dist), String.format("%.3f", a.debugHitDistance), a.zZeroToOne);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, this.testTexture, 0);
			}
			int x0 = Math.round(w * 0.5f * (1.0f - b.overscanX));
			int y0 = Math.round(h * 0.5f * (1.0f - b.overscanY));
			int x1 = w - x0;
			int y1 = h - y0;
			int visible = (x1 - x0) * (y1 - y0);
			double warpedPsnr = psnr(warped, real, w, x0, y0, x1, y1, null);
			double reusePsnr = psnr(old, real, w, x0, y0, x1, y1, null);
			int[] covered = new int[1];
			double coveredPsnr = psnr(warped, real, w, x0, y0, x1, y1, covered);
			if (cameraOnly != null) {
				FrameGenMod.LOGGER.info("[self-test] moving entities: PSNR {} dB with entity motion vs. {} dB camera only",
					String.format("%.2f", warpedPsnr), String.format("%.2f", psnr(cameraOnly, real, w, x0, y0, x1, y1, null)));
				if (DUMP_DIR != null && this.dumps < 6) {
					dumpImages(this.dumps++, w, h, old, real, cameraOnly, warped);
				}
			}
			double dist = Pose.distance(this.poseA, this.target);
			double angle = Math.toDegrees(Pose.angleBetween(this.poseA, this.target));
			// Micro-benchmark: the full two-frame interpolation pass, isolated with glFinish.
			if (!this.history.isEmpty() && this.history.getLast().overlay != null) {
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, this.testTexture, 0);
				this.poseA.set(a);
				this.poseB.set(b);
				this.target.blend(this.poseA, this.poseB, 0.5);
				Pose saved = new Pose().set(this.target);
				double[] ms = new double[2];
				int runs = 8;
				for (int variant = 0; variant < 2; variant++) {
					GL33C.glFinish();
					long t0 = System.nanoTime();
					for (int i = 0; i < runs; i++) {
						this.target.set(saved);
						this.drawWarp(config, a, b, 0.5, w, h, a, variant == 0 ? Double.NaN : 0.5, (a.simNanos + b.simNanos) * 0.5);
					}
					GL33C.glFinish();
					ms[variant] = (System.nanoTime() - t0) / 1e6 / runs;
				}
				FrameGenMod.LOGGER.info("[bench] interpolation pass at {}x{}, quality {}: {} ms/frame camera only, {} ms/frame with {} moving entities",
					w, h, config.quality, String.format("%.2f", ms[0]), String.format("%.2f", ms[1]), this.statMovingEntities);
			}
			FrameGenMod.LOGGER.info("[self-test] camera moved {} blocks / {} deg: reprojection PSNR {} dB vs. frame reuse {} dB; {}% of pixels reconstructed confidently at {} dB; {} entities, {} moving",
				String.format("%.2f", dist), String.format("%.1f", angle), String.format("%.1f", warpedPsnr), String.format("%.1f", reusePsnr),
				String.format("%.1f", 100.0 * covered[0] / visible), String.format("%.1f", coveredPsnr), a.entityCount, moving);
		} finally {
			org.lwjgl.system.MemoryUtil.memFree(warped);
			org.lwjgl.system.MemoryUtil.memFree(real);
			org.lwjgl.system.MemoryUtil.memFree(old);
			if (cameraOnly != null) org.lwjgl.system.MemoryUtil.memFree(cameraOnly);
			GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, 0, 0);
			GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
			GL33C.glBindVertexArray(0);
		}
	}

	/** The world frame before {@code r} in the history, or null. */
	private @Nullable FrameRecord olderWorld(FrameRecord r) {
		FrameRecord found = null;
		for (FrameRecord h : this.history) {
			if (h == r) return found;
			if (h.hasWorld()) found = h;
		}
		return null;
	}

	private boolean testDraw;
	private boolean testNoSeeThrough;

	/**
	 * Interpolation self-test (debug only): generates the frame between real frames {@code f0} and {@code f2} at the
	 * moment of {@code f1}, exactly as Interpolate mode would, and compares it with the real {@code f1}. Run with and
	 * without moving-entity reprojection.
	 */
	private void interpolationTest(FrameRecord f0, FrameRecord f1, FrameRecord f2) {
		int w = f1.world.width;
		int h = f1.world.height;
		if (this.testFbo == 0 || this.testWidth != w || this.testHeight != h) return; // selfTest set these up
		FrameGenConfig config = FrameGenConfig.get();
		double span = Math.max(1.0, f2.simNanos - f0.simNanos);
		double u = (f1.simNanos - f0.simNanos) / span;
		GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, this.testFbo);
		GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, this.testTexture, 0);
		GL33C.glViewport(0, 0, w, h);
		GL33C.glBindVertexArray(this.vao);
		java.nio.ByteBuffer camOnly = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer moving = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer real = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		java.nio.ByteBuffer noSeeThrough = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
		this.testDraw = true;
		try {
			for (int pass = 0; pass < 3; pass++) {
				this.target.set(f1).showAsRendered();
				this.testNoSeeThrough = pass == 2;
				this.drawWarp(config, f0, f2, 1.0 - u, w, h, f0, pass == 0 ? Double.NaN : u, f1.simNanos);
				GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, pass == 0 ? camOnly : pass == 1 ? moving : noSeeThrough);
			}
			this.testNoSeeThrough = false;
			int movingCount = this.statMovingEntities;
			GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, f1.world.glColor, 0);
			GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, real);
			int x0 = Math.round(w * 0.5f * (1.0f - f1.overscanX));
			int y0 = Math.round(h * 0.5f * (1.0f - f1.overscanY));
			double withMotion = psnr(moving, real, w, x0, y0, w - x0, h - y0, null);
			double cameraOnly = psnr(camOnly, real, w, x0, y0, w - x0, h - y0, null);
			FrameGenMod.LOGGER.info("[self-test] interpolation at t={}: PSNR {} dB with entity motion ({} moving) vs. {} dB camera only",
				String.format("%.2f", u), String.format("%.2f", withMotion), movingCount, String.format("%.2f", cameraOnly));
			if (f0.hasOpaqueDepth && f2.hasOpaqueDepth) {
				FrameGenMod.LOGGER.info("[self-test] see-through surfaces: PSNR {} dB with the fix vs. {} dB without; camera moved {} blocks",
					String.format("%.2f", withMotion), String.format("%.2f", psnr(noSeeThrough, real, w, x0, y0, w - x0, h - y0, null)),
					String.format("%.2f", Math.sqrt((f2.camX - f0.camX) * (f2.camX - f0.camX) + (f2.camY - f0.camY) * (f2.camY - f0.camY) + (f2.camZ - f0.camZ) * (f2.camZ - f0.camZ))));
			}
			if (DUMP_DIR != null && f0.hasOpaqueDepth && this.interpDumps < 6) {
				java.nio.ByteBuffer a = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
				java.nio.ByteBuffer b = org.lwjgl.system.MemoryUtil.memAlloc(w * h * 4);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, f0.world.glColor, 0);
				GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, a);
				GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, f2.world.glColor, 0);
				GL33C.glReadPixels(0, 0, w, h, GL33C.GL_RGBA, GL33C.GL_UNSIGNED_BYTE, b);
				dumpImages(100 + this.interpDumps++, w, h, a, real, b, noSeeThrough, moving);
				org.lwjgl.system.MemoryUtil.memFree(a);
				org.lwjgl.system.MemoryUtil.memFree(b);
			}
		} finally {
			this.testDraw = false;
			org.lwjgl.system.MemoryUtil.memFree(camOnly);
			org.lwjgl.system.MemoryUtil.memFree(moving);
			org.lwjgl.system.MemoryUtil.memFree(real);
			org.lwjgl.system.MemoryUtil.memFree(noSeeThrough);
			GL33C.glFramebufferTexture2D(GL33C.GL_FRAMEBUFFER, GL33C.GL_COLOR_ATTACHMENT0, GL33C.GL_TEXTURE_2D, 0, 0);
			GL33C.glBindFramebuffer(GL33C.GL_FRAMEBUFFER, 0);
			GL33C.glBindVertexArray(0);
		}
	}

	private int interpDumps;


	/** Dev only ({@code -Dframegen.debug.dump=<dir>}): self-test images A | B | camera-only | with entity motion. */
	private static final @Nullable String DUMP_DIR = System.getProperty("framegen.debug.dump");
	private int dumps;

	private static void dumpImages(int index, int w, int h, java.nio.ByteBuffer... images) {
		try {
			java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w * images.length, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
			for (int k = 0; k < images.length; k++) {
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int i = ((h - 1 - y) * w + x) * 4;
						java.nio.ByteBuffer b = images[k];
						out.setRGB(k * w + x, y, (b.get(i) & 0xFF) << 16 | (b.get(i + 1) & 0xFF) << 8 | (b.get(i + 2) & 0xFF));
					}
				}
			}
			java.nio.file.Path dir = java.nio.file.Path.of(DUMP_DIR);
			java.nio.file.Files.createDirectories(dir);
			javax.imageio.ImageIO.write(out, "png", dir.resolve("selftest_" + index + ".png").toFile());
		} catch (Exception e) {
			FrameGenMod.LOGGER.warn("dump failed", e);
		}
	}

	/**
	 * PSNR over the pixels in [x0, x1) x [y0, y1) of two images {@code width} pixels wide, or (if {@code coveredOut} is
	 * given) only those whose alpha in {@code x} marks them confident.
	 */
	private static double psnr(java.nio.ByteBuffer x, java.nio.ByteBuffer y, int width, int x0, int y0, int x1, int y1, int @Nullable [] coveredOut) {
		double sum = 0.0;
		int counted = 0;
		for (int py = y0; py < y1; py++) {
			for (int px = x0; px < x1; px++) {
				int i = py * width + px;
				if (coveredOut != null && (x.get(i * 4 + 3) & 0xFF) < 128) continue;
				counted++;
				for (int c = 0; c < 3; c++) {
					int d = (x.get(i * 4 + c) & 0xFF) - (y.get(i * 4 + c) & 0xFF);
					sum += d * d;
				}
			}
		}
		if (coveredOut != null) coveredOut[0] = counted;
		if (counted == 0) return 0.0;
		double mse = sum / (counted * 3.0);
		return mse <= 1e-9 ? 99.0 : 10.0 * Math.log10(255.0 * 255.0 / mse);
	}

	// ---- Slot lifetime ----------------------------------------------------------------------------------------

	/** After presenting: overlays of everything but the newest record are no longer needed. */
	private void releaseUnneeded(FrameRecord newest) {
		for (FrameRecord r : this.history) {
			if (r != newest && r.overlay != null) {
				this.exchange.release(r.overlay, fence());
				r.overlay = null;
			}
		}
	}

	private void releaseRecord(FrameRecord r, boolean fenced) {
		if (r.world != null) {
			this.exchange.release(r.world, fenced ? fence() : 0L);
			r.world = null;
		}
		if (r.overlay != null) {
			this.exchange.release(r.overlay, fenced ? fence() : 0L);
			r.overlay = null;
		}
	}

	private static long fence() {
		long sync = GL33C.glFenceSync(GL33C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		GL33C.glFlush();
		return sync;
	}

	// ---- GPU timing -----------------------------------------------------------------------------------------------

	private final int[] timerQueries = new int[4];
	private final boolean[] timerPending = new boolean[4];
	private int timerIndex;
	private double gpuMsAccum;
	private int gpuSamples;
	private volatile float statGpuMs;

	/** GPU time of the presenter's own drawing (the cost it adds on top of the game), via GL_TIME_ELAPSED queries. */
	private void beginGpuTimer() {
		if (this.timerQueries[0] == 0) {
			GL33C.glGenQueries(this.timerQueries);
		}
		// Collect finished results without stalling.
		for (int i = 0; i < this.timerQueries.length; i++) {
			if (this.timerPending[i] && GL33C.glGetQueryObjecti(this.timerQueries[i], GL33C.GL_QUERY_RESULT_AVAILABLE) != 0) {
				this.gpuMsAccum += GL33C.glGetQueryObjecti64(this.timerQueries[i], GL33C.GL_QUERY_RESULT) / 1e6;
				this.gpuSamples++;
				this.timerPending[i] = false;
			}
		}
		this.timerIndex = (this.timerIndex + 1) % this.timerQueries.length;
		if (this.timerPending[this.timerIndex]) {
			this.timerIndex = -1; // all queries still in flight; skip timing this frame
			return;
		}
		GL33C.glBeginQuery(GL33C.GL_TIME_ELAPSED, this.timerQueries[this.timerIndex]);
	}

	private void endGpuTimer() {
		if (this.timerIndex < 0) {
			this.timerIndex = 0;
			return;
		}
		GL33C.glEndQuery(GL33C.GL_TIME_ELAPSED);
		this.timerPending[this.timerIndex] = true;
	}

	public float gpuMs() {
		return this.statGpuMs;
	}

	// ---- Pacing -----------------------------------------------------------------------------------------------

	private void pace(FrameGenConfig config, boolean generating, long presentedAt) {
		double hz = this.displayHz;
		double refreshNanos = 1e9 / hz;
		if (this.vsync) {
			// Detect drivers/compositors where swap doesn't block (or block reliably) and fall back to a timer.
			long since = presentedAt - this.lastPresentNanos;
			if (this.lastPresentNanos > 0 && since < refreshNanos * 0.5) {
				if (++this.vsyncIneffectiveStreak > 20) this.vsyncHonored = false;
			} else if (since > refreshNanos * 0.8) {
				this.vsyncIneffectiveStreak = 0;
				this.vsyncHonored = true;
			}
			if (this.vsyncHonored) return;
		}
		if (!generating) return; // passthrough without V-Sync: present as fast as frames arrive, like vanilla
		double fps = config.targetOutputFps > 0 ? config.targetOutputFps : hz;
		long deadline = this.lastPresentNanos + (long) (1e9 / fps);
		sleepUntil(deadline);
	}

	private static void sleepUntil(long deadline) {
		while (true) {
			long remaining = deadline - System.nanoTime();
			if (remaining <= 0L) return;
			if (remaining > 1_500_000L) {
				LockSupport.parkNanos(remaining - 1_000_000L);
			} else {
				Thread.onSpinWait();
			}
		}
	}

	private void updateStats(long now) {
		long elapsed = now - this.statWindowStart;
		if (elapsed >= 500_000_000L) {
			double seconds = elapsed / 1e9;
			this.statOutputFps = (float) (this.statOutputFrames / seconds);
			this.statRealFps = (float) (this.statRealFrames / seconds);
			this.statPresentCostMs = this.statOutputFrames > 0 ? (float) (this.statCostAccum / this.statOutputFrames) : 0.0f;
			this.statLatencyMs = (float) (Math.max(0.0, this.delayNanos) / 1e6);
			if (DEBUG_LOG && now - this.lastDebugLog > 2_000_000_000L) {
				this.lastDebugLog = now;
				FrameGenMod.LOGGER.info("[presenter] out {} fps, real {} fps, generating={}, delay {} ms, turning {} ms, latency~{} ms, interval~{} ms, cost {} ms, gpu {} ms, history {}, inflight {}",
					String.format("%.1f", this.statOutputFps), String.format("%.1f", this.statRealFps), this.statGenerating + (this.statNative ? " (native)" : ""),
					String.format("%.1f", this.statLatencyMs), this.cameraDelaySamples > 0 ? String.format("%.1f", this.cameraDelayAccum / this.cameraDelaySamples) : "-",
					String.format("%.1f", this.latencyHigh / 1e6), String.format("%.1f", this.intervalHigh / 1e6),
					String.format("%.2f", this.statPresentCostMs), String.format("%.3f", this.statGpuMs), this.history.size(), this.inflight.size());
			}
			this.statOutputFrames = 0;
			this.statRealFrames = 0;
			this.statCostAccum = 0.0;
			if (this.gpuSamples > 0) {
				this.statGpuMs = (float) (this.gpuMsAccum / this.gpuSamples);
				this.gpuMsAccum = 0.0;
				this.gpuSamples = 0;
			}
			this.statCameraDelayMs = this.cameraDelaySamples > 0 ? (float) (this.cameraDelayAccum / this.cameraDelaySamples) : Float.NaN;
			this.cameraDelayAccum = 0.0;
			this.cameraDelaySamples = 0;
			this.statWindowStart = now;
		}
	}
}
