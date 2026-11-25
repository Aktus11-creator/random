package dev.framegen.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.framegen.engine.FrameGenEngine;
import net.minecraft.client.Camera;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Camera.class)
abstract class CameraMixin {
	/** Widen the culling frustum by the overscan margin, or the margin would be missing chunks and entities. */
	@ModifyReturnValue(method = "createProjectionMatrixForCulling", at = @At("RETURN"))
	private Matrix4f framegen$overscanCulling(Matrix4f projection) {
		float scale = FrameGenEngine.overscanCullingScale();
		return scale < 1.0f ? projection.scaleLocal(scale, scale, 1.0f) : projection;
	}
}
