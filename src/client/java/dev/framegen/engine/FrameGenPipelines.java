package dev.framegen.engine;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.UniformType;
import java.util.Optional;
import net.minecraft.client.renderer.BindGroupLayouts;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Render-thread pipelines, compiled by Minecraft's shader manager like any vanilla pipeline. */
public final class FrameGenPipelines {
	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath("framegen", path);
	}

	private static final BindGroupLayout COMPOSE_LAYOUT = BindGroupLayout.builder()
		.withUniform("GuiSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("HandSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("VignetteSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FrameGenComposite", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout FINAL_LAYOUT = BindGroupLayout.builder()
		.withUniform("OverlaySampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("WorldSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("VignetteSampler", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FrameGenComposite", UniformType.UNIFORM_BUFFER)
		.build();
	private static final BindGroupLayout INVERT_LAYOUT = BindGroupLayout.builder()
		.withUniform("Sampler0", UniformType.COMBINED_IMAGE_SAMPLER)
		.withUniform("FrameGenQuad", UniformType.UNIFORM_BUFFER)
		.build();

	/** Draws a texture opaquely *behind* what is already in the target: result = dst + (1 - dst.a) * src, alpha -> 1. */
	public static final RenderPipeline UNDER = RenderPipeline.builder()
		.withLocation(id("pipeline/under"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(id("core/fg_opaque"))
		.withBindGroupLayout(BindGroupLayouts.IN_SAMPLER)
		.withColorTargetState(new ColorTargetState(
			new BlendFunction(BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE, BlendFactor.ONE_MINUS_DST_ALPHA, BlendFactor.ONE)))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	public static final RenderPipeline COMPOSE_OVERLAY = RenderPipeline.builder()
		.withLocation(id("pipeline/compose_overlay"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(id("core/fg_compose"))
		.withBindGroupLayout(COMPOSE_LAYOUT)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	public static final RenderPipeline FINAL = RenderPipeline.builder()
		.withLocation(id("pipeline/final"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(id("core/fg_final"))
		.withBindGroupLayout(FINAL_LAYOUT)
		.withColorTargetState(new ColorTargetState(Optional.empty(), GpuFormat.RGBA8_UNORM, ColorTargetState.WRITE_ALL))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	public static final RenderPipeline INVERT_QUAD = RenderPipeline.builder()
		.withLocation(id("pipeline/invert_quad"))
		.withVertexShader("core/screenquad")
		.withFragmentShader(id("core/fg_invert"))
		.withBindGroupLayout(INVERT_LAYOUT)
		.withColorTargetState(new ColorTargetState(BlendFunction.INVERT))
		.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
		.build();

	private FrameGenPipelines() {
	}

	public static void register() {
		RenderPipelines.register(UNDER);
		RenderPipelines.register(COMPOSE_OVERLAY);
		RenderPipelines.register(FINAL);
		RenderPipelines.register(INVERT_QUAD);
	}
}
