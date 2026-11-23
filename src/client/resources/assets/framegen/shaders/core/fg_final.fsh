#version 330
#extension GL_ARB_separate_shader_objects : require

// Rebuilds the exact vanilla frame from the separated layers: overlay + (1 - overlay.a) * vignette * world.
uniform sampler2D OverlaySampler;
uniform sampler2D WorldSampler;
uniform sampler2D VignetteSampler;

layout(std140) uniform FrameGenComposite {
    vec4 VignetteColor;
    vec4 Flags;
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 o = texture(OverlaySampler, texCoord);
    vec3 w = texture(WorldSampler, texCoord).rgb;
    vec3 v = VignetteColor.w > 0.5 ? 1.0 - texture(VignetteSampler, vec2(texCoord.x, 1.0 - texCoord.y)).rgb * VignetteColor.rgb : vec3(1.0);
    fragColor = vec4(o.rgb + (1.0 - o.a) * v * w, 1.0);
}
