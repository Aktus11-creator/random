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
  - *Interpolate* (default): blends between the two real frames around the displayed moment. It's the smoothest,
    especially at low frame rates, and adds about one real frame of latency.
  - *Extrapolate*: predicts forward from the newest frame. It adds no latency but is less smooth at low frame
    rates and can briefly overshoot when you stop turning.
- **Stable water and glass.** What you see through water, stained glass and ice is reprojected with its own depth,
  so it no longer slides around behind the surface when you move.
- **Responsive camera.** Generated frames turn with your newest mouse movement (real input, not a prediction), so
  turning reacts sooner while the world stays as smooth as Interpolate makes it.
- **Smooth mobs, players and items.** Moving entities are moved with their own motion in generated frames (walking,
  flying, riding, turning), not just the camera.
- **No stretched screen edges.** The world is rendered with a small margin around the screen (*overscan*), so
  when you turn, the edge that comes into view is real scenery instead of stretched edge pixels. Real frames are
  cropped back to the normal view.
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
| Overscan | Extra field of view rendered around the screen, per side: Off / 4% / **8%** (default) / … / 24%. Generated frames use it for screen edges coming into view while you turn. Real frames look the same. Costs GPU time: 8% renders about 35% more world pixels. Skipped automatically while post effects or glowing entities are on screen. |
| Stable Water and Glass | Reprojects what's seen through water, stained glass and ice with its own depth where that fits the real frames better (on by default). |
| Responsive Camera | Turns the camera in generated frames with your newest mouse input instead of the interpolated rotation (on by default). First person only; off while riding, sleeping or with Cinematic Camera. |
| Smooth Moving Objects | Moves mobs, players, items, minecarts and boats in generated frames (on by default). Limb animations still update at the real frame rate. |
| Sharp HUD and Hand | Keeps HUD and hand out of the reprojection. Disable only if another mod's HUD looks wrong. |
| Max Prediction | Extrapolate mode only: how far past the newest real frame the camera may be predicted, in real frames. It has no effect with Interpolate. |
| Low Latency Present | Waits for each present to complete. This gives lower latency and steadier pacing on most drivers. |
| Show Stats | `FrameGen 144 fps (real 72) | Interpolate | delay 21 ms, turning 6 ms | present 0.3 ms` (*turning* is the age of the mouse input the camera was turned with), or `FrameGen native` when the game already keeps up. |
| Debug View | Tints pixels the reprojection couldn't reconstruct confidently in magenta, and pixels moved with an entity in blue. |

The settings are stored in `config/framegen.json`.

## How it works

