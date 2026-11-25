package dev.framegen.engine;

import org.joml.AxisAngle4d;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;

/**
 * A camera pose: projections, view rotation and world position. Double precision so far-from-origin worlds stay exact.
 *
 * <p>There are two projections because of overscan: {@link #projection} is what the frame was rendered with (wider
 * than the screen), {@link #display} is what the player sees. Source frames are read with the first, generated frames
 * are produced with the second.
 */
final class Pose {
	final Matrix4d projection = new Matrix4d();
	final Matrix4d display = new Matrix4d();
	final Quaterniond rotation = new Quaterniond();
	double x;
	double y;
	double z;

	private final Matrix4d tmpA = new Matrix4d();
	private final Matrix4d tmpB = new Matrix4d();
	private final Quaterniond tmpQ = new Quaterniond();
	private final AxisAngle4d tmpAA = new AxisAngle4d();

	Pose set(FrameRecord record) {
		this.projection.set(record.projection);
		this.display.set(this.projection).scaleLocal(1.0 / record.overscanX, 1.0 / record.overscanY, 1.0);
		new Matrix4d(record.viewRotation).getNormalizedRotation(this.rotation);
		this.x = record.camX;
		this.y = record.camY;
		this.z = record.camZ;
		return this;
	}

	/** Sets the rotation the way Minecraft's camera builds it from yaw and pitch (degrees). */
	Pose setYawPitch(float yaw, float pitch) {
		this.rotation.rotationYXZ(Math.PI - Math.toRadians(yaw), -Math.toRadians(pitch), 0.0).conjugate();
		return this;
	}

	/** Produce output framed like the source image itself, margin included (self-test). */
	Pose showAsRendered() {
		this.display.set(this.projection);
		return this;
	}

	Pose set(Pose other) {
		this.projection.set(other.projection);
		this.display.set(other.display);
		this.rotation.set(other.rotation);
		this.x = other.x;
		this.y = other.y;
		this.z = other.z;
		return this;
	}

	/**
	 * Interpolates (0 <= t <= 1) or extrapolates (t > 1) between two poses. Rotation uses the constant angular
	 * velocity curve through both orientations, so extrapolation continues a turn instead of overshooting linearly.
	 * The projection is never extrapolated (FOV and bobbing changes are not predictable).
	 */
	Pose blend(Pose a, Pose b, double t) {
		double tp = Math.max(0.0, Math.min(1.0, t));
		a.projection.lerp(b.projection, tp, this.projection);
		a.display.lerp(b.display, tp, this.display);
		this.x = a.x + (b.x - a.x) * t;
		this.y = a.y + (b.y - a.y) * t;
		this.z = a.z + (b.z - a.z) * t;

		// relative = a^-1 * b, scaled to t along its rotation axis.
		Quaterniond rel = a.rotation.conjugate(this.tmpQ).mul(b.rotation).normalize();
		if (rel.w < 0.0) {
			rel.set(-rel.x, -rel.y, -rel.z, -rel.w);
		}
		this.tmpAA.set(rel);
		if (Math.abs(this.tmpAA.angle) < 1e-9) {
			this.rotation.set(a.rotation);
		} else {
			this.tmpAA.angle *= t;
			Quaterniond scaled = new Quaterniond().set(this.tmpAA);
			a.rotation.mul(scaled, this.rotation).normalize();
		}
		return this;
	}

	/** Angle in radians between the two view orientations. */
	static double angleBetween(Pose a, Pose b) {
		double dot = Math.abs(a.rotation.dot(b.rotation));
		return 2.0 * Math.acos(Math.min(1.0, dot));
	}

	static double distance(Pose a, Pose b) {
		double dx = a.x - b.x;
		double dy = a.y - b.y;
		double dz = a.z - b.z;
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	/**
	 * The two halves of the reprojection, for points that move on their own: {@code srcInv} maps source NDC (+ depth)
	 * to a homogeneous point relative to the source camera (world axes), {@code tgt} maps such a point to this pose's
	 * clip space. {@code tgt * srcInv} is the {@code fwd} matrix of {@link #reprojection}.
	 */
	void motionMatrices(Pose source, Matrix4f srcInv, Matrix4f tgt) {
		srcInv.set(this.tmpA.set(source.projection).rotate(source.rotation).invert());
		tgt.set(this.tmpB.set(this.display).rotate(this.rotation).translate(source.x - this.x, source.y - this.y, source.z - this.z));
	}

	/**
	 * Builds the matrices mapping a source frame rendered from {@code source} to clip space of {@code target}.
	 * Results: fwd (with translation), fwdRot (rotation only, for points at infinity) and their inverses.
	 */
	void reprojection(Pose source, Matrix4f fwd, Matrix4f fwdRot, Matrix4f inv, Matrix4f invRot) {
		// inverse(P_s * R_s): source NDC -> camera-relative world direction/point (homogeneous).
		Matrix4d sourceInv = this.tmpA.set(source.projection).rotate(source.rotation).invert();
		// P_t * R_t, framed like the screen
		Matrix4d targetVp = this.tmpB.set(this.display).rotate(this.rotation);

		Matrix4d m = new Matrix4d(targetVp).mul(sourceInv);
		fwdRot.set(m);
		invRot.set(m.invert());

		// Points are relative to the camera; moving from the source camera to the target camera shifts them by
		// (source - target). Applied to homogeneous points, so w = 0 (infinitely far) points stay put.
		Matrix4d withTranslation = new Matrix4d(targetVp).translate(source.x - this.x, source.y - this.y, source.z - this.z).mul(sourceInv);
		fwd.set(withTranslation);
		inv.set(withTranslation.invert());
	}
}
