package dev.framegen.mixin;

import com.mojang.blaze3d.framegraph.FrameGraphBuilder;
import dev.framegen.engine.FrameGenEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FrameGraphBuilder.class)
abstract class FrameGraphBuilderMixin {
	/** Responsive camera: look at the mouse between the level's render passes too, so samples are spread over the frame. */
	@Inject(method = "execute(Lcom/mojang/blaze3d/resource/GraphicsResourceAllocator;Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;)V",
		at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/framegraph/FrameGraphBuilder$Inspector;afterExecutePass(Ljava/lang/String;)V", shift = At.Shift.AFTER))
	private void framegen$sampleInput(CallbackInfo ci) {
		FrameGenEngine.sampleInput();
	}
}
