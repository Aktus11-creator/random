#version 330 core
uniform sampler2D uOverlay;
in vec2 vUv;
out vec4 fragColor;

void main() {
    fragColor = vec4(texture(uOverlay, vUv).rgb, 1.0);
}
