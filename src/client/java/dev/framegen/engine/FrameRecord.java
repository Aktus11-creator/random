package dev.framegen.engine;

import java.util.ArrayList;
import java.util.List;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

/**
 * Everything the presenter needs to know about one frame the game rendered. Filled in on the render thread,
 * immutable once handed to {@link FrameExchange#publish}.
 */
public final class FrameRecord {
	public final long id;
	/** {@link System#nanoTime()} at which the game sampled this frame's camera (start of extraction). */
	public long simNanos;
	public int width;
	public int height;

	/** True when the overlay slot holds the complete final image and there is no separable world layer (menus, loading, FG off). */
	public boolean opaque;
	public @Nullable GpuSlot world;
	public @Nullable GpuSlot overlay;

	/** Projection actually used for the level, including view bobbing, nausea distortion and the overscan margin. */
	public final Matrix4f projection = new Matrix4f();
	/**
	 * Overscan: the screen shows only the centre of the world layer, {@code [-overscanX, overscanX] x [-overscanY,
	 * overscanY]} in its NDC; the projection the player sees is {@code diag(1/overscanX, 1/overscanY, 1, 1) * projection}.
	 * 1 = no margin.
	 */
	public float overscanX = 1.0f;
	public float overscanY = 1.0f;
	/** Camera rotation (model-view without translation); level geometry is camera-relative. */
	public final Matrix4f viewRotation = new Matrix4f();
	public double camX;
	public double camY;
	public double camZ;
	/**
	 * Moving objects (entities) in this frame, nearest first. For each: its id, rendered position (absolute world),
	 * a box around it that turns with its body, relative to this frame's camera ({@link #BOX_FLOATS} floats: centre x,
	 * centre z, min y, max y, facing x, facing z, half width, half length), its velocity in blocks per second and its
	 * body yaw in degrees (NaN if it has none). Generated frames move the pixels inside the box with the entity.
	 */
	public int entityCount;
	public int[] entityIds = EMPTY_INT;
	public double[] entityPos = EMPTY_DOUBLE;
	public float[] entityBox = EMPTY_FLOAT;
	public float[] entityVel = EMPTY_FLOAT;
	public float[] entityYaw = EMPTY_FLOAT;
	public static final int MAX_ENTITIES = 64;
	public static final int BOX_FLOATS = 8;
	private static final int[] EMPTY_INT = new int[0];
	private static final double[] EMPTY_DOUBLE = new double[0];
	private static final float[] EMPTY_FLOAT = new float[0];

	void allocateEntities(int count) {
		this.entityCount = count;
		this.entityIds = new int[count];
		this.entityPos = new double[count * 3];
		this.entityBox = new float[count * BOX_FLOATS];
		this.entityVel = new float[count * 3];
		this.entityYaw = new float[count];
	}

	/** Index of the entity with this id, or -1. */
	int findEntity(int id) {
		for (int i = 0; i < this.entityCount; i++) {
			if (this.entityIds[i] == id) return i;
		}
		return -1;
	}

	/** Camera discontinuity before this frame (teleport, respawn, dimension change...). Never interpolate across. */
	public boolean cut;
	/** Depth buffer convention: true = glClipControl ZERO_TO_ONE (window depth == NDC z), false = default [-1, 1] mapping. */
	public boolean zZeroToOne;

	/** HUD vignette, applied to the world layer only (it multiplies, so it cannot live in the premultiplied overlay). */
	public boolean vignette;
	public float vignetteR;
	public float vignetteG;
	public float vignetteB;
	public int vignetteTexture;

	/** Invert-blended HUD elements (crosshair) that must be applied on top of the final, warped image. */
	public final List<InvertQuad> invertQuads = new ArrayList<>(4);

	/** Debug only: distance from the camera to the block under the crosshair, from Minecraft's raycast (NaN if none). */
	public double debugHitDistance = Double.NaN;

	/** GLsync created right after the frame's GPU work was submitted. Owned by whoever currently holds the record. */
	long readyFence;
	/** Presenter: when the fence was first observed signalled. */
	long readyNanos;

	FrameRecord(long id) {
		this.id = id;
	}

	public boolean hasWorld() {
		return !this.opaque && this.world != null;
	}

	/**
	 * A screen-space quad drawn with Minecraft's INVERT blend function ({@code src*(1-dst) + dst*(1-src)}).
	 * Coordinates are framebuffer pixels with the origin in the top left corner.
	 */
	public record InvertQuad(float x0, float y0, float x1, float y1, float u0, float v0, float u1, float v1, int texture, float r, float g, float b, float a) {
	}
}
