package dev.framegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.FramerateLimitTracker;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.api.device.GpuSurface;
import dev.framegen.engine.FrameGenEngine;
import java.util.function.BooleanSupplier;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	/** Install the FrameGen surface (and presenter thread) in place of the window surface. */
	@WrapOperation(
		method = "<init>",
		at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createSurface(JLjava/util/function/BooleanSupplier;)Lcom/mojang/renderpearl/api/device/GpuSurface;")
	)
	private GpuSurface framegen$wrapSurface(GpuDevice device, long windowHandle, BooleanSupplier isIconified, Operation<GpuSurface> original) {
		GpuSurface vanilla = original.call(device, windowHandle, isIconified);
		return FrameGenEngine.wrapSurface(vanilla, windowHandle, isIconified);
	}

	/** While generating, cap the real frame rate to output / multiplier. */
	@ModifyExpressionValue(
		method = "renderFrame",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/platform/FramerateLimitTracker;getFramerateLimit()I")
	)
	private int framegen$limitRealFrames(int vanillaLimit) {
		return FrameGenEngine.adjustFramerateLimit(vanillaLimit);
	}

	@Inject(method = "renderFrame", at = @At("HEAD"))
	private void framegen$pollDisplay(boolean advanceGameTime, CallbackInfo ci) {
		FrameGenEngine.pollDisplay(false);
	}
}
