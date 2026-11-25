#version 330 core
// A real frame shown as is (no reprojection): overlay + (1 - overlay.a) * vignette * world.
uniform sampler2D uWorld;
uniform sampler2D uOverlay;
uniform sampler2D uVignetteTex;
uniform int uVignette;
uniform vec3 uVignetteColor;
uniform vec4 uWorldRect; // visible part of the world layer (overscan margin excluded), in UV
in vec2 vUv;
out vec4 fragColor;

void main() {
    vec3 world = texture(uWorld, mix(uWorldRect.xy, uWorldRect.zw, vUv)).rgb;
    vec3 vignette = uVignette == 1 ? 1.0 - texture(uVignetteTex, vec2(vUv.x, 1.0 - vUv.y)).rgb * uVignetteColor : vec3(1.0);
    vec4 overlay = texture(uOverlay, vUv);
    fragColor = vec4(overlay.rgb + (1.0 - overlay.a) * vignette * world, 1.0);
}
