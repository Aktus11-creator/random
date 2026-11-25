package dev.framegen.engine;

import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import dev.framegen.FrameGenConfig;
import dev.framegen.FrameGenMod;
import dev.framegen.mixin.LevelRendererAccessor;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.util.ARGB;
import org.joml.Matrix3x2f;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryStack;

/**
 * Render-thread half of the engine. Hooks into {@code GameRenderer} to split each real frame into layers:
 *
 * <pre>
 *   A  after the level, before the hand:  world color + depth  -> world slot;  clear color to transparent
 *   (hand and screen effects render onto the transparent target)
 *   B  after the hand:                    hand layer -> handTexture;  put the world back underneath
 *   B2 after post effects / outlines:     if those touched the image, bake it into the world layer
 *   C  before the GUI:                    clear color to transparent
 *   (GUI renders; vignette and crosshair are intercepted because they need the world under them)
 *   D  at the swapchain blit:             compose hand + GUI into the overlay slot, rebuild the exact vanilla
 *                                         image in the main target (screenshots and other mods see no difference)
 * </pre>
 *
 * Frames that can't be split (menus open, no world, frame generation off) are captured as one opaque image.
 */
public final class FrameCapture {
	private static final Vector4f TRANSPARENT = new Vector4f(0.0f, 0.0f, 0.0f, 0.0f);
	private static final int QUAD_STRIDE = 256;
	private static final int MAX_QUADS = 8;

	private final FrameExchange exchange;
	private final boolean zZeroToOne;

	// Per-frame state ---------------------------------------------------------------------------------------------
	private @Nullable FrameRecord record;
	private boolean splitPlanned;
	private boolean worldCaptured;
	private boolean handSplit;
	private boolean guiSplit;
	private boolean vignetteIntercepted;
	private float vignetteR;
	private float vignetteG;
	private float vignetteB;
	private @Nullable GpuTextureView vignetteView;
	private final List<PendingQuad> pendingQuads = new ArrayList<>(MAX_QUADS);
	private @Nullable GpuSlot worldSlot;
	private final Matrix4f levelProjection = new Matrix4f();
	/**
	 * Another mod (e.g. a supersampling mod) presents a different image than Minecraft's main target. The layer
	 * splitting then can't work, so frames are passed through unchanged until it stops for a while.
	 */
	private boolean redirectActive;
	/** The main target the level was rendered into this frame (hook A). */
	private @Nullable RenderTarget levelTarget;
	/** True if that target is larger than the window (supersampling): layers are downscaled to native size. */
	private boolean scaled;
	/** The texture the GUI was drawn into (hook C). */
	private @Nullable GpuTexture guiTarget;
	/** A resolution-scaling mod's own native-size image of world + hand, taken just before the GUI (hook C). */
	private boolean baseCaptured;
	private @Nullable GpuTextureView hiresWorld;
	private @Nullable GpuTextureView baseImage;
	private @Nullable GpuTextureView guiCopy;
	private @Nullable GpuTextureView handRaw;
	private int framesWithoutRedirect;
	private boolean redirectLogged;
	private boolean levelProjectionSet;

	// Persistent resources --------------------------------------------------------------------------------------
	private @Nullable GpuTexture handTexture;
	private @Nullable GpuTextureView handView;
	private @Nullable GpuTexture blankTexture;
	private @Nullable GpuTextureView blankView;
	private @Nullable GpuBuffer compositeUbo;
	private @Nullable GpuBuffer quadUbo;

	// Continuity tracking for camera cuts -------------------------------------------------------------------------
	private double lastCamX = Double.NaN;
	private double lastCamY;
	private double lastCamZ;
	private @Nullable Object lastLevel;
	private @Nullable Object lastCameraEntity;
	private long lastWorldPublishNanos;

	FrameCapture(FrameExchange exchange, boolean zZeroToOne) {
		this.exchange = exchange;
		this.zZeroToOne = zZeroToOne;
	}

	private record PendingQuad(Matrix3x2f pose, int x0, int y0, int x1, int y1, float u0, float u1, float v0, float v1, int color, GpuTextureView texture) {
	}

	// ==== Hooks ===================================================================================================

