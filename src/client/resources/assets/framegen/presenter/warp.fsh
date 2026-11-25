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
//
// Moving objects: each source frame comes with a short list of entities (a box around each, relative to that frame's
// camera, plus where the entity is at the generated moment: a translation and a turn about its vertical axis).
// Points inside a box are moved with their entity before being projected into the target. The iteration then uses
// the residual form s += Inv(t) - Inv(Fwd(s)), which reduces to the plain step for static points.

uniform sampler2D uColorA;
uniform sampler2D uDepthA;
uniform sampler2D uColorB;
uniform sampler2D uDepthB;
uniform sampler2D uOverlay;
// Depth before water, stained glass, ice and clouds were drawn: the depth of what is seen through them.
uniform sampler2D uOpaqueDepthA;
uniform sampler2D uOpaqueDepthB;
uniform int uOpaqueA;
uniform int uOpaqueB;
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

// Source NDC -> point relative to the source camera (homogeneous), and that point -> target clip space.
uniform mat4 uSrcInvA;
uniform mat4 uTgtA;
uniform mat4 uSrcInvB;
uniform mat4 uTgtB;
// Moving entities, frame A from index 0 and frame B from ENT_B. Six vec4 each: (centre x, centre z, min y, max y),
// (facing x, facing z, half width, half length) of a box that turns with the body, translation, (turn angle, -, -, -),
// the moved box's rectangle in target NDC, and how far the entity's centre moves on screen (target NDC) compared with
// static scenery. Positions are relative to the source camera.
#define MAX_ENT 32
#define ENT_B (MAX_ENT * 6)
layout(std140) uniform FrameGenEntities {
    vec4 uEnt[2 * MAX_ENT * 6];
};
uniform int uEntCountA;
uniform int uEntCountB;
// Bounding rectangles of all moving boxes, in source NDC (before moving) and target NDC (after): quick rejects so that
// pixels far from moving entities cost nothing extra.
uniform vec4 uSrcRectA;
uniform vec4 uSrcRectB;
uniform vec4 uTgtRectA;
uniform vec4 uTgtRectB;

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
#ifdef HAS_GATHER
    // One fetch for the 2x2 footprint (clamp-to-edge sampler == clamped texel indices).
    vec4 g = textureGather(depthTex, (base + 1.0) / size);
    float d00 = g.w;
    float d10 = g.z;
    float d01 = g.x;
    float d11 = g.y;
#else
    ivec2 i0 = ivec2(clamp(base, vec2(0.0), size - 1.0));
    ivec2 i1 = ivec2(clamp(base + 1.0, vec2(0.0), size - 1.0));
    float d00 = texelFetch(depthTex, i0, 0).r;
    float d10 = texelFetch(depthTex, ivec2(i1.x, i0.y), 0).r;
    float d01 = texelFetch(depthTex, ivec2(i0.x, i1.y), 0).r;
    float d11 = texelFetch(depthTex, i1, 0).r;
#endif
    float lo = min(min(d00, d10), min(d01, d11));
    float hi = max(max(d00, d10), max(d01, d11));
    // Reversed-Z: depth ~ near / distance, so a relative spread compares distances regardless of range.
    if (lo > 0.0 && hi - lo <= 0.04 * hi) {
        return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
    }
    vec2 n = step(0.5, f);
    return n.y < 0.5 ? (n.x < 0.5 ? d00 : d10) : (n.x < 0.5 ? d01 : d11);
}

float fetchDepthNearest(sampler2D depthTex, vec2 size, vec2 s) {
    ivec2 px = ivec2(clamp(floor((s * 0.5 + 0.5) * size), vec2(0.0), size - 1.0));
    return texelFetch(depthTex, px, 0).r;
}

// Maps source point s (NDC xy) with source depth d into the target frame; returns target clip coordinates.
vec4 forwardMap(mat4 fwd, mat4 fwdRot, vec2 s, float d) {
    // Reversed-Z: 0 is the cleared far plane (sky, void). Treat it as infinitely far: rotation only.
    return (d <= 0.0 ? fwdRot : fwd) * vec4(s, toNdcZ(d), 1.0);
}

