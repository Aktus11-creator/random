package dev.framegen.engine;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import dev.framegen.FrameGenMod;
import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Saves FrameGen's intermediate layers of one frame as PNGs (alpha preserved) plus a text file of the frame's state,
 * to {@code <game dir>/framegen-debug/}. For diagnosing setups we can't reproduce.
 */
final class DebugSnapshot {
	private final Path dir;
	private final String prefix;
	private final StringBuilder info = new StringBuilder();
	private int index;

	DebugSnapshot() {
		this.dir = Minecraft.getInstance().gameDirectory.toPath().resolve("framegen-debug");
		this.prefix = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
	}

	Path dir() {
		return this.dir;
	}

	void note(String line) {
		this.info.append(line).append('\n');
	}

	/** Queues a GPU readback of the texture's current contents (in command order) and saves it as PNG. */
	void capture(@Nullable GpuTexture texture, String name) {
		int n = ++this.index;
		if (texture == null || texture.isClosed()) {
			this.note(String.format("%02d %s: <none>", n, name));
			return;
		}
		int w = texture.getWidth(0);
		int h = texture.getHeight(0);
		boolean depth = texture.getFormat().hasDepthAspect();
		int block = texture.getFormat().blockSize();
		this.note(String.format("%02d %s: %dx%d %s", n, name, w, h, texture.getFormat()));
		String file = String.format("%s_%02d_%s.png", this.prefix, n, name);
		GpuBuffer buffer = RenderSystem.getDevice().createBuffer(() -> "FrameGen debug readback", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * block);
		RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(texture, buffer, 0L, () -> {
			NativeImage image = new NativeImage(w, h, false);
			try (GpuBufferSlice.MappedView read = buffer.map(true, false)) {
				var data = read.data().order(ByteOrder.nativeOrder());
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						int offset = (x + y * w) * block;
						int abgr;
						if (depth) {
							// Reversed-Z: 0 = nothing drawn (black); nearer = brighter. Hand depth shows up white.
							float d = data.getFloat(offset);
							int v = d <= 0.0f ? 0 : Math.max(40, Math.min(255, (int) (Math.sqrt(d) * 255.0f * 4.0f)));
							abgr = 0xFF000000 | (v << 16) | (v << 8) | v;
						} else {
							abgr = data.getInt(offset); // RGBA8 bytes -> ABGR int on little endian, alpha kept
						}
						image.setPixelABGR(x, h - y - 1, abgr);
					}
				}
			} finally {
				buffer.close();
			}
			Util.ioPool().execute(() -> {
				try {
					Files.createDirectories(this.dir);
					image.writeToFile(this.dir.resolve(file));
				} catch (IOException e) {
					FrameGenMod.LOGGER.warn("Couldn't write FrameGen debug image {}", file, e);
				} finally {
					image.close();
				}
			});
		}, 0);
	}

	void finish() {
		String text = this.info.toString();
		Util.ioPool().execute(() -> {
			try {
				Files.createDirectories(this.dir);
				Files.writeString(this.dir.resolve(this.prefix + "_00_info.txt"), text, StandardCharsets.UTF_8);
			} catch (IOException e) {
				FrameGenMod.LOGGER.warn("Couldn't write FrameGen debug info", e);
			}
		});
		FrameGenMod.LOGGER.info("FrameGen debug snapshot saved to {} ({} images)", this.dir, this.index);
	}
}
