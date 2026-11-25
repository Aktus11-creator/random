<p align="center"><img src="docs/banner.png" alt="FrameGen by Aktus1_1" width="730"></p>

# FrameGen — frame generation for Minecraft 26.3 (Fabric)

*By **Aktus1_1***


FrameGen makes camera motion run at your monitor's refresh rate even when Minecraft renders far fewer real frames.
It generates the in-between frames by **reprojecting** the last real frames, using their depth buffers and the exact
camera matrices they were rendered with. It does not guess with optical flow.

```
game (render thread)      ██ real ██          ██ real ██          ██ real ██        e.g. 60 fps
presenter (own thread)    ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌ ▌         e.g. 144 fps, evenly paced
```

## Features

- **True decoupled presentation.** A dedicated presenter thread with its own OpenGL context owns the window and
  presents at display refresh. The game renders offscreen at its own pace, so generated frames are evenly spaced.
  If frame generation just slotted extra frames into the game's own loop, they would bunch up next to the real
  frames and look no smoother.
- **Depth-aware reprojection.** Camera rotation, translation (parallax), FOV changes and view bobbing are all
  reconstructed geometrically. An iterative inverse-warp solver with edge-aware depth sampling finds, for each output
  pixel, the source pixel that lands there. It fills disocclusions with background, not by smearing foreground edges.
- **Two modes**
  - *Extrapolate* (default): predicts forward from the newest frame. It adds no input delay but can briefly
    overshoot when you stop turning.
  - *Interpolate*: blends between the two real frames around the displayed moment. It has the fewest artifacts
    but adds about one real frame of input delay.
- **Sharp HUD and hand.** The world, the first-person hand and the HUD are captured as separate layers. Only the
  world is reprojected, so the hotbar, chat, crosshair and hand never wobble or smear. The vignette (multiply blend)
  and the crosshair (invert blend) are replayed exactly over the generated image.
- **No visible change to the vanilla image.** Each real frame is rebuilt pixel-exactly in Minecraft's main target,
  so F2 screenshots and other mods see the normal frame.
- **Safe fallbacks.** Menus, loading screens and post effects (spectator mob views, entity outlines) are handled.
  If the presenter ever fails, the game falls back to vanilla presentation automatically.
- **Never slower than needed.** If the game already renders about one real frame per display refresh, FrameGen
  switches to *native* presentation. It shows the real frames as they are, with no warping and no added latency,
  and resumes generating as soon as the game falls behind.
- In-game settings screen (also via Mod Menu), hotkeys, and an optional stats line.

## Requirements

| | |
|---|---|
| Minecraft | 26.3 |
| Fabric Loader | ≥ 0.20.1 |
| Fabric API | required |
| Java | 25 |
| Graphics API | **OpenGL** (the default). Vulkan isn't supported yet; FrameGen turns itself off and says so in the stats line and settings screen. |
| Mod Menu | optional |
| Sodium / Nvidium | optional, supported (see below) |

## Install

1. Install Fabric Loader for 26.3 and Fabric API.
2. Drop `framegen-<version>.jar` into `.minecraft/mods`.
3. Optional, for the best results: turn **V-Sync on**. The presenter then paces itself to the display. Leave
   *Max Framerate* at "Unlimited"; FrameGen limits the real frame rate itself (see *Real FPS Limit*).

## Controls

| Key | Action |
|---|---|
| `F8` | Toggle frame generation |
| *(unbound)* | Switch Interpolate / Extrapolate |
| *(unbound)* | Toggle the stats line |
| *(unbound)* | Open the FrameGen settings |

Rebind them under *Options → Controls → Key Binds → FrameGen*.

## Settings

| Option | What it does |
|---|---|
| Frame Generation | Master switch. When off, frames pass straight through. |
| Mode | Interpolate (smoothest) or Extrapolate (lowest latency). |
| Quality | Solver iterations per pixel and disocclusion background search. Low / **Medium** (default) / High / Ultra. The solver stops early once a pixel has converged, so higher tiers mostly cost more around edges. |
| Real FPS Limit | **Display rate** (default) means the game never renders more real frames than your display shows. FrameGen only fills in when the game drops below that. *1/2 display* and lower trade real frames for GPU headroom; use them only if the game stutters. *Game limit* uses your Max Framerate. |
| Output FPS | Presentation rate when V-Sync is off ("Display" = refresh rate). |
| Sharp HUD and Hand | Keeps HUD and hand out of the reprojection. Disable only if another mod's HUD looks wrong. |
| Max Prediction | How far past the newest real frame the camera may be predicted. |
| Low Latency Present | Waits for each present to complete. This gives lower latency and steadier pacing on most drivers. |
| Show Stats | `FrameGen 144 fps (real 72) | Interpolate | delay 21 ms | present 0.3 ms`, or `FrameGen native` when the game already keeps up. |
| Debug View | Tints pixels the reprojection couldn't reconstruct confidently in magenta. |

