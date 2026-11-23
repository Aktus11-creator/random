#version 330
#extension GL_ARB_separate_shader_objects : require

// Merges the HUD layer (G) and the first-person hand layer (H), both premultiplied, into one overlay:
//   final = G + (1 - G.a) * V * (H + (1 - H.a) * world)
//         = [G + (1 - G.a) * V * H]  +  (1 - G.a)(1 - H.a) * V * world
// so overlay.rgb = G + (1 - G.a) V H and overlay.a = 1 - (1 - G.a)(1 - H.a); V (vignette) is applied to the world later.
uniform sampler2D GuiSampler;
uniform sampler2D HandSampler;
uniform sampler2D VignetteSampler;

layout(std140) uniform FrameGenComposite {
    vec4 VignetteColor; // rgb, w = enabled
    vec4 Flags;         // x = hand layer present
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec4 g = texture(GuiSampler, texCoord);
    vec4 h = Flags.x > 0.5 ? texture(HandSampler, texCoord) : vec4(0.0);
    vec3 v = VignetteColor.w > 0.5 ? 1.0 - texture(VignetteSampler, vec2(texCoord.x, 1.0 - texCoord.y)).rgb * VignetteColor.rgb : vec3(1.0);
    fragColor = vec4(g.rgb + (1.0 - g.a) * v * h.rgb, 1.0 - (1.0 - g.a) * (1.0 - h.a));
}
