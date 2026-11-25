package dev.framegen.engine;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuSurface;
import com.mojang.renderpearl.api.device.SurfaceException;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import dev.framegen.compat.PresentHookReplayer;
import java.util.Collection;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.lwjgl.sdl.SDLVideo;

/**
 * Replaces Minecraft's window surface on the OpenGL backend. The game keeps "presenting" through this object, but
 * instead of blitting and swapping, each finished frame is captured and handed to the {@link Presenter} thread,
 * which owns the real window.
 *
 * <p>If the presenter dies for any reason, this falls back to the vanilla surface so the game keeps running.
 */
final class FrameGenSurface implements GpuSurface {
	private final GpuSurface vanilla;
	private final Presenter presenter;
	private final FrameExchange exchange;
	private final FrameCapture capture;
	private final long presenterContext;
	private final BooleanSupplier iconified;
	private Optional<Configuration> configuration = Optional.empty();
	private boolean acquired;
	private boolean blitted;
	private boolean fallback;
	private boolean closed;
	private int stalls;
	/** Other mods' {@code GlSurface.present} injections, re-run here because the game no longer calls it. */
	private final PresentHookReplayer presentHooks;

	FrameGenSurface(GpuSurface vanilla, Presenter presenter, FrameExchange exchange, FrameCapture capture, long presenterContext, BooleanSupplier iconified) {
		this.vanilla = vanilla;
		this.presenter = presenter;
		this.exchange = exchange;
		this.capture = capture;
		this.presenterContext = presenterContext;
		this.iconified = iconified;
		this.presentHooks = new PresentHookReplayer(vanilla);
	}

	boolean isPresenterActive() {
		return !this.fallback && !this.closed && this.presenter.isAlive();
	}

	private boolean checkFallback() {
		if (!this.fallback && !this.presenter.isAlive()) {
			FrameGenMod.LOGGER.warn("FrameGen presenter is not running; falling back to vanilla presentation");
			this.fallback = true;
			this.acquired = false;
			this.configuration.ifPresent(config -> {
				try {
					this.vanilla.configure(config);
				} catch (SurfaceException e) {
					FrameGenMod.LOGGER.error("Couldn't configure the vanilla surface after falling back", e);
				}
			});
		}
		return this.fallback;
	}

	@Override
	public void configure(Configuration config) throws SurfaceException {
		Optional<Configuration> previous = this.configuration;
		this.configuration = Optional.of(config);
		if (this.checkFallback()) {
			this.vanilla.configure(config);
			return;
		}
		if (Presenter.DEBUG_LOG && !previous.equals(this.configuration)) {
			FrameGenMod.LOGGER.info("[surface] configure {}x{} {}", config.width(), config.height(), config.presentMode());
		}
		this.presenter.configure(config.width(), config.height(), config.presentMode() == PresentMode.FIFO || config.presentMode() == PresentMode.FIFO_RELAXED);
	}

	@Override
	public Optional<Configuration> currentConfiguration() {
		return this.fallback ? this.vanilla.currentConfiguration() : this.configuration;
	}

	@Override
	public Collection<PresentMode> supportedPresentModes() {
		return this.vanilla.supportedPresentModes();
	}

	@Override
	public boolean isSuboptimal() {
		return this.fallback ? this.vanilla.isSuboptimal() : false;
	}

	@Override
	public boolean isAcquired() {
		return this.fallback ? this.vanilla.isAcquired() : this.acquired;
	}

	@Override
	public void acquireNextTexture() throws SurfaceException {
		if (this.checkFallback()) {
			this.vanilla.acquireNextTexture();
			return;
		}
		if (this.acquired) {
			throw new IllegalStateException("Cannot acquire a surface while it is already acquired");
		}
		if (this.configuration.isEmpty()) {
			throw new IllegalStateException("Cannot acquire an unconfigured surface");
		}
		if (this.iconified.getAsBoolean()) {
			throw new SurfaceException("Cannot acquire minimized window");
		}
		this.acquired = true;
		this.blitted = false;
	}

	@Override
	public void blitFromTexture(CommandEncoder commandEncoder, GpuTextureView textureView) {
		if (this.fallback) {
			this.vanilla.blitFromTexture(commandEncoder, textureView);
			return;
		}
		if (!this.acquired) {
			throw new IllegalStateException("Cannot present to an unacquired surface");
		}
		if (this.blitted) {
			throw new IllegalStateException("Already blitted to this frame!");
		}
		this.blitted = this.capture.finishFrame(textureView);
	}

	@Override
	public void present() {
		if (this.fallback) {
			this.vanilla.present();
			return;
		}
		if (!this.acquired) {
			throw new IllegalStateException("Cannot present to a surface if it isn't acquired");
		}
		this.acquired = false;
		if (!this.blitted) {
			return;
		}
		this.presentHooks.beforePresent();
		FrameRecord record = this.capture.publish();
		this.presentHooks.afterPresent();
		if (record == null) return;

		// Without generation the presenter shows each frame exactly once; emulate V-Sync's blocking swap so the game
		// doesn't render frames nobody will see.
		boolean passthrough = record.opaque || !FrameGenConfig.get().enabled || this.presenter.isNative();
		boolean vsync = this.configuration.map(c -> c.presentMode() == PresentMode.FIFO).orElse(false);
		if (passthrough && vsync) {
			if (!this.exchange.awaitDisplayed(record.id, 1, 100L)) {
				// The presenter didn't show the previous frame within 100 ms: menus would feel frozen. Log it so it
				// can be diagnosed from latest.log.
				if (this.stalls++ % 200 == 0) {
					FrameGenMod.LOGGER.warn("FrameGen presenter is not keeping up with the game (frame {} not shown after 100 ms, {} stalls so far). "
						+ "If menus or the game feel frozen, please report this with your latest.log.", record.id - 1, this.stalls);
				}
			}
		}
	}

	@Override
	public void close() {
		if (this.closed) return;
		this.closed = true;
		this.presenter.stop();
		this.capture.close();
		this.exchange.destroy();
		if (this.presenterContext != 0L) {
			SDLVideo.SDL_GL_DestroyContext(this.presenterContext);
		}
		this.vanilla.close();
	}
}