The settings are stored in `config/framegen.json`.

## How it works

```
 render thread (Minecraft's GL context)                    presenter thread (shared GL context, owns the window)
 ─────────────────────────────────────                    ─────────────────────────────────────────────────────
 extract  → sample camera, timestamp
 level    → [A] copy world color + depth into a frame slot,
               clear color to transparent
 hand     → renders onto transparent target
          → [B] copy hand layer, put world back underneath
 post fx  → [B2] if post effects/outlines drew over the
               hand, bake everything into the world layer
 GUI      → [C] clear to transparent; vignette + crosshair
               are intercepted (they blend with the world)
 present  → [D] compose hand+GUI into a premultiplied
               overlay, rebuild the exact vanilla frame in
               the main target, fence, publish ──────────────▶ ingest frames whose GPU fence has signalled
                                                             playback clock picks the time to show
                                                             (interp: render latency + 1 frame in the past)
                                                             pose = slerp/lerp between the bracketing frames
                                                             warp both frames into that pose (depth-aware),
                                                             blend by confidence + time, × vignette,
                                                             + overlay, replay crosshair (invert blend)
                                                             swap (V-Sync)  →  release slots with fences
```

- Minecraft 26.3 renders through the new `renderpearl` abstraction and SDL3. FrameGen replaces the window surface on
  the OpenGL backend. Minecraft's context stays on its hidden utility window and only ever renders offscreen, and a
  second context in the same share group is current on the visible window in the presenter thread. Frames are
  handed over through a lock-protected exchange with GL fence objects in both directions, so neither thread ever
  blocks on the other's GPU work.
- Reprojection uses the full homogeneous transform
  `M = P_target · R_target · T(cam_source − cam_target) · (P_source · R_source)⁻¹`, built in double precision. Sky
  pixels (cleared reversed-Z depth) use the rotation-only variant, so they behave as if infinitely far away.
