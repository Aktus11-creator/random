#version 330
#extension GL_ARB_separate_shader_objects : require

// Copies the centre of a larger image (the overscanned world) 1:1 into the render target: target pixel p reads
// source texel p + Offset. The margins are symmetric, so the result doesn't depend on which way the y axis points.
uniform sampler2D InSampler;

layout(std140) uniform FrameGenCrop {
    vec4 Crop; // xy = offset in source texels, zw = target size in pixels
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 p = ivec2(floor(texCoord * Crop.zw)) + ivec2(Crop.xy);
    fragColor = texelFetch(InSampler, clamp(p, ivec2(0), textureSize(InSampler, 0) - 1), 0);
}
