#version 330 core
// FrameGen reprojection + composite.
//
// For every output pixel we look for the point in each source frame (A = older, B = newer) that lands on this pixel
// once moved into the generated camera. Each source frame carries color + depth + the exact matrices it was rendered
// with, so this is an exact geometric reprojection of static geometry (camera rotation, translation, FOV changes and
// view bobbing), not optical flow guesswork.
//
// Forward mapping source -> target is easy (unproject with depth, reproject), but we need the inverse (target pixel ->
// source pixel) to gather colors without holes. We solve it with a fixed-point iteration:
//   s_{k+1} = Inv( t, depthInTarget( Fwd( s_k, depth_source(s_k) ) ) )
// which converges in 1-2 steps on smooth surfaces. The final residual |Fwd(s) - t| tells us whether the source really
// saw this point (small) or whether it is disoccluded / off-screen in that frame (large), and drives the blending.

uniform sampler2D uColorA;
uniform sampler2D uDepthA;
uniform sampler2D uColorB;
uniform sampler2D uDepthB;
uniform sampler2D uOverlay;
uniform sampler2D uVignetteTex;

// Source NDC (+ depth) -> target clip space, with and without the camera translation (sky/void has no parallax).
uniform mat4 uFwdA;
uniform mat4 uFwdRotA;
uniform mat4 uInvA;
uniform mat4 uInvRotA;
uniform mat4 uFwdB;
uniform mat4 uFwdRotB;
uniform mat4 uInvB;
uniform mat4 uInvRotB;

uniform vec2 uSizeA;      // source A size in texels
uniform vec2 uSizeB;      // source B size in texels
uniform vec2 uOutSize;    // output size in pixels
uniform float uWeightA;   // temporal weight of A (B gets the rest)
uniform int uUseA;
uniform int uIterations;
uniform int uBackgroundSearch;
uniform int uZeroToOne;
uniform int uVignette;
uniform vec3 uVignetteColor;
uniform int uDebug;

in vec2 vUv;
out vec4 fragColor;

const float EPS_W = 1e-6;
const float ERR_GOOD = 0.75;  // pixels: reprojection agrees
const float ERR_BAD = 3.0;    // pixels: source did not see this point

float toNdcZ(float d) {
    return uZeroToOne == 1 ? d : d * 2.0 - 1.0;
}

// Depth at a continuous source position. Window-space depth is an affine function of screen position across any
// plane, so bilinear interpolation is exact on Minecraft's flat faces and avoids the stair-stepping (and false
// reprojection residuals) of nearest-texel lookups. Across depth discontinuities interpolating would invent
// surfaces floating in mid-air, so there we fall back to the nearest texel.
float fetchDepth(sampler2D depthTex, vec2 size, vec2 s) {
    vec2 p = (s * 0.5 + 0.5) * size - 0.5;
    vec2 base = floor(p);
    vec2 f = p - base;
    ivec2 i0 = ivec2(clamp(base, vec2(0.0), size - 1.0));
    ivec2 i1 = ivec2(clamp(base + 1.0, vec2(0.0), size - 1.0));
    float d00 = texelFetch(depthTex, i0, 0).r;
    float d10 = texelFetch(depthTex, ivec2(i1.x, i0.y), 0).r;
    float d01 = texelFetch(depthTex, ivec2(i0.x, i1.y), 0).r;
    float d11 = texelFetch(depthTex, i1, 0).r;
    float lo = min(min(d00, d10), min(d01, d11));
    float hi = max(max(d00, d10), max(d01, d11));
    // Reversed-Z: depth ~ near / distance, so a relative spread compares distances regardless of range.
    if (lo > 0.0 && hi - lo <= 0.04 * hi) {
        return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
    }
    vec2 n = step(0.5, f);
    return n.y < 0.5 ? (n.x < 0.5 ? d00 : d10) : (n.x < 0.5 ? d01 : d11);
}

// Maps source point s (NDC xy) with source depth d into the target frame; returns target clip coordinates.
vec4 forwardMap(mat4 fwd, mat4 fwdRot, vec2 s, float d) {
    // Reversed-Z: 0 is the cleared far plane (sky, void). Treat it as infinitely far: rotation only.
    return (d <= 0.0 ? fwdRot : fwd) * vec4(s, toNdcZ(d), 1.0);
}

struct Candidate {
    vec3 color;
    float err;
};