- Window-space depth is affine across planes, so depth is interpolated bilinearly within surfaces (exact on
  Minecraft's flat faces) and by nearest texel across discontinuities.

### Measured reprojection accuracy

The dev build has a self-test (`-Dframegen.debug=true`). It reprojects real frame *A* into the exact camera of the
next real frame *B* and compares the result with *B*:

| Camera motion between frames | Reprojected vs. B | Reusing A vs. B | Confidently reconstructed |
|---|---|---|---|
| 9° turn | 33.5 dB | 21.4 dB | 89% of pixels at 40.8 dB |
| 15.6° turn | 33.0 dB | 21.4 dB | 87.5% at 44.8 dB |
| 0.83 blocks strafe | 26.1 dB | 19.9 dB | 62.8% at 34.6 dB |

The remaining pixels are newly revealed screen edges and disoccluded areas. These numbers come from a software
renderer running at only 2–6 real fps, so the motion between frames was 5–10× larger than in normal play. The
depth decoding was also checked against Minecraft's own crosshair raycast (1.620 vs 1.620 blocks).

## Sodium and Nvidium

FrameGen is compatible with **Sodium 0.9.3+ for 26.3** and **Nvidium 0.4.4 for 26.3**:

- **Settings in Sodium's menu.** With Sodium installed, *Video Settings* gets a **FrameGen** page next to Sodium's
  and Nvidium's own pages. The tooltip on *Frame Generation* shows live status. FrameGen's own screen (Mod Menu /
  keybind) keeps working too, and both edit the same `config/framegen.json`.
- **Sodium's driver checks still run.** Sodium hooks `GlSurface.present` for its one-time driver checks and its
  injected-overlay detection. FrameGen's presenter replaces that call, so FrameGen finds every mod's
  `GlSurface.present` injection and replays it on the render thread for each presented frame. It reads the
  injection target from the mixin's own bytecode, never by guessing names.
- **No-error GL contexts.** Sodium makes Minecraft's context a `KHR_no_error` context on supported drivers. GL only
  allows sharing between contexts whose no-error state matches, so FrameGen reads Minecraft's actual context flags
  and creates the presenter context to match.
- **Sodium / Nvidium terrain is reprojected exactly like vanilla terrain.** Both draw into Minecraft's main
  framebuffer with the vanilla reversed-Z convention and the same projection matrix FrameGen captures. Nvidium
  draws with `GL_GEQUAL` mesh-shader passes using Sodium's copy of that matrix. Nvidium's raw GL state changes stay
  in Minecraft's context and never reach the presenter's.

Verified in the dev environment with Sodium 0.9.3 + Nvidium 0.4.4-beta8:
- Everything loads together with no mixin conflicts.
- The FrameGen page appears in Sodium's settings and applies changes.
- Sodium's checks run.
- Generation works on Sodium's terrain renderer: an 11.4° turn reconstructs 90.4% of pixels at 46.9 dB.
- The presenter shares correctly with a forced no-error context.

Nvidium only enables itself on NVIDIA GPUs with mesh shaders. The test machine has none, so there Nvidium stayed
disabled; its rendering path was checked by code review, not by running it.

## Changelog

**1.3.1**
- **Extrapolate is now the default mode**, and existing configs are switched over. It adds no input delay on top of
  the game's own: the presented camera is exactly as old as a real frame when it reaches the screen. Interpolate
  remains available for the fewest artifacts at about one real frame of added delay.
- Extrapolation's timing now uses the average render latency instead of a cautious worst-case estimate.
- With Extrapolate, switching between generating and native presentation no longer changes the delay, so the
  view doesn't snap when the frame rate hovers around the display rate.

**1.3.0**
- **Supersampling / resolution-scaling mods are supported.** These mods render into a main target larger than
  the window, then swap in a native-size output before the GUI and present that. FrameGen now follows the swap:
  - the world layer is box-filtered down to native resolution;
  - the HUD is captured from the output target;
  - real frames are rebuilt from the mod's own downscaled image, so its quality is kept exactly.
  This fixes menus showing a zoomed-in, GUI-less image when such a mod is installed. Frame generation keeps
  working while supersampling: a 4.5° turn reconstructs 96% of pixels at 50.9 dB.
- FrameGen always presents the texture handed to the swapchain blit. If a mod redirects the frame in a way
  FrameGen can't follow, frames are shown unchanged and generation pauses. The stats line and the log say so.

**1.2.1**
- Frames are now placed on screen 1:1 exactly like vanilla's swapchain blit. If the game frame and the window ever
  differ in size, the image is no longer stretched (zoomed). The log records both sizes when that happens.
- The log records window size, pixel density, display scale and fullscreen state when the surface is configured,
  to help diagnose display issues.

**1.2.0**
- The real frame rate is no longer capped at half the display rate by default, which made fast PCs render far
  fewer real frames than they could. The new default is the display rate, and existing configs are migrated.
- New native mode: no generation while the game already keeps up with the display.
- The reprojection shader is about 5× cheaper at the same quality: early-exit solver, `textureGather` depth
  fetches, and the second frame is skipped when it doesn't contribute. Measured as 24.1 → 4.8 ms/frame on the
  software test GPU, while still reconstructing 91% of pixels at 46 dB for a 10° turn. The default quality tier is
  now Medium.
- The log now warns if the presenter stops keeping up or GPU frames never finish, to help diagnose freezes.

## Limitations

- **OpenGL only.** On the Vulkan backend FrameGen stays inactive.
- Entities, particles and animated textures update at the real frame rate. Without motion vectors only the camera
  is reconstructed between real frames. In interpolation mode, conflicting pixels snap to the nearer real frame
  instead of ghosting.
- The edges of the screen that just came into view have no source data and are filled by edge stretching. This is
  hard to notice at normal frame rates.
- Shader packs (Iris) and other mods that replace the window surface are untested. Sodium, Nvidium and
  supersampling mods that swap the main render target are supported (see above and the changelog).

## Testing status

Everything above was developed and verified on Linux/X11, on Mesa's llvmpipe software renderer under Xvfb. That
covers the presenter thread, shared contexts, passthrough and generation, HUD/hand layering, F2 screenshots, menus,
toggling, mode switching and clean shutdown. It has **not yet been run on real GPU drivers** (NVIDIA / AMD / Intel)
or on Windows, macOS or Wayland. The design relies only on standard shared-context OpenGL 3.3 and SDL3, but
driver-specific issues are possible. If you hit one, the game falls back to vanilla presentation;
`-Dframegen.disable=true` turns the mod off entirely.

## Building

```sh
./gradlew build            # → build/libs/framegen-<version>.jar  (needs JDK 25)
./gradlew runClient        # dev client (adds -Dframegen.debug=true: presenter stats + self-test in the log)
```

Useful JVM flags:

- `-Dframegen.disable=true`: don't install the presenter at all (vanilla presentation).
- `-Dframegen.debug=true`: log presenter statistics and run the reprojection self-test every few seconds.

## License

MIT © 2026 Aktus1_1
