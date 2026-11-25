package dev.fakess;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.fakess.mixin.GameRendererAccessor;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.WindowRenderState;

/** Test stand-in for a supersampling mod: renders at 2x, downsamples into a native target before the GUI. */
public final class FakeSS {
	public static final int SCALE = 2;
	private static TextureTarget output;
	private static RenderTarget hiRes;
	private static int nativeW;
	private static int nativeH;
	private static boolean logged;

	public static void afterExtractWindow(WindowRenderState state) {
		nativeW = state.width;
		nativeH = state.height;
		state.width *= SCALE;
		state.height *= SCALE;
	}

	public static void beforeGui(GameRenderer renderer) {
		RenderTarget main = renderer.mainRenderTarget();
		if (output == null) {
			output = new TextureTarget("FakeSS output", nativeW, nativeH, GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
		} else if (output.width != nativeW || output.height != nativeH) {
			output.resize(nativeW, nativeH);
		}
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder()
				.createRenderPass(() -> "FakeSS downsample", output.getColorTextureView(), Optional.empty())) {
			RenderSystem.bindDefaultUniforms(pass);
			pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
			pass.setUniform("InSampler", main.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.draw(3, 1, 0, 0);
		}
		RenderSystem.getDevice().createCommandEncoder().clearDepthTexture(output.getDepthTexture(), 0.0);
		hiRes = main;
		((GameRendererAccessor) renderer).fakess$setMainRenderTarget(output);
		WindowRenderState state = renderer.gameRenderState().windowRenderState;
		state.width = nativeW;
		state.height = nativeH;
		if (!logged) {
			logged = true;
			System.out.println("[FakeSS] rendering world at " + main.width + "x" + main.height + ", presenting " + nativeW + "x" + nativeH);
		}
	}

	public static void onRenderEnd(GameRenderer renderer) {
		if (hiRes != null) {
			((GameRendererAccessor) renderer).fakess$setMainRenderTarget(hiRes);
			hiRes = null;
		}
	}

	public static RenderTarget displayTarget(RenderTarget original) {
		return output != null ? output : original;
	}
}
