#version 330
#extension GL_ARB_separate_shader_objects : require

// Replays an invert-blended HUD sprite (the crosshair) over the finished frame. Drawn as a fullscreen triangle;
// everything outside the sprite's rectangle is discarded.
uniform sampler2D Sampler0;

layout(std140) uniform FrameGenQuad {
    vec4 Rect;   // pixels, origin top left: x0, y0, x1, y1
    vec4 UvRect; // u0, v0, u1, v1
    vec4 Color;
    vec4 Screen; // width, height
};

layout(location = 0) in vec2 texCoord;

layout(location = 0) out vec4 fragColor;

void main() {
    vec2 px = vec2(texCoord.x * Screen.x, (1.0 - texCoord.y) * Screen.y);
    vec2 f = (px - Rect.xy) / (Rect.zw - Rect.xy);
    if (any(lessThan(f, vec2(0.0))) || any(greaterThanEqual(f, vec2(1.0)))) {
        discard;
    }
    vec4 color = texture(Sampler0, mix(UvRect.xy, UvRect.zw, f)) * Color;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
