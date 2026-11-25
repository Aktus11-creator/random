package dev.fakess.mixin;

import dev.fakess.FakeSS;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "extractWindow", at = @At("TAIL"))
	private void fakess$scale(CallbackInfo ci) {
		FakeSS.afterExtractWindow(((GameRenderer) (Object) this).gameRenderState().windowRenderState);
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/fog/FogRenderer;endFrame()V", shift = At.Shift.AFTER))
	private void fakess$beforeGui(CallbackInfo ci) {
		FakeSS.beforeGui((GameRenderer) (Object) this);
	}

	@Inject(method = "render", at = @At("TAIL"))
	private void fakess$end(CallbackInfo ci) {
		FakeSS.onRenderEnd((GameRenderer) (Object) this);
	}
}
