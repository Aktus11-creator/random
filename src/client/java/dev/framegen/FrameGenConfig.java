package dev.framegen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;

/**
 * User settings. Fields are read from the render thread and the presenter thread, so every field is
 * volatile and every mutation goes through {@link #save()} on the render thread.
 */
public final class FrameGenConfig {
	public enum Mode {
		/** Shows the camera slightly in the past and blends between the two surrounding real frames. Smoothest, adds about one real frame of latency. */
		INTERPOLATE,
		/** Predicts the camera forward from the newest real frame. No added latency, small overshoot when motion changes abruptly. */
		EXTRAPOLATE
	}

	public enum Quality {
		LOW(2, false),
		MEDIUM(4, true),
		HIGH(6, true),
		ULTRA(10, true);

		public final int iterations;
		public final boolean backgroundSearch;

		Quality(int iterations, boolean backgroundSearch) {
			this.iterations = iterations;
			this.backgroundSearch = backgroundSearch;
		}
	}

	/** Master switch. When off, frames are passed through unchanged. */
	public volatile boolean enabled = true;
	public volatile Mode mode = Mode.EXTRAPOLATE;
	public volatile Quality quality = Quality.MEDIUM;
	/**
	 * Real frame rate limit while generating, as a divisor of the output rate: 1 = never render more real frames
	 * than the display shows (default), 2 = half the display rate, ... 0 = use the game's own Max Framerate.
	 */
	public volatile int multiplier = 1;
	/** Lets {@link #load()} migrate settings whose meaning changed. */
	public int configVersion; // 0 when absent from an old file

	/** Output frame rate when V-Sync is off. 0 = match the display refresh rate. */
	public volatile int targetOutputFps = 0;
	/** Keep the HUD and first-person hand crisp (rendered unwarped on top of the generated world). */
	public volatile boolean sharpHud = true;
	/** Maximum prediction, in real-frame intervals, for extrapolation and late frames. */
	public volatile float maxExtrapolation = 1.0f;
	/** Wait for the GPU after every present. Lowers latency and improves pacing on most drivers. */
	public volatile boolean lowLatencyPresent = true;
	/** Show the frame generation statistics line in the HUD. */
	public volatile boolean showStats = false;
	/** Debug view: tint generated pixels by how well the reprojection converged. */
	public volatile boolean debugView = false;

	private static final int CURRENT_VERSION = 3;
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static FrameGenConfig instance;

	public static FrameGenConfig get() {
		if (instance == null) {
			instance = load();
		}
		return instance;
	}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve("framegen.json");
	}

	private static FrameGenConfig load() {
		Path file = path();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file)) {
				FrameGenConfig cfg = GSON.fromJson(reader, FrameGenConfig.class);
				if (cfg != null) {
					if (cfg.configVersion < CURRENT_VERSION) {
						if (cfg.configVersion < 2) {
							// 1.0/1.1 defaulted to capping the game at half the display rate, which made fast machines
							// render far fewer real frames than they could. Reset to the new default.
							cfg.multiplier = 1;
							if (cfg.quality == Quality.HIGH) cfg.quality = Quality.MEDIUM; // old default, now the costlier tier
						}
						if (cfg.configVersion < 3) {
							// Up to 1.3 the default was Interpolate, which adds about one real frame of input delay.
							// Extrapolate (no added delay) is the default now.
							cfg.mode = Mode.EXTRAPOLATE;
						}
						cfg.configVersion = CURRENT_VERSION;
						cfg.sanitize();
						cfg.save();
					}
					cfg.sanitize();
					return cfg;
				}
			} catch (IOException | JsonParseException e) {
				FrameGenMod.LOGGER.warn("Couldn't read {}, using defaults", file, e);
			}
		}
		FrameGenConfig cfg = new FrameGenConfig();
		cfg.configVersion = CURRENT_VERSION;
		cfg.save();
		return cfg;
	}

	private void sanitize() {
		if (this.mode == null) this.mode = Mode.EXTRAPOLATE;
		if (this.quality == null) this.quality = Quality.MEDIUM;
		if (this.multiplier < 0 || this.multiplier > 8) this.multiplier = 1;
		if (this.targetOutputFps < 0 || this.targetOutputFps > 1000) this.targetOutputFps = 0;
		if (!(this.maxExtrapolation >= 0.0f) || this.maxExtrapolation > 3.0f) this.maxExtrapolation = 1.0f;
	}

	public void save() {
		this.sanitize();
		Path file = path();
		try {
			Files.createDirectories(file.getParent());
			Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			try (Writer writer = Files.newBufferedWriter(tmp)) {
				GSON.toJson(this, writer);
			}
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			FrameGenMod.LOGGER.warn("Couldn't save {}", file, e);
		}
	}
}
