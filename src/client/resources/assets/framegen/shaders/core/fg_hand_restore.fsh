#version 330
#extension GL_ARB_separate_shader_objects : require

// Puts the world back underneath the hand in the main target: hand + (1 - coverage) * world, where coverage is the
// hand's alpha or its depth (see fg_hand_capture). Output is opaque.
uniform sampler2D HandSampler;
uniform sampler2D WorldSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    ivec2 px = clamp(ivec2(texCoord * vec2(textureSize(HandSampler, 0))), ivec2(0), textureSize(HandSampler, 0) - 1);
    vec4 hand = texelFetch(HandSampler, px, 0);
    float coverage = max(hand.a, texelFetch(DepthSampler, px, 0).r > 0.0 ? 1.0 : 0.0);
    vec3 world = texture(WorldSampler, texCoord).rgb;
    fragColor = vec4(hand.rgb + (1.0 - coverage) * world, 1.0);
}