Candidate reproject(sampler2D colorTex, sampler2D depthTex, vec2 size, mat4 fwd, mat4 fwdRot, mat4 inv, mat4 invRot, vec2 t) {
    // Rotation-only inverse is independent of depth and is the best first guess for large camera turns.
    vec4 g = invRot * vec4(t, 0.5, 1.0);
    vec2 s0 = g.w > EPS_W ? g.xy / g.w : t;
    vec2 s = s0;
    for (int i = 0; i < uIterations; i++) {
        float d = fetchDepth(depthTex, size, s);
        vec4 p = forwardMap(fwd, fwdRot, s, d);
        if (p.w <= EPS_W) break;
        vec4 q = (d <= 0.0 ? invRot : inv) * vec4(t, p.z / p.w, 1.0);
        if (q.w <= EPS_W) break;
        s = q.xy / q.w;
    }

    float d = fetchDepth(depthTex, size, s);
    vec4 p = forwardMap(fwd, fwdRot, s, d);
    float err = p.w > EPS_W ? length((p.xy / p.w - t) * uOutSize * 0.5) : 1e4;

    if (uBackgroundSearch == 1 && err > ERR_GOOD) {
        // Disocclusion: the iteration got pulled onto a foreground edge. Probe along the search direction for the
        // farthest surface and reproject *that* depth: fills the hole with background instead of smearing the edge.
        vec2 dir = s - s0;
        float len = length(dir * size);
        dir = len > 1e-3 ? dir / len * 2.0 / size : vec2(2.0 / size.x, 0.0);
        float bestD = d;
        vec2 bestS = s;
        for (int k = -3; k <= 3; k++) {
            if (k == 0) continue;
            vec2 sk = s + dir * float(k) * 2.0;
            float dk = fetchDepth(depthTex, size, sk);
            if (dk < bestD) { // reversed-Z: smaller = farther
                bestD = dk;
                bestS = sk;
            }
        }
        if (bestD < d) {
            vec4 pb = forwardMap(fwd, fwdRot, bestS, bestD);
            if (pb.w > EPS_W) {
                vec4 qb = (bestD <= 0.0 ? invRot : inv) * vec4(t, pb.z / pb.w, 1.0);
                if (qb.w > EPS_W) {
                    vec2 sb = qb.xy / qb.w;
                    float db = fetchDepth(depthTex, size, sb);
                    vec4 pb2 = forwardMap(fwd, fwdRot, sb, db);
                    float errB = pb2.w > EPS_W ? length((pb2.xy / pb2.w - t) * uOutSize * 0.5) : 1e4;
                    // Accept if it is at least a plausible background fill.
                    if (errB < err) {
                        s = sb;
                        err = errB;
                    } else if (db <= bestD + 1e-7) {
                        s = sb;
                        err = min(err, ERR_BAD);
                    }
                }
            }
        }
    }

    vec2 uv = s * 0.5 + 0.5;
    // Points the source never saw because they were off-screen: clamp (edge stretch) but mark as unreliable.
    vec2 outside = max(max(-uv, uv - 1.0), vec2(0.0)) * size;
    err += length(outside) * 2.0;
    vec2 half_texel = 0.5 / size;
    Candidate c;
    c.color = texture(colorTex, clamp(uv, half_texel, 1.0 - half_texel)).rgb;
    c.err = err;
    return c;
}

float confidence(float err) {
    return 1.0 - smoothstep(ERR_GOOD, ERR_BAD, err);
}

void main() {
    vec2 t = vUv * 2.0 - 1.0;

    Candidate cb = reproject(uColorB, uDepthB, uSizeB, uFwdB, uFwdRotB, uInvB, uInvRotB, t);
    vec3 world = cb.color;
    float shownErr = cb.err;

    if (uUseA == 1) {
        Candidate ca = reproject(uColorA, uDepthA, uSizeA, uFwdA, uFwdRotA, uInvA, uInvRotA, t);
        float confA = confidence(ca.err);
        float confB = confidence(cb.err);
        float wA = uWeightA * confA;
        float wB = (1.0 - uWeightA) * confB;
        // Things that moved on their own (mobs, particles, animated textures) disagree between the two frames even
        // where the reprojection is perfect. Cross-fading would show two half-transparent copies, so snap to the
        // temporally nearer frame instead.
        vec3 diff = abs(ca.color - cb.color);
        if (max(diff.r, max(diff.g, diff.b)) > 0.12) {
            if (uWeightA >= 0.5) { wB *= 0.02; } else { wA *= 0.02; }
        }
        float sum = wA + wB;
        if (sum > 1e-4) {
            world = (ca.color * wA + cb.color * wB) / sum;
            shownErr = min(ca.err, cb.err);
        } else {
            // Neither frame is confident: take whichever reprojection is less wrong.
            world = ca.err < cb.err ? ca.color : cb.color;
            shownErr = min(ca.err, cb.err);
        }
    }

    if (uDebug == 1) {
        float c = confidence(shownErr);
        world = mix(world, vec3(1.0, 0.0, 1.0), 0.6 * (1.0 - c));
    }

    vec3 vignette = vec3(1.0);
    if (uVignette == 1) {
        // Minecraft draws the vignette with blend (ZERO, ONE_MINUS_SRC_COLOR) over the world; textures are stored
        // top row first, our UVs have the origin at the bottom.
        vignette = 1.0 - texture(uVignetteTex, vec2(vUv.x, 1.0 - vUv.y)).rgb * uVignetteColor;
    }

    vec4 overlay = texture(uOverlay, vUv);
    // Alpha is unused by the window; the self-test reads the reprojection confidence from it.
    fragColor = vec4(overlay.rgb + (1.0 - overlay.a) * vignette * world, confidence(shownErr));
}
