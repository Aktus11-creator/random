package dev.framegen.mixin;

import dev.framegen.engine.FrameCapture;
import dev.framegen.engine.FrameGenEngine;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class LevelRendererMixin {
	/** Depth of what is seen through water and stained glass, see {@link FrameCapture#beforeTranslucent}. */
	@Inject(method = "executeClassicTransparency", at = @At("HEAD"))
	private void framegen$beforeTranslucent(CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.beforeTranslucent();
	}
}