struct Candidate {
    vec3 color;
    float err;
    float z;     // target NDC depth of the reconstructed point (reversed-Z: larger = nearer)
    bool moved;  // the point belongs to a moving entity
    int movedId; // which one (index in the frame's entity list), -1 if none
    vec2 s;      // source position (NDC)
};

// Like forwardMap, but moves points that lie inside an entity box with that entity.
bool inRect(vec2 p, vec4 r) {
    return all(greaterThanEqual(p, r.xy)) && all(lessThanEqual(p, r.zw));
}

vec4 forwardMapMoving(mat4 fwd, mat4 fwdRot, mat4 srcInv, mat4 tgt, int ents, int count, vec4 srcRect, vec2 s, float d, out bool moved, out int movedId) {
    moved = false;
    movedId = -1;
    if (count == 0 || d <= 0.0 || !inRect(s, srcRect)) return forwardMap(fwd, fwdRot, s, d);
    vec4 h = srcInv * vec4(s, toNdcZ(d), 1.0);
    if (abs(h.w) < 1e-9) return forwardMap(fwd, fwdRot, s, d);
    vec3 p = h.xyz / h.w;
    for (int i = 0; i < count; i++) {
        int e = ents + i * 6;
        vec4 box = uEnt[e];
        vec4 axes = uEnt[e + 1];
        vec2 rel = p.xz - box.xy;
        if (p.y >= box.z && p.y <= box.w && abs(dot(rel, axes.xy)) <= axes.w && abs(dot(rel, vec2(-axes.y, axes.x))) <= axes.z) {
            vec3 move = uEnt[e + 2].xyz;
            float angle = uEnt[e + 3].x;
            float c = cos(angle);
            float sn = sin(angle);
            p.xz = box.xy + vec2(c * rel.x + sn * rel.y, -sn * rel.x + c * rel.y);
            p += move;
            moved = true;
            movedId = i;
            break;
        }
    }
    return moved ? tgt * vec4(p, 1.0) : fwd * vec4(s, toNdcZ(d), 1.0);
}

