#version 330
#extension GL_ARB_separate_shader_objects : require

// Extracts the first-person hand layer (drawn onto a transparent target) as premultiplied colour + coverage,
// downscaling if the source is larger (supersampling). Coverage comes from alpha *or* the hand's depth: Minecraft
// clears the depth right before drawing the hand, so any written depth is hand. That keeps the hand even when some
// render pipeline (mods, emissive layers, 3D skin layers...) doesn't write alpha.
uniform sampler2D InSampler;
uniform sampler2D DepthSampler;

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 srcSize = vec2(textureSize(InSampler, 0));
    vec2 p = texCoord * srcSize;
    vec2 f = max(vec2(1.0), vec2(abs(dFdx(p.x)), abs(dFdy(p.y))));
    ivec2 n = ivec2(min(ceil(f), vec2(4.0)));
    ivec2 maxTexel = ivec2(srcSize) - 1;
    vec4 sum = vec4(0.0);
    for (int j = 0; j < 4; j++) {
        if (j >= n.y) break;
        for (int i = 0; i < 4; i++) {
            if (i >= n.x) break;
            ivec2 q = clamp(ivec2(p - f * 0.5 + (vec2(i, j) + 0.5) * f / vec2(n)), ivec2(0), maxTexel);
            vec4 c = texelFetch(InSampler, q, 0);
            float depthCoverage = texelFetch(DepthSampler, q, 0).r > 0.0 ? 1.0 : 0.0;
            sum += vec4(c.rgb, max(c.a, depthCoverage));
        }
    }
    fragColor = sum / float(n.x * n.y);
}
