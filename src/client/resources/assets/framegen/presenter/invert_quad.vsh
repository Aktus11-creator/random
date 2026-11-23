#version 330 core
// Screen-space quad (triangle strip of 4 vertices). uRect/uUvRect are (x0, y0, x1, y1).
uniform vec4 uRect;   // NDC
uniform vec4 uUvRect; // texture coordinates
out vec2 vUv;

void main() {
    vec2 c = vec2(float(gl_VertexID & 1), float((gl_VertexID >> 1) & 1));
    vUv = mix(uUvRect.xy, uUvRect.zw, c);
    gl_Position = vec4(mix(uRect.xy, uRect.zw, c), 0.0, 1.0);
}
