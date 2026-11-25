package dev.framegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.framegen.engine.FrameCapture;
import dev.framegen.engine.FrameGenEngine;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The capture points described in {@link FrameCapture}. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void framegen$beginExtract(DeltaTracker deltaTracker, boolean advanceGameTime, CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.beginExtract();
	}

	/** Remember the frame's global uniforms so overscan can re-issue them with its larger screen size. */
	@WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlobalSettingsUniform;update(IIDJFILnet/minecraft/world/phys/Vec3;Z)V"))
	private void framegen$recordGlobalSettings(GlobalSettingsUniform uniform, int width, int height, double glintAlpha, long gameTime, float partialTicks,
		int menuBlurRadius, Vec3 cameraPos, boolean useRgss, Operation<Void> original) {
		original.call(uniform, width, height, glintAlpha, gameTime, partialTicks, menuBlurRadius, cameraPos, useRgss);
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.onGlobalSettings(uniform, glintAlpha, gameTime, partialTicks, menuBlurRadius, cameraPos, useRgss);
	}

	@Inject(method = "renderLevel", at = @At("HEAD"))
	private void framegen$beginLevel(CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.beginOverscan((GameRenderer) (Object) this);
	}

	@ModifyArg(
		method = "renderLevel",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;")
	)
	private Matrix4f framegen$captureProjection(Matrix4f projection) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.onLevelProjection(projection);
		return projection;
	}

	@Inject(method = "render3dHud", at = @At("HEAD"))
	private void framegen$beforeHand(CameraRenderState cameraState, PlayerRenderState playerState, OptionsRenderState optionsState,
		boolean consistentDepthRequired, CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.beforeHand(cameraState, consistentDepthRequired);
	}

	@Inject(method = "renderLevel", at = @At("TAIL"))
	private void framegen$afterLevel(CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) {
			capture.endOverscan(); // normally already done before the hand; never leave the swapped target behind
			capture.afterLevel();
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GameRenderer;applyPostEffects()V", shift = At.Shift.AFTER))
	private void framegen$afterPostEffects(CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) {
			capture.afterPostEffects(!((GameRendererAccessor) this).framegen$appliedPostEffects().isEmpty());
		}
	}

	@Inject(method = "render", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/render/GuiRenderer;render()V"))
	private void framegen$beforeGui(CallbackInfo ci) {
		FrameCapture capture = FrameGenEngine.capture();
		if (capture != null) capture.beforeGui();
	}
}
