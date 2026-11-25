#version 330
#extension GL_ARB_separate_shader_objects : require

// Depth version of fg_crop: copies the centre of a larger depth buffer 1:1 into a depth-only render pass.
uniform sampler2D InSampler;

layout(std140) uniform FrameGenCrop {
    vec4 Crop; // xy = offset in source texels, zw = target size in pixels
};

layout(location = 0) in vec2 texCoord;

void main() {
    ivec2 p = ivec2(floor(texCoord * Crop.zw)) + ivec2(Crop.xy);
    gl_FragDepth = texelFetch(InSampler, clamp(p, ivec2(0), textureSize(InSampler, 0) - 1), 0).r;
}
