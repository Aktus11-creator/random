#version 330 core
// Mirrors Minecraft's crosshair shader (position_tex_color); blending is INVERT.
uniform sampler2D uTexture;
uniform vec4 uColor;
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec4 color = texture(uTexture, vUv) * uColor;
    if (color.a == 0.0) {
        discard;
    }
    fragColor = color;
}