	/** Start of {@code GameRenderer.extract}: the camera for this frame is sampled right after. */
	public void beginExtract() {
		this.resetFrame();
		this.record = this.exchange.newRecord();
		this.record.simNanos = System.nanoTime();
		Minecraft mc = Minecraft.getInstance();
		FrameGenConfig config = FrameGenConfig.get();
		this.splitPlanned = config.enabled && FrameGenEngine.isPresenterRunning() && mc.level != null && mc.gui.screen() == null && mc.gui.overlay() == null
			&& !this.redirectActive;
	}

	/** True while another mod substitutes the presented image (frame generation is paused meanwhile). */
	public boolean isRedirected() {
		return this.redirectActive;
	}

	public boolean interceptsHudBlits() {
		return this.splitPlanned && FrameGenConfig.get().sharpHud;
	}

	/** {@code GuiGraphicsExtractor.innerBlit}: returns true if the blit was taken over by FrameGen. */
	public boolean interceptGuiBlit(RenderPipeline pipeline, GpuTextureView texture, Matrix3x2f pose, int x0, int y0, int x1, int y1,
		float u0, float u1, float v0, float v1, int color) {
		if (!this.interceptsHudBlits()) return false;
		if (pipeline == RenderPipelines.VIGNETTE) {
			this.vignetteIntercepted = true;
			this.vignetteR = ARGB.redFloat(color);
			this.vignetteG = ARGB.greenFloat(color);
			this.vignetteB = ARGB.blueFloat(color);
			this.vignetteView = texture;
			return true;
		}
		if (pipeline == RenderPipelines.CROSSHAIR && this.pendingQuads.size() < MAX_QUADS) {
			this.pendingQuads.add(new PendingQuad(new Matrix3x2f(pose), x0, y0, x1, y1, u0, u1, v0, v1, color, texture));
			return true;
		}
		return false;
	}

	/** The projection matrix the level is rendered with (after bobbing / nausea). */
	public void onLevelProjection(Matrix4f projection) {
		this.levelProjection.set(projection);
		this.levelProjectionSet = true;
	}

	/**
	 * Hook A. Captures the world layer at the window's native resolution. Normally the main target already is that
	 * size and this is a plain copy. If a resolution-scaling mod (e.g. a supersampler) made the main target larger,
	 * the world is box-filtered down instead, so the presenter works at native resolution and memory use stays flat.
	 */
	public void beforeHand(CameraRenderState camera, boolean consistentDepthRequired) {
		if (!this.splitPlanned || this.record == null || !this.levelProjectionSet || camera.isPanoramicMode) return;
		Minecraft mc = Minecraft.getInstance();
		RenderTarget main = mc.gameRenderer.mainRenderTarget();
		GpuTexture color = main.getColorTexture();
		GpuTexture depth = main.getDepthTexture();
		if (color == null || depth == null || main.getColorTextureView() == null || main.getDepthTextureView() == null) return;

		int nw = Math.max(1, mc.getWindow().getWidth());
		int nh = Math.max(1, mc.getWindow().getHeight());
		GpuSlot slot = this.exchange.acquire(GpuSlot.Kind.WORLD, nw, nh, 30L);
		if (slot == null) return;
		this.worldSlot = slot;
		this.levelTarget = main;
		this.scaled = main.width != nw || main.height != nh;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (!this.scaled) {
			encoder.copyTextureToTexture(color, slot.color, 0, 0, 0, 0, 0, nw, nh);
			encoder.copyTextureToTexture(depth, slot.depth, 0, 0, 0, 0, 0, nw, nh);
		} else {
			this.hiresWorld = this.ensureTemp(this.hiresWorld, "FrameGen hi-res world", main.width, main.height);
			encoder.copyTextureToTexture(color, this.hiresWorld.texture(), 0, 0, 0, 0, 0, main.width, main.height);
			this.downsample(encoder, main.getColorTextureView(), slot.colorView);
			this.downsampleDepth(encoder, main.getDepthTextureView(), slot.depthView);
		}
		this.worldCaptured = true;

		FrameRecord r = this.record;
		r.projection.set(this.levelProjection);
		r.viewRotation.set(camera.viewRotationMatrix);
		r.camX = camera.pos.x;
		r.camY = camera.pos.y;
		r.camZ = camera.pos.z;
		if (Presenter.DEBUG_LOG) {
			var hit = mc.hitResult;
			r.debugHitDistance = hit != null && hit.getType() != net.minecraft.world.phys.HitResult.Type.MISS ? hit.getLocation().distanceTo(camera.pos) : Double.NaN;
		}

		// With post effects active the hand is integrated into the processed image; keep it in the world layer.
		this.handSplit = FrameGenConfig.get().sharpHud && !consistentDepthRequired;
		if (this.handSplit) {
			encoder.clearColorTexture(color, TRANSPARENT);
		}
	}

