package dev.framegen.engine;

/**
 * Extra data FrameGen attaches to every {@code EntityRenderState} during extraction (see
 * {@code EntityRendererMixin}): which entity it is, and how fast it moves, so generated frames can move its pixels.
 */
public interface EntityMotionState {
	int framegen$entityId();

	/** Velocity in blocks per second, as the game is currently showing it (zero while ticking is frozen). */
	float framegen$vx();

	float framegen$vy();

	float framegen$vz();

	void framegen$setMotion(int entityId, float vx, float vy, float vz);
}
