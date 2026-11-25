package dev.framegen.engine;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.DeviceInfo;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.sdl.SDLError;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.sdl.SDLVideo;
import org.lwjgl.sdl.SDL_DisplayMode;

/** Static entry points used by the mixins. Everything here runs on the render thread unless noted. */
public final class FrameGenEngine {
	public enum Status {
		NOT_STARTED,
		ACTIVE,
		UNSUPPORTED_BACKEND,
		FAILED
	}

	private static Status status = Status.NOT_STARTED;
	private static @Nullable String statusDetail;
	private static @Nullable FrameGenSurface surface;
	private static @Nullable Presenter presenter;
	private static @Nullable FrameCapture capture;
	private static long window;
	private static long lastDisplayPoll;
	private static volatile @Nullable Throwable presenterCrash;

	private FrameGenEngine() {
	}

	/**
	 * Called in place of {@code GpuDevice.createSurface} while Minecraft starts. Sets up the presenter if the device
	 * is OpenGL; otherwise returns the vanilla surface untouched.
	 */
	public static GpuSurface wrapSurface(GpuSurface vanilla, long windowHandle, BooleanSupplier iconified) {
		if (Boolean.getBoolean("framegen.disable")) {
			status = Status.FAILED;
			statusDetail = "disabled with -Dframegen.disable=true";
			FrameGenMod.LOGGER.warn("FrameGen disabled by -Dframegen.disable=true");
			return vanilla;
		}
		DeviceInfo info = RenderSystem.getDevice().getDeviceInfo();
		if (!"OpenGL".equals(info.backendName())) {
			status = Status.UNSUPPORTED_BACKEND;
			statusDetail = info.backendName();
			FrameGenMod.LOGGER.warn("FrameGen needs the OpenGL backend (running on {}); frame generation is disabled", info.backendName());
			return vanilla;
		}

		long mcContext = SDLVideo.SDL_GL_GetCurrentContext();
		long mcWindow = SDLVideo.SDL_GL_GetCurrentWindow();
		if (mcContext == 0L || mcWindow == 0L) {
			return fail(vanilla, "no current OpenGL context on the render thread");
		}

		// A second context in Minecraft's share group, created for the visible window. It will be current on the
		// presenter thread; Minecraft's own context stays bound to its hidden utility window and renders offscreen.
		//
		// Contexts can only share objects if their KHR_no_error state matches. Sodium turns that on for Minecraft's
		// context by default on supported drivers, so mirror whatever Minecraft's context actually has instead of
		// relying on SDL's attribute state.
		boolean noError = (GL11C.glGetInteger(GL30C.GL_CONTEXT_FLAGS) & GL_CONTEXT_FLAG_NO_ERROR_BIT) != 0;
		int previousNoError = getSdlAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR, noError ? 1 : 0);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 1);
		long presenterContext = SDLVideo.SDL_GL_CreateContext(windowHandle);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
		SDLVideo.SDL_GL_SetAttribute(SDLVideo.SDL_GL_CONTEXT_NO_ERROR, previousNoError);
		String createError = presenterContext == 0L ? SDLError.SDL_GetError() : null;
		if (!SDLVideo.SDL_GL_MakeCurrent(mcWindow, mcContext)) {
			// Should never happen; if it does the game can't continue on this thread anyway.
			throw new IllegalStateException("FrameGen couldn't restore Minecraft's OpenGL context: " + SDLError.SDL_GetError());
		}
		if (presenterContext == 0L) {
			return fail(vanilla, "couldn't create a shared OpenGL context: " + createError);
		}

		FrameExchange exchange = new FrameExchange();
		Presenter p = new Presenter(exchange, windowHandle, presenterContext, iconified);
		if (!p.start()) {
			SDLVideo.SDL_GL_DestroyContext(presenterContext);
			return fail(vanilla, "presenter thread failed to start (see log)");
		}

		window = windowHandle;
		presenter = p;
		capture = new FrameCapture(exchange, info.isZZeroToOne());
		surface = new FrameGenSurface(vanilla, p, exchange, capture, presenterContext, iconified);
		status = Status.ACTIVE;
		statusDetail = info.name();
		pollDisplay(true);
		FrameGenMod.LOGGER.info("FrameGen active on {} ({}), presenting on a dedicated thread", info.name(), info.vendorName());
		return surface;
	}

	private static final int GL_CONTEXT_FLAG_NO_ERROR_BIT = 0x00000008;

	private static int getSdlAttribute(int attribute) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer value = stack.mallocInt(1);
			return SDLVideo.SDL_GL_GetAttribute(attribute, value) ? value.get(0) : 0;
		}
	}

	private static GpuSurface fail(GpuSurface vanilla, String reason) {
		status = Status.FAILED;
		statusDetail = reason;
		FrameGenMod.LOGGER.error("FrameGen disabled: {}", reason);
		return vanilla;
	}

	/** One-line description of the window for diagnostics (render thread). */
	static String describeWindow() {
		if (window == 0L) return "?";
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer lw = stack.mallocInt(1);
			IntBuffer lh = stack.mallocInt(1);
			IntBuffer pw = stack.mallocInt(1);
			IntBuffer ph = stack.mallocInt(1);
			SDLVideo.SDL_GetWindowSize(window, lw, lh);
			SDLVideo.SDL_GetWindowSizeInPixels(window, pw, ph);
			float density = SDLVideo.SDL_GetWindowPixelDensity(window);
			float scale = SDLVideo.SDL_GetWindowDisplayScale(window);
			long flags = SDLVideo.SDL_GetWindowFlags(window);
			return String.format(java.util.Locale.ROOT, "window %dx%d logical, %dx%d pixels, pixel density %.2f, display scale %.2f, fullscreen %s",
				lw.get(0), lh.get(0), pw.get(0), ph.get(0), density, scale, (flags & SDLVideo.SDL_WINDOW_FULLSCREEN) != 0);
		} catch (Throwable t) {
			return "? (" + t + ")";
		}
	}

	static void onPresenterCrashed(Throwable t) {
		presenterCrash = t;
	}

	public static Status status() {
		if (status == Status.ACTIVE && (surface == null || !surface.isPresenterActive())) {
			return Status.FAILED;
		}
		return status;
	}

	public static @Nullable String statusDetail() {
		Throwable crash = presenterCrash;
		return crash != null ? "presenter crashed: " + crash : statusDetail;
	}

	public static boolean isPresenterRunning() {
		return surface != null && surface.isPresenterActive();
	}

	public static @Nullable FrameCapture capture() {
		return isPresenterRunning() ? capture : null;
	}

	public static @Nullable Presenter presenter() {
		return presenter;
	}

	/** Render thread, once per frame: keep the presenter informed of the display's refresh rate. */
	public static void pollDisplay(boolean force) {
		if (presenter == null || window == 0L) return;
		long now = System.nanoTime();
		if (!force && now - lastDisplayPoll < 1_000_000_000L) return;
		lastDisplayPoll = now;
		int display = SDLVideo.SDL_GetDisplayForWindow(window);
		if (display == 0) return;
		SDL_DisplayMode mode = SDLVideo.SDL_GetCurrentDisplayMode(display);
		if (mode != null && mode.refresh_rate() > 0.0f) {
			presenter.setDisplayHz(mode.refresh_rate());
		}
	}

	/**
	 * Frame rate a dynamic resolution scaler (Supersample) should aim for. While frame generation is on in a world,
	 * that's the output rate divided by the multiplier (at least 2), so the scaler spends only half the frame budget
	 * and FrameGen fills in the rest. Otherwise the scaler's own target is kept.
	 */
	public static double adjustResolutionScalerTarget(double target) {
		Presenter p = presenter;
		FrameGenConfig config = FrameGenConfig.get();
		var mc = net.minecraft.client.Minecraft.getInstance();
		if (p == null || !isPresenterRunning() || !config.enabled || mc.level == null || mc.gui.screen() != null) {
			return target;
		}
		double output = config.targetOutputFps > 0 ? config.targetOutputFps : p.displayHz();
		double base = output / Math.max(2, config.multiplier);
		if (!compatLogged) {
			compatLogged = true;
			FrameGenMod.LOGGER.info("FrameGen: asking the dynamic resolution scaler to target {} fps (output {} fps)",
				Math.round(Math.min(target, base)), Math.round(output));
		}
		return Math.max(30.0, Math.min(target, base));
	}

	private static boolean compatLogged;

	/**
	 * The game's frame rate cap while generating: leave the GPU room for the presenter and give the interpolator
	 * evenly spaced input. Returns the vanilla limit unchanged when frame generation isn't running.
	 */
	public static int adjustFramerateLimit(int vanillaLimit) {
		Presenter p = presenter;
		FrameGenConfig config = FrameGenConfig.get();
		if (p == null || !isPresenterRunning() || !config.enabled || config.multiplier <= 0 || !(p.isGenerating() || p.isNative())) {
			return vanillaLimit;
		}
		double output = config.targetOutputFps > 0 ? config.targetOutputFps : p.displayHz();
		int cap = Math.max(15, (int) Math.round(output / config.multiplier));
		return vanillaLimit >= 260 ? cap : Math.min(vanillaLimit, cap);
	}
}