	/** Hook B: end of {@code GameRenderer.renderLevel}. */
	public void afterLevel() {
		if (!this.handSplit || this.worldSlot == null || this.levelTarget == null) return;
		RenderTarget main = this.levelTarget;
		GpuTextureView mainColor = main.getColorTextureView();
		GpuTextureView mainDepth = main.getDepthTextureView();
		if (mainColor == null || mainDepth == null) return;
		this.ensureHandTexture(this.worldSlot.width, this.worldSlot.height);
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		// Hand layer for the presenter (native size, coverage from alpha or depth, see fg_hand_capture).
		this.fullscreenPass(encoder, "FrameGen capture hand", this.handView, FrameGenPipelines.HAND_CAPTURE, pass -> {
			pass.setUniform("InSampler", mainColor, nearest());
			pass.setUniform("DepthSampler", mainDepth, nearest());
		});
		// Put the world back underneath the hand so everything after this point sees the vanilla image.
		this.handRaw = this.ensureTemp(this.handRaw, "FrameGen hand (full size)", main.width, main.height);
		encoder.copyTextureToTexture(main.getColorTexture(), this.handRaw.texture(), 0, 0, 0, 0, 0, main.width, main.height);
		GpuTextureView world = this.scaled ? this.hiresWorld : this.worldSlot.colorView;
		GpuTextureView hand = this.handRaw;
		this.fullscreenPass(encoder, "FrameGen restore world", mainColor, FrameGenPipelines.HAND_RESTORE, pass -> {
			pass.setUniform("HandSampler", hand, nearest());
			pass.setUniform("WorldSampler", world, nearest());
			pass.setUniform("DepthSampler", mainDepth, nearest());
		});
	}

	/** Hook B2: after entity outlines and post effects were applied to the main target. */
	public void afterPostEffects(boolean postEffectsApplied) {
		if (!this.worldCaptured || this.worldSlot == null || this.levelTarget == null) return;
		LevelRendererAccessor levelRenderer = (LevelRendererAccessor) Minecraft.getInstance().levelRenderer;
		if (postEffectsApplied || levelRenderer.framegen$rendersEntityOutline()) {
			// Something drew over the hand: the separated layers no longer add up. Bake everything into the world.
			RenderTarget main = this.levelTarget;
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			if (!this.scaled) {
				encoder.copyTextureToTexture(main.getColorTexture(), this.worldSlot.color, 0, 0, 0, 0, 0, main.width, main.height);
			} else {
				this.downsample(encoder, main.getColorTextureView(), this.worldSlot.colorView);
			}
			this.handSplit = false;
		}
	}

	/**
	 * Hook C: right before the GUI renders. A resolution-scaling mod may have swapped in a different (native size)
	 * main target by now, holding its own high-quality downscale of world + hand: keep that as the base image for the
	 * real frame, then clear the target so the GUI renders onto transparent.
	 */
	public void beforeGui() {
		if (!this.worldCaptured || !FrameGenConfig.get().sharpHud) return;
		RenderTarget current = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		if (current.getColorTexture() == null) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		if (current != this.levelTarget) {
			this.baseImage = this.ensureTemp(this.baseImage, "FrameGen base image", current.width, current.height);
			encoder.copyTextureToTexture(current.getColorTexture(), this.baseImage.texture(), 0, 0, 0, 0, 0, current.width, current.height);
			this.baseCaptured = true;
		}
		encoder.clearColorTexture(current.getColorTexture(), TRANSPARENT);
		this.guiTarget = current.getColorTexture();
		this.guiSplit = true;
	}

