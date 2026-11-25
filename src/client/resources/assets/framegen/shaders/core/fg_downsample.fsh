#version 330
#extension GL_ARB_separate_shader_objects : require

// Box-filters a larger image (a supersampled main target) down to the size of the render target. The footprint
// of one output pixel in source pixels comes from the screen-space derivative, so any ratio works (1.5x, 2x, 4x...).
uniform sampler2D InSampler;

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
            vec2 q = p - f * 0.5 + (vec2(i, j) + 0.5) * f / vec2(n);
            sum += texelFetch(InSampler, clamp(ivec2(q), ivec2(0), maxTexel), 0);
        }
    }
    fragColor = sum / float(n.x * n.y);
}