```
 render thread (Minecraft's GL context)                    presenter thread (shared GL context, owns the window)
 ─────────────────────────────────────                    ─────────────────────────────────────────────────────
 extract  → sample camera, timestamp
 level    → [O] rendered into a larger target with a wider
               projection (overscan margin)
          → [A] copy world color + depth into a frame slot,
               crop the normal view back into the main
               target, clear color to transparent
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
- Overscan multiplies the level's projection by `diag(w/W, h/H, 1, 1)` in clip space and renders into a target
  that is `W × H` instead of `w × h`. The centre `w × h` pixels are then exactly the normal image (pixel centres
  line up), and the culling frustum is widened to match. The presenter reads source frames with this wider
  projection and produces output with the normal one.
- See-through surfaces: water, stained glass, ice and clouds write their own depth, so the final depth buffer would
  move the scenery behind them as if it were painted on the surface. Right before Minecraft draws them, FrameGen
  copies the depth buffer (`glCopyImageSubData`, no GL state touched) and keeps it next to the normal depth. Where
  the two differ, the presenter reprojects with both and keeps the version on which the two real frames agree
  better. That is usually the scenery behind the surface, and sometimes the surface itself (waves, glass edges).
- Responsive camera: Minecraft reads input once per real frame. While a frame renders, the render thread (SDL's
  main thread) pumps SDL's event queue a few times (between level render passes, before the GUI, at present) and
  peeks at the mouse motion waiting there without removing it, so the game still gets every event. That motion,
  put through the game's own sensitivity formula and added to the frame's camera rotation, is the rotation the next
  frame will have. The presenter plays these samples back one sample-gap late, interpolating between them, and uses
  that rotation for generated frames. The world (position, entities) is still interpolated as before.
- Moving entities: each real frame records every rendered entity's id, position, velocity and body yaw, plus a box
  around it that turns with the body. For a generated frame, the presenter places each entity where it is at that
  moment (interpolated between the two real frames, or by its velocity) and the shader moves every source pixel
  inside the box with it (translation plus the body turn). Because the solver normally starts from where static
  scenery would be, pixels in an entity's on-screen area also get a second search starting from the entity, and of
  two valid answers the nearer one wins. Entities that jump or turn too far between two real frames fall back to
  the old behaviour (the nearer real frame is shown).
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

**1.8.0**
- **Water, stained glass and ice no longer make the world behind them wobble.** Before, what you saw through them
  was moved as if it were painted on the surface, so it slid around when you walked or strafed. New *Stable Water
  and Glass* setting (on by default). In the test, strafing past a stained-glass window, generated frames came out
  1–3 dB closer to the real frames.
- Only applies with the default transparency mode. With *Improved Transparency* on, Minecraft draws those
  surfaces differently and the old behaviour remains.

**1.7.0**
- **Turning reacts much sooner in Interpolate.** New *Responsive Camera* setting (on by default): generated frames
  turn with your newest mouse movement instead of waiting for the next real frame. It uses your real input, not
  a prediction, so it never overshoots when you stop. Walking, the world and mobs stay interpolated and as smooth
  as before.
- The stats line shows the new *turning* delay next to the world delay.
- First person only. Off while riding (boats and horses turn you themselves), sleeping, or with Cinematic Camera.
- Your mouse input reaches the game exactly as before. The test turned to the same angle with the setting on
  and off.

**1.6.0**
- **Mobs, players, items, minecarts and boats now move smoothly** in generated frames, not just the camera.
  Before, anything that moved on its own only moved on real frames. New *Smooth Moving Objects* setting (on by
  default).
- Walking, flying, riding and turning are smoothed. Limb animations (legs swinging) and particles still update at
  the real frame rate.
- Works with entity model mods (EMF/ETF, 3D Skin Layers, player animations): FrameGen uses each entity's position
  and hitbox, not its model.

**1.5.0**
- **No more stretched screen edges when you turn fast.** FrameGen now renders a little more of the world than
  fits on screen (new *Overscan* setting, 8% per side by default). Generated frames take the edge that comes
  into view from that margin instead of stretching the last column of pixels. Real frames are cropped back, so
  they look the same as before. In the test, a 5° turn between real frames now reconstructs 98% of the screen
  (was 89–90%).
- Works with supersampling mods: the margin scales with their render resolution, and they still get their
  normal image.
- Costs GPU time (8% ≈ 35% more world pixels). If your FPS drops too much, lower it to 4% or turn it off.

**1.4.2**
- **Fixes the invisible first-person hand** (and other silently missing layers) on some setups. Minecraft 26.3's
  OpenGL backend has a bug: reading back a depth texture leaves its shared read framebuffer reading nothing, so
  every later colour copy through it copies nothing. If any mod read depth during a frame, FrameGen's hand copy
  came out empty and the hand was erased from the real frame. FrameGen now fixes that state after depth readbacks
  (which helps other mods too), and does all of its own colour copies as render passes, so they no longer depend
  on that shared state.
- New **F9 debug snapshot**. It saves every FrameGen layer of one frame as PNGs, plus an info file, to
  `.minecraft/framegen-debug/`, for bug reports.

**1.4.1**
- Reverted the 1.3.1 latency changes. Interpolate is the default again (configs switched to Extrapolate by
  1.3.1 are switched back), and Extrapolate uses its original timing. Extrapolation wasn't smooth at low frame
  rates.

**1.4.0**
- **First-person hand no longer disappears.** The hand layer used to be detected from alpha only, and with some
  mod combinations (entity model/texture mods, 3D skin layers, hand-size mods) parts of the arm are drawn without
  writing alpha, so the hand got dropped. Coverage now also comes from the hand's depth, which Minecraft clears
  right before drawing the hand, so it's reliable whatever writes or doesn't write alpha.
- **Works together with Supersample's dynamic mode.** That mode raised the resolution until the game reached
  the display's refresh rate, leaving FrameGen nothing to generate, so the camera wasn't boosted. While FrameGen
  is on, Supersample is now asked to target half the output rate, and FrameGen doubles the camera back to the
  full refresh rate. This compat hook is only applied when Supersample is installed.
- New, simpler flat icon.

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
- Entities move with their position and body turn; limb animations, head turns, particles and animated textures
  update at the real frame rate. Parts of a model that stick far out of its hitbox may not move with it. In
  interpolation mode, conflicting pixels snap to the nearer real frame instead of ghosting.
- With *Improved Transparency* (Video Settings), the see-through fix doesn't apply. Effects drawn without depth
  (rain, snow, particles) are still reprojected with the depth behind them.
- With Responsive Camera, very fast flicks can turn further ahead of the newest real frame than the overscan margin
  covers, and some edge stretching returns for those moments. Raising Overscan helps.
- Screen edges that come into view are taken from the overscan margin. A turn larger than the margin between two
  real frames (or overscan off) still shows some edge stretching. Overscan sits out frames with post effects
  (e.g. spectating a creeper) or glowing entities on screen.
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