	/**
	 * Hook D (swapchain blit): build the overlay and record, restore the vanilla image in the presented texture.
	 * Returns false if nothing could be captured (the frame is then simply not published).
	 */
	public boolean finishFrame(GpuTextureView mainColor) {
		FrameRecord r = this.record;
		if (r == null) {
			r = this.record = this.exchange.newRecord();
			r.simNanos = System.nanoTime();
		}
		int w = mainColor.getWidth(0);
		int h = mainColor.getHeight(0);
		r.width = w;
		r.height = h;
		r.zZeroToOne = this.zZeroToOne;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();

		GpuSlot overlay = this.exchange.acquire(GpuSlot.Kind.OVERLAY, w, h, 30L);
		if (overlay == null) {
			this.exchange.abandon(this.worldSlot);
			this.worldSlot = null;
			return false;
		}

		// The texture handed to the swapchain blit is, by contract, the frame to show: usually the main target, or a
		// resolution-scaling mod's native-size output. If the GUI was split onto a texture that is *not* the one being
		// presented, some other mod redirected the frame in a way we can't follow: present its image unchanged and
		// stop splitting layers until it goes away.
		GpuTexture presented = mainColor.texture();
		boolean lost = this.guiSplit && presented != this.guiTarget;
		if (lost) {
			this.redirectActive = true;
			this.framesWithoutRedirect = 0;
			if (!this.redirectLogged) {
				this.redirectLogged = true;
				FrameGenMod.LOGGER.warn("FrameGen: another mod presents a different image ({}x{}) than the one the GUI was drawn into. "
					+ "Frames are shown unchanged and frame generation is paused while it does.", w, h);
			}
		} else if (this.redirectActive && ++this.framesWithoutRedirect > 120) {
			this.redirectActive = false;
		}
		boolean layered = this.worldCaptured && this.worldSlot != null && !lost && this.worldSlot.width == w && this.worldSlot.height == h;
		if (this.guiSplit && !lost && !layered) {
			// GUI was split but the layers can't be used this frame (size changed mid-frame): still rebuild the image.
			GpuTextureView vignette = this.vignetteIntercepted && this.vignetteView != null ? this.vignetteView : this.blankView();
			this.rebuildPresented(encoder, mainColor, vignette, null, w, h);
		}
		if (layered) {
			GpuTextureView vignette = this.vignetteIntercepted && this.vignetteView != null ? this.vignetteView : this.blankView();
			if (this.guiSplit) {
				GpuTextureView hand = this.handSplit && this.handView != null ? this.handView : this.blankView();
				this.writeCompositeUbo(encoder, 0, this.handSplit);
				this.fullscreenPass(encoder, "FrameGen compose overlay", overlay.colorView, FrameGenPipelines.COMPOSE_OVERLAY, pass -> {
					pass.setUniform("GuiSampler", mainColor, nearest());
					pass.setUniform("HandSampler", hand, nearest());
					pass.setUniform("VignetteSampler", vignette, linear());
					pass.setUniform("FrameGenComposite", this.compositeUbo.slice(0, 32));
				});
				this.rebuildPresented(encoder, mainColor, vignette, overlay.colorView, w, h);
			} else {
				// HUD separation disabled: the whole frame minus the world is not separable, so the overlay is empty
				// and the world layer is the full image (HUD gets warped with the world).
				encoder.clearColorTexture(overlay.color, TRANSPARENT);
				encoder.copyTextureToTexture(presented, this.worldSlot.color, 0, 0, 0, 0, 0, w, h);
			}
			r.opaque = false;
			r.world = this.worldSlot;
			r.vignette = this.vignetteIntercepted;
			r.vignetteR = this.vignetteR;
			r.vignetteG = this.vignetteG;
			r.vignetteB = this.vignetteB;
			r.vignetteTexture = this.vignetteIntercepted && this.vignetteView != null ? ((GlTexture) this.vignetteView.texture()).glId() : 0;
			this.detectCut(r);
		} else {
			if (this.worldSlot != null) {
				this.exchange.abandon(this.worldSlot);
			}
			encoder.copyTextureToTexture(presented, overlay.color, 0, 0, 0, 0, 0, w, h);
			r.opaque = true;
			r.world = null;
		}
		this.worldSlot = null;
		r.overlay = overlay;

		// Crosshair & co: draw them for real into the presented image, and hand them to the presenter. GUI coordinates
		// are in the window's GUI scale (resolution-scaling mods leave the window itself alone).
		if (!this.pendingQuads.isEmpty()) {
			int guiScale = Minecraft.getInstance().getWindow().getGuiScale();
			this.replayQuads(encoder, mainColor, r, guiScale, w, h);
			if (r.opaque) {
				// The overlay is the full image: it must contain the crosshair too.
				encoder.copyTextureToTexture(presented, overlay.color, 0, 0, 0, 0, 0, w, h);
				r.invertQuads.clear();
			}
		}
		return true;
	}

