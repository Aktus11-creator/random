package dev.framegen.mixin;

import dev.framegen.engine.EntityMotionState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(EntityRenderState.class)
abstract class EntityRenderStateMixin implements EntityMotionState {
	@Unique
	private int framegen$id = -1;
	@Unique
	private float framegen$vx;
	@Unique
	private float framegen$vy;
	@Unique
	private float framegen$vz;

	@Override
	public int framegen$entityId() {
		return this.framegen$id;
	}

	@Override
	public float framegen$vx() {
		return this.framegen$vx;
	}

	@Override
	public float framegen$vy() {
		return this.framegen$vy;
	}

	@Override
	public float framegen$vz() {
		return this.framegen$vz;
	}

	@Override
	public void framegen$setMotion(int entityId, float vx, float vy, float vz) {
		this.framegen$id = entityId;
		this.framegen$vx = vx;
		this.framegen$vy = vy;
		this.framegen$vz = vz;
	}
}
