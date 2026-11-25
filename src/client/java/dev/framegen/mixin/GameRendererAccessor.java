package dev.framegen.mixin;

import com.mojang.blaze3d.pipeline.RenderTarget;
import java.util.List;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.GlobalSettingsUniform;
import net.minecraft.client.renderer.PostChain;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface GameRendererAccessor {
	@Accessor("appliedPostEffects")
	List<PostChain> framegen$appliedPostEffects();

	/** Overscan swaps in a larger target while the level renders (see {@code FrameCapture.beginOverscan}). */
	@Mutable
	@Accessor("mainRenderTarget")
	void framegen$setMainRenderTarget(RenderTarget target);

	@Accessor("globalSettingsUniform")
	GlobalSettingsUniform framegen$globalSettingsUniform();
}