	/**
	 * Turns the presented texture (currently holding only the GUI on transparent) back into the full frame.
	 * With a resolution-scaling mod's base image: GUI over vignette x base (which already contains world + hand,
	 * downscaled by that mod). Otherwise: overlay over vignette x captured world, exactly like vanilla.
	 */
	private void rebuildPresented(CommandEncoder encoder, GpuTextureView target, GpuTextureView vignette, @Nullable GpuTextureView overlay, int w, int h) {
		if (this.baseCaptured && this.baseImage != null && this.baseImage.getWidth(0) == w && this.baseImage.getHeight(0) == h) {
			this.guiCopy = this.ensureTemp(this.guiCopy, "FrameGen GUI copy", w, h);
			encoder.copyTextureToTexture(target.texture(), this.guiCopy.texture(), 0, 0, 0, 0, 0, w, h);
			this.writeCompositeUbo(encoder, 1, true);
			GpuTextureView gui = this.guiCopy;
			GpuTextureView base = this.baseImage;
			// compose: gui + (1 - gui.a) * vignette * base, alpha 1 (the base is opaque)
			this.fullscreenPass(encoder, "FrameGen rebuild frame (scaled)", target, FrameGenPipelines.COMPOSE_OVERLAY, pass -> {
				pass.setUniform("GuiSampler", gui, nearest());
				pass.setUniform("HandSampler", base, nearest());
				pass.setUniform("VignetteSampler", vignette, linear());
				pass.setUniform("FrameGenComposite", this.compositeUbo.slice(256, 32));
			});
		} else if (overlay != null && this.worldSlot != null && this.worldSlot.width == w && this.worldSlot.height == h) {
			GpuTextureView world = this.worldSlot.colorView;
			this.writeCompositeUbo(encoder, 1, this.handSplit);
			this.fullscreenPass(encoder, "FrameGen rebuild frame", target, FrameGenPipelines.FINAL, pass -> {
				pass.setUniform("OverlaySampler", overlay, nearest());
				pass.setUniform("WorldSampler", world, nearest());
				pass.setUniform("VignetteSampler", vignette, linear());
				pass.setUniform("FrameGenComposite", this.compositeUbo.slice(256, 32));
			});
		}
	}

	/** After {@code CommandEncoder.submit()}: fence the frame's GPU work and hand it to the presenter. */
	public @Nullable FrameRecord publish() {
		FrameRecord r = this.record;
		this.record = null;
		if (r == null || r.overlay == null) {
			return null;
		}
		long fence = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		GL32C.glFlush();
		this.exchange.publish(r, fence);
		return r;
	}

	// ==== Helpers =================================================================================================

	private void resetFrame() {
		if (this.worldSlot != null) {
			this.exchange.abandon(this.worldSlot);
			this.worldSlot = null;
		}
		if (this.record != null && this.record.overlay != null) {
			this.exchange.abandon(this.record.overlay);
		}
		this.record = null;
		this.splitPlanned = false;
		this.worldCaptured = false;
		this.handSplit = false;
		this.guiSplit = false;
		this.vignetteIntercepted = false;
		this.vignetteView = null;
		this.pendingQuads.clear();
		this.levelProjectionSet = false;
		this.levelTarget = null;
		this.scaled = false;
		this.guiTarget = null;
		this.baseCaptured = false;
	}

