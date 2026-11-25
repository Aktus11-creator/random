package dev.framegen.mixin;

import dev.framegen.engine.EntityMotionState;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
abstract class EntityRendererMixin {
	/**
	 * Tag each render state with its entity and velocity. The rendered position is lerp(old, current, partialTicks),
	 * so between two ticks it moves at (current - old) per tick: exactly the motion the player sees.
	 */
	@Inject(method = "extractRenderState", at = @At("TAIL"))
	private void framegen$tagMotion(Entity entity, EntityRenderState state, float partialTicks, CallbackInfo ci) {
		TickRateManager ticks = entity.level().tickRateManager();
		float rate = ticks.isEntityFrozen(entity) ? 0.0f : ticks.tickrate();
		((EntityMotionState) state).framegen$setMotion(entity.getId(),
			(float) ((entity.getX() - entity.xOld) * rate),
			(float) ((entity.getY() - entity.yOld) * rate),
			(float) ((entity.getZ() - entity.zOld) * rate));
	}
}
