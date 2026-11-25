#version 330
#extension GL_ARB_separate_shader_objects : require

// Downscales a depth buffer by taking the texel at the centre of each output pixel's footprint.
uniform sampler2D InSampler;

layout(location = 0) in vec2 texCoord;

void main() {
    vec2 srcSize = vec2(textureSize(InSampler, 0));
    gl_FragDepth = texelFetch(InSampler, clamp(ivec2(texCoord * srcSize), ivec2(0), ivec2(srcSize) - 1), 0).r;
}
