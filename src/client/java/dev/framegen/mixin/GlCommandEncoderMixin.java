package dev.framegen.mixin;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fixes a 26.3 OpenGL backend bug: reading back a depth texture sets the shared read framebuffer's read buffer to
 * GL_NONE and never restores it, so every later colour copy through that framebuffer (copyTextureToTexture,
 * copyTextureToBuffer) silently copies nothing. Any mod that reads depth once breaks colour copies for everyone.
 */
@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder", remap = false)
abstract class GlCommandEncoderMixin {
	@Shadow
	@Final
	private int readFbo;

	@Inject(
		method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JLjava/lang/Runnable;IIIII)V",
		at = @At("TAIL"),
		require = 0
	)
	private void framegen$restoreReadBuffer(GpuTexture source, GpuBuffer destination, long offset, Runnable callback, int mipLevel,
		int x, int y, int width, int height, CallbackInfo ci) {
		if (source.getFormat().hasDepthAspect()) {
			GlStateManager._glBindFramebuffer(36008, this.readFbo); // GL_READ_FRAMEBUFFER
			GlStateManager._glReadBuffer(36064); // GL_COLOR_ATTACHMENT0
			GlStateManager._glBindFramebuffer(36008, 0);
		}
	}
}