	private void detectCut(FrameRecord r) {
		Minecraft mc = Minecraft.getInstance();
		Object level = mc.level;
		Object cameraEntity = mc.getCameraEntity();
		boolean cut = Double.isNaN(this.lastCamX) || level != this.lastLevel || cameraEntity != this.lastCameraEntity;
		if (!cut) {
			double dx = r.camX - this.lastCamX;
			double dy = r.camY - this.lastCamY;
			double dz = r.camZ - this.lastCamZ;
			// Faster than ~elytra-with-rockets per frame is not motion, it's a teleport.
			cut = dx * dx + dy * dy + dz * dz > 12.0 * 12.0;
		}
		if (r.simNanos - this.lastWorldPublishNanos > Presenter.STALE_FRAME_NANOS) {
			cut = true;
		}
		r.cut = cut;
		this.lastCamX = r.camX;
		this.lastCamY = r.camY;
		this.lastCamZ = r.camZ;
		this.lastLevel = level;
		this.lastCameraEntity = cameraEntity;
		this.lastWorldPublishNanos = r.simNanos;
	}

	private void replayQuads(CommandEncoder encoder, GpuTextureView target, FrameRecord r, int guiScale, int w, int h) {
		GpuBuffer ubo = this.quadUbo();
		Vector2f p0 = new Vector2f();
		Vector2f p1 = new Vector2f();
		int i = 0;
		for (PendingQuad q : this.pendingQuads) {
			q.pose().transformPosition(q.x0(), q.y0(), p0);
			q.pose().transformPosition(q.x1(), q.y1(), p1);
			float x0 = Math.min(p0.x, p1.x) * guiScale;
			float y0 = Math.min(p0.y, p1.y) * guiScale;
			float x1 = Math.max(p0.x, p1.x) * guiScale;
			float y1 = Math.max(p0.y, p1.y) * guiScale;
			float cr = ARGB.redFloat(q.color());
			float cg = ARGB.greenFloat(q.color());
			float cb = ARGB.blueFloat(q.color());
			float ca = ARGB.alphaFloat(q.color());
			r.invertQuads.add(new FrameRecord.InvertQuad(x0, y0, x1, y1, q.u0(), q.v0(), q.u1(), q.v1(),
				((GlTexture) q.texture().texture()).glId(), cr, cg, cb, ca));

			try (MemoryStack stack = MemoryStack.stackPush()) {
				ByteBuffer data = Std140Builder.onStack(stack, 64)
					.putVec4(x0, y0, x1, y1)
					.putVec4(q.u0(), q.v0(), q.u1(), q.v1())
					.putVec4(cr, cg, cb, ca)
					.putVec4(w, h, 0.0f, 0.0f)
					.get();
				encoder.writeToBuffer(ubo.slice((long) i * QUAD_STRIDE, 64), data);
			}
			final int index = i;
			this.fullscreenPass(encoder, "FrameGen crosshair", target, FrameGenPipelines.INVERT_QUAD, pass -> {
				pass.setUniform("Sampler0", q.texture(), nearest());
				pass.setUniform("FrameGenQuad", ubo.slice((long) index * QUAD_STRIDE, 64));
			});
			i++;
		}
	}