Candidate reproject(sampler2D colorTex, sampler2D depthTex, vec2 size, mat4 fwd, mat4 fwdRot, mat4 inv, mat4 invRot,
        mat4 srcInv, mat4 tgt, int ents, int entCount, vec4 srcRect, vec2 t, vec2 startShift, bool fill) {
    // Rotation-only inverse is independent of depth and is the best first guess for large camera turns. For a moving
    // object, start where the object was: shifted back by its own screen motion.
    vec4 g = invRot * vec4(t - startShift, 0.5, 1.0);
    vec2 s0 = g.w > EPS_W ? g.xy / g.w : t;
    vec2 s = s0;
    float d = 0.0;
    float err = 1e4;
    float zt = 0.0;
    bool moved = false;
    int movedId = -1;
    // Each step measures the residual of the current guess (that's the convergence check, for free) and stops as
    // soon as it is below a twentieth of a pixel; smooth surfaces converge in one or two steps.
    for (int i = 0; i <= uIterations; i++) {
        d = fetchDepth(depthTex, size, s);
        vec4 p = forwardMapMoving(fwd, fwdRot, srcInv, tgt, ents, entCount, srcRect, s, d, moved, movedId);
        if (p.w <= EPS_W) {
            err = 1e4;
            break;
        }
        zt = p.z / p.w;
        err = length((p.xy / p.w - t) * uOutSize * 0.5);
        if (err < 0.05 || i == uIterations) break;
        vec4 q = (d <= 0.0 ? invRot : inv) * vec4(t, zt, 1.0);
        if (q.w <= EPS_W) break;
        if (moved) {
            // Residual step: the static inverse maps the target error back to a source correction.
            vec4 q0 = inv * vec4(p.xy / p.w, zt, 1.0);
            if (q0.w <= EPS_W) break;
            s += q.xy / q.w - q0.xy / q0.w;
        } else {
            s = q.xy / q.w;
        }
    }

    if (fill && uBackgroundSearch == 1 && err > ERR_GOOD) {
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
            float dk = fetchDepthNearest(depthTex, size, sk);
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
    c.z = zt;
    c.moved = moved;
    c.movedId = movedId;
    c.s = s;
    return c;
}

float confidence(float err) {
    return 1.0 - smoothstep(ERR_GOOD, ERR_BAD, err);
}

// The solver starts from where static scenery would be, so it finds the ground a moving object is about to cover rather
// than the object. Inside each moving entity's on-screen area, search again starting from the object, and keep the
// object if it really lands here and is in front.
Candidate reprojectAll(sampler2D colorTex, sampler2D depthTex, vec2 size, mat4 fwd, mat4 fwdRot, mat4 inv, mat4 invRot,
        mat4 srcInv, mat4 tgt, int ents, int entCount, vec4 srcRect, vec4 tgtRect, vec2 t) {
    Candidate c = reproject(colorTex, depthTex, size, fwd, fwdRot, inv, invRot, srcInv, tgt, ents, entCount, srcRect, t, vec2(0.0), true);
    // Already on a moving object that really lands here: nothing to look for.
    if (entCount == 0 || !inRect(t, tgtRect) || (c.moved && c.err < ERR_GOOD)) return c;
    for (int i = 0; i < entCount; i++) {
        if (!inRect(t, uEnt[ents + i * 6 + 4])) continue;
        vec2 shift = uEnt[ents + i * 6 + 5].xy;
        Candidate e = reproject(colorTex, depthTex, size, fwd, fwdRot, inv, invRot, srcInv, tgt, ents, entCount, srcRect, t, shift, false);
        // Only this entity counts: a search started for one object can land on another one that is hidden behind it.
        if (e.movedId == i && confidence(e.err) > 0.5 && (confidence(c.err) < 0.5 || e.z > c.z)) {
            c = e;
        }
    }
    return c;
}

// A see-through surface (water, stained glass, ice, clouds) is in front of the scenery at this source position.
bool seeThrough(sampler2D depthTex, sampler2D opaqueTex, vec2 size, vec2 s) {
    float d = fetchDepthNearest(depthTex, size, s);
    float o = fetchDepthNearest(opaqueTex, size, s);
    return d > o * 1.0005 + 1e-7; // reversed-Z: the surface is nearer = larger
}

void main() {
    vec2 t = vUv * 2.0 - 1.0;

    vec3 world;
    float shownErr;
    bool shownMoved = false;
    if (uUseA == 1) {
        // Evaluate the temporally dominant frame first; the other one only if it carries real weight or the first
        // could not reconstruct this pixel (disocclusion fill). Halves the cost for most pixels near t = 0 or 1.
        bool aFirst = uWeightA >= 0.5;
        float wFirst = aFirst ? uWeightA : 1.0 - uWeightA;
        Candidate c1 = aFirst
            ? reprojectAll(uColorA, uDepthA, uSizeA, uFwdA, uFwdRotA, uInvA, uInvRotA, uSrcInvA, uTgtA, 0, uEntCountA, uSrcRectA, uTgtRectA, t)
            : reprojectAll(uColorB, uDepthB, uSizeB, uFwdB, uFwdRotB, uInvB, uInvRotB, uSrcInvB, uTgtB, ENT_B, uEntCountB, uSrcRectB, uTgtRectB, t);
        float conf1 = confidence(c1.err);
        world = c1.color;
        shownErr = c1.err;
        shownMoved = c1.moved;
        // Near moving objects both frames are always consulted: one of them may hold the object, the other what's
        // behind it.
        bool nearMoving = (uEntCountA > 0 && inRect(t, uTgtRectA)) || (uEntCountB > 0 && inRect(t, uTgtRectB));
        if (1.0 - wFirst > 0.02 || conf1 < 0.999 || nearMoving) {
            Candidate c2 = aFirst
                ? reprojectAll(uColorB, uDepthB, uSizeB, uFwdB, uFwdRotB, uInvB, uInvRotB, uSrcInvB, uTgtB, ENT_B, uEntCountB, uSrcRectB, uTgtRectB, t)
                : reprojectAll(uColorA, uDepthA, uSizeA, uFwdA, uFwdRotA, uInvA, uInvRotA, uSrcInvA, uTgtA, 0, uEntCountA, uSrcRectA, uTgtRectA, t);
            // See-through surfaces: both frames show the surface *and* what's behind it, but carry only the surface's
            // depth. Reproject again with the depth of what's behind, and keep whichever version the two frames agree
            // on better: the background through a window, or the surface itself (waves, glass edges).
            if (uOpaqueA == 1 && uOpaqueB == 1) {
                vec2 sA = aFirst ? c1.s : c2.s;
                vec2 sB = aFirst ? c2.s : c1.s;
                if (seeThrough(uDepthA, uOpaqueDepthA, uSizeA, sA) || seeThrough(uDepthB, uOpaqueDepthB, uSizeB, sB)) {
                    Candidate oA = reprojectAll(uColorA, uOpaqueDepthA, uSizeA, uFwdA, uFwdRotA, uInvA, uInvRotA, uSrcInvA, uTgtA, 0, uEntCountA, uSrcRectA, uTgtRectA, t);
                    Candidate oB = reprojectAll(uColorB, uOpaqueDepthB, uSizeB, uFwdB, uFwdRotB, uInvB, uInvRotB, uSrcInvB, uTgtB, ENT_B, uEntCountB, uSrcRectB, uTgtRectB, t);
                    vec3 dSurface = abs(c1.color - c2.color);
                    vec3 dBehind = abs(oA.color - oB.color);
                    float confBehind = min(confidence(oA.err), confidence(oB.err));
                    if (confBehind > 0.5 && dot(dBehind, vec3(1.0)) + 0.02 < dot(dSurface, vec3(1.0))) {
                        c1 = aFirst ? oA : oB;
                        c2 = aFirst ? oB : oA;
                        conf1 = confidence(c1.err);
                        world = c1.color;
                        shownErr = c1.err;
                        shownMoved = c1.moved;
                    }
                }
            }
            float conf2 = confidence(c2.err);
            float w1 = wFirst * conf1;
            float w2 = (1.0 - wFirst) * conf2;
            vec3 diff = abs(c1.color - c2.color);
            bool conflict = max(diff.r, max(diff.g, diff.b)) > 0.12;
            if ((c1.moved || c2.moved) && conf1 > 0.5 && conf2 > 0.5 && abs(c1.z - c2.z) > 0.02 * max(c1.z, c2.z)) {
                // Two valid points land here: a moving object and what is behind it. The nearer one is visible.
                if (c1.z > c2.z) w2 = 0.0; else w1 = 0.0;
            } else if (conflict) {
                // Things that changed on their own (animations, particles, objects we can't track) disagree between
                // the two frames even where the reprojection is perfect. Cross-fading would show two half-transparent
                // copies, so snap to the temporally nearer frame (c1) instead.
                w2 *= 0.02;
            }
            float sum = w1 + w2;
            if (sum > 1e-4) {
                world = (c1.color * w1 + c2.color * w2) / sum;
            } else {
                // Neither frame is confident: take whichever reprojection is less wrong.
                world = c1.err <= c2.err ? c1.color : c2.color;
            }
            shownErr = min(c1.err, c2.err);
            shownMoved = w1 >= w2 ? c1.moved : c2.moved;
        }
    } else {
        Candidate cb = reprojectAll(uColorB, uDepthB, uSizeB, uFwdB, uFwdRotB, uInvB, uInvRotB, uSrcInvB, uTgtB, ENT_B, uEntCountB, uSrcRectB, uTgtRectB, t);
        world = cb.color;
        shownErr = cb.err;
        shownMoved = cb.moved;
    }

    if (uDebug == 1) {
        float c = confidence(shownErr);
        world = mix(world, vec3(1.0, 0.0, 1.0), 0.6 * (1.0 - c));
        if (shownMoved) world = mix(world, vec3(0.0, 0.3, 1.0), 0.4);
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
