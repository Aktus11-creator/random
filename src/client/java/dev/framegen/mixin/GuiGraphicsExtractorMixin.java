package dev.framegen.mixin;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.framegen.engine.FrameCapture;
import dev.framegen.engine.FrameGenEngine;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.joml.Matrix3x2fStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD vignette (multiplicative) and crosshair (inverting) blend with the pixels under them, so they can't be
 * captured in a premultiplied overlay. FrameGen takes them over and applies them to the final image itself.
 */
@Mixin(GuiGraphicsExtractor.class)
abstract class GuiGraphicsExtractorMixin {
	@Shadow
	@Final
	private Matrix3x2fStack pose;

	@Inject(
		method = "innerBlit(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;IIIIFFFFI)V",
		at = @At("HEAD"),
		cancellable = true
	)
	private void framegen$interceptBlendingBlits(RenderPipeline pipeline, GpuTextureView textureView, GpuSampler sampler, int x0, int y0, int x1, int y1,
		float u0, float u1, float v0, float v1, int color, CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null && capture.interceptGuiBlit(pipeline, textureView, this.pose, x0, y0, x1, y1, u0, u1, v0, v1, color)) {
			ci.cancel();
		}
	}
}
