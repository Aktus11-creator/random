package dev.framegen.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Development aid only: software rasterizers under Xvfb (used for automated testing) expose no sRGB-capable GLX
 * visuals, so the window can't be created. Only active when {@code -Dframegen.dev.noSrgbVisual=true} is set,
 * which the dev run configuration does; players never set it. Minecraft never enables GL_FRAMEBUFFER_SRGB, so the
 * attribute doesn't change rendering.
 */
@Mixin(GlBackend.class)
abstract class GlBackendMixin {
	@WrapOperation(
		method = "createWindow",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_GL_SetAttribute(II)Z", remap = false),
		require = 0
	)
	private boolean framegen$devNoSrgb(int attribute, int value, Operation<Boolean> original) {
		if (attribute == 22 && Boolean.getBoolean("framegen.dev.noSrgbVisual")) {
			value = 0;
		}
		return original.call(attribute, value);
	}
}
