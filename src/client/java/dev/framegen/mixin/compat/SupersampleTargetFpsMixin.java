package dev.framegen.mixin.compat;

import com.mojang.blaze3d.platform.Window;
import dev.framegen.engine.FrameGenEngine;
import net.minecraft.client.Options;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Supersample's dynamic mode raises the render resolution until the game reaches its target frame rate (the
 * display's refresh rate). With FrameGen on that leaves nothing to generate: the camera would stay at the real frame
 * rate. While FrameGen is generating, aim Supersample at half the output rate instead (FrameGen doubles it back), so
 * both work: supersampled image quality and a camera at the full refresh rate.
 *
 * {@code @Pseudo}: does nothing if Supersample isn't installed.
 */
@Pseudo
@Mixin(targets = "com.novotnya.supersample.render.SupersampleRenderer", remap = false)
abstract class SupersampleTargetFpsMixin {
	@Inject(method = "targetFps(Lcom/mojang/blaze3d/platform/Window;Lnet/minecraft/client/Options;)D", at = @At("RETURN"), cancellable = true, remap = false, require = 0)
	private void framegen$leaveRoomForGeneratedFrames(Window window, Options options, CallbackInfoReturnable<Double> cir) {
		double adjusted = FrameGenEngine.adjustResolutionScalerTarget(cir.getReturnValueD());
		if (adjusted != cir.getReturnValueD()) {
			cir.setReturnValue(adjusted);
		}
	}
}
