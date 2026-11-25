package dev.framegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(SkyRenderer.class)
abstract class SkyRendererMixin {
	/**
	 * The sky renderer keeps the main target it was created with instead of asking for the current one. While
	 * overscan swaps a larger target in, the sky must go there like everything else in the level.
	 */
	@ModifyExpressionValue(method = "render", at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/SkyRenderer;renderTarget:Lcom/mojang/blaze3d/pipeline/RenderTarget;"))
	private RenderTarget framegen$currentMainTarget(RenderTarget stored) {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
	}
}