	/** Writes composite parameters (vignette, whether the hand layer is present) into UBO slot 0 or 1. */
	private void writeCompositeUbo(CommandEncoder encoder, int slot, boolean handLayer) {
		if (this.compositeUbo == null) {
			this.compositeUbo = RenderSystem.getDevice().createBuffer(() -> "FrameGen composite UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, 512);
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			ByteBuffer data = Std140Builder.onStack(stack, 32)
				.putVec4(this.vignetteR, this.vignetteG, this.vignetteB, this.vignetteIntercepted ? 1.0f : 0.0f)
				.putVec4(handLayer ? 1.0f : 0.0f, 0.0f, 0.0f, 0.0f)
				.get();
			encoder.writeToBuffer(this.compositeUbo.slice(slot * 256L, 32), data);
		}
	}

	/** A reusable RGBA8 render texture of the given size (recreated when the size changes). */
	private GpuTextureView ensureTemp(@Nullable GpuTextureView current, String label, int w, int h) {
		if (current != null && current.getWidth(0) == w && current.getHeight(0) == h) return current;
		if (current != null) {
			GpuTexture old = current.texture();
			current.close();
			old.close();
		}
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
		GpuTexture texture = RenderSystem.getDevice().createTexture(label, usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		return RenderSystem.getDevice().createTextureView(texture);
	}

	private void downsample(CommandEncoder encoder, GpuTextureView source, GpuTextureView target) {
		this.fullscreenPass(encoder, "FrameGen downsample", target, FrameGenPipelines.DOWNSAMPLE, pass ->
			pass.setUniform("InSampler", source, nearest()));
	}

	private void downsampleDepth(CommandEncoder encoder, GpuTextureView source, GpuTextureView targetDepth) {
		RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "FrameGen downsample depth").withDepthAttachment(targetDepth).build();
		try (RenderPass pass = encoder.createRenderPass(descriptor)) {
			pass.setUniform("InSampler", source, nearest());
			pass.setPipeline(RenderSystem.getCompiledPipeline(FrameGenPipelines.DOWNSAMPLE_DEPTH));
			pass.draw(3, 1, 0, 0);
		}
	}

	private GpuBuffer quadUbo() {
		if (this.quadUbo == null) {
			this.quadUbo = RenderSystem.getDevice().createBuffer(() -> "FrameGen quad UBO", GpuBuffer.USAGE_UNIFORM | GpuBuffer.USAGE_COPY_DST, (long) QUAD_STRIDE * MAX_QUADS);
		}
		return this.quadUbo;
	}

	private void ensureHandTexture(int w, int h) {
		if (this.handTexture != null && this.handTexture.getWidth(0) == w && this.handTexture.getHeight(0) == h) return;
		if (this.handView != null) this.handView.close();
		if (this.handTexture != null) this.handTexture.close();
		int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
		this.handTexture = RenderSystem.getDevice().createTexture("FrameGen hand layer", usage, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		this.handView = RenderSystem.getDevice().createTextureView(this.handTexture);
	}

	private GpuTextureView blankView() {
		if (this.blankView == null) {
			int usage = GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_RENDER_ATTACHMENT;
			this.blankTexture = RenderSystem.getDevice().createTexture("FrameGen blank", usage, GpuFormat.RGBA8_UNORM, 1, 1, 1, 1);
			this.blankView = RenderSystem.getDevice().createTextureView(this.blankTexture);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(this.blankTexture, TRANSPARENT);
		}
		return this.blankView;
	}

	private static GpuSampler nearest() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.NEAREST);
	}

	private static GpuSampler linear() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	private interface PassSetup {
		void setup(RenderPass pass);
	}

	private void fullscreenPass(CommandEncoder encoder, String label, GpuTextureView target, RenderPipeline pipeline, PassSetup setup) {
		try (RenderPass pass = encoder.createRenderPass(() -> label, target, Optional.empty())) {
			pass.setPipeline(RenderSystem.getCompiledPipeline(pipeline));
			setup.setup(pass);
			pass.draw(3, 1, 0, 0);
		}
	}

	void close() {
		this.resetFrame();
		if (this.handView != null) this.handView.close();
		if (this.handTexture != null) this.handTexture.close();
		if (this.blankView != null) this.blankView.close();
		if (this.blankTexture != null) this.blankTexture.close();
		if (this.compositeUbo != null) this.compositeUbo.close();
		if (this.quadUbo != null) this.quadUbo.close();
		for (GpuTextureView temp : new GpuTextureView[]{this.hiresWorld, this.baseImage, this.guiCopy, this.handRaw}) {
			if (temp != null) {
				GpuTexture texture = temp.texture();
				temp.close();
				texture.close();
			}
		}
		this.hiresWorld = null;
		this.baseImage = null;
		this.guiCopy = null;
		this.handRaw = null;
		this.handView = null;
		this.handTexture = null;
		this.blankView = null;
		this.blankTexture = null;
		this.compositeUbo = null;
		this.quadUbo = null;
	}
}
