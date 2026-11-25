# FrameGen: handoff for the next Claude session

You are taking over **FrameGen**, a Fabric mod for **Minecraft 26.3** that does frame generation. The previous
session ran out of context. This file is everything you need. Read it fully, then read the code it points to
before changing anything.

- **Repo:** `aktus11-creator/random`. **Work branch: `claude/magical-euler-cfwal6`.** Commit and push there; never
  open a PR unless asked.
- **Author / user:** Aktus1_1. They play on their own Windows PC, and you can't reach their machine. They test
  each release and report back in short, casual messages. Keep replies short and plain, and lead with what
  changed and the download link.
- **Current version:** see `gradle.properties` and the changelog in `README.md`. (Branch history: 1.0–1.4.2 were
  made on `claude/epic-cori-5h5iyh`; from 1.5.0 on, work continues on `claude/magical-euler-cfwal6`.)
- **Your task:** implement the four improvements in "The work" below, one release per improvement. Test each in
  the dev environment before releasing.

---

## 1. Environment setup (the cloud container is fresh, so do this first)

```sh
# JDK 25 (the system JDK is 21; MC 26.3 needs 25)
mkdir -p /opt/jdk25 && cd /opt/jdk25 && \
curl -sL -o jdk.tgz "$(curl -s 'https://api.adoptium.net/v3/assets/latest/25/hotspot?os=linux&architecture=x64&image_type=jdk' | python3 -c 'import json,sys;print(json.load(sys.stdin)[0]["binary"]["package"]["link"])')" && \
tar xzf jdk.tgz --strip-components=1 && rm jdk.tgz
export JAVA_HOME=/opt/jdk25

# Virtual display for running the real game (Mesa llvmpipe software GL 4.5). -ac is required.
Xvfb :99 -ac -screen 0 1280x720x24 +extension GLX +render -noreset &

cd /home/user/random
./gradlew --no-daemon genSources   # optional: decompiled MC sources (vineflower) in .gradle/loom-cache
./gradlew --no-daemon build        # -> build/libs/framegen-<version>.jar
```

- **Decompiled Minecraft sources** are essential: 26.3 is unobfuscated but very new. After `genSources`, unzip
  `.gradle/loom-cache/minecraftMaven/net/minecraft/*/26.3/*-sources.jar` into a scratch dir and grep it. Don't
  guess APIs from memory; 26.3 renamed and restructured a lot (see section 3).
- **Network:** Fabric maven, Modrinth (api + cdn + maven), Adoptium, GitHub raw, services.gradle.org and
  Mojang all work.
- **The dev run** (`./gradlew runClient`) loads Sodium 0.9.3 and Nvidium 0.4.4-beta8 (via `localRuntime`, see
  `build.gradle`) and Mod Menu. Nvidium disables itself on llvmpipe (it needs NVIDIA mesh shaders).
- **Dev JVM flags,** already set in `build.gradle` → `loom.runs.client`:
  - `-Dframegen.dev.noSrgbVisual=true`: Xvfb has no sRGB GLX visuals; without this the window can't be
    created. Handled by `GlBackendMixin`; players never set it.
  - `-Dframegen.debug=true`: the presenter logs stats every 2 s (`[presenter] out X fps, real Y fps, …`) and
    runs a **reprojection self-test** every ~3 s (`[self-test] … PSNR …`, `[bench] … ms/frame`).
- **A test world already exists** at `run/saves/fgtest` (creative). If not (fresh clone), create one through the
  UI and rename the save folder to `fgtest`. Then: `devtools/runmc.sh /tmp/r.log --quickPlaySingleplayer fgtest`.
- **Shortcuts** (added in 1.5.0): `devtools/startmc.sh <log>` starts the client in `fgtest` and waits until
  frames are generated; `devtools/setcfg.py key=value…` edits `run/config/framegen.json` (do it while the game is
  stopped); `devtools/turntest.sh <log> [dx] [steps]` turns the camera and prints the self-test lines;
  `devtools/burst.sh <prefix> [dx] [n]` grabs screenshots during a turn. `xin.py type:` now handles shifted
  characters, e.g. `"type:/summon pig ^ ^ ^4 {Glowing:1b}"` after `key:t`. `/tick freeze` makes the world static
  for pixel comparisons (screenshots with F8 on vs off).
- **Driving the game:** `python3 devtools/xin.py <cmds…>` sends X input via XTest. Commands: `click:x:y`,
  `key:Escape`, `key:F9`, `down:w`/`up:w`, `rel:dx:dy:count` (mouse look), `sleep:s`, `type:text`. The game
  window is 854×480 at screen offset (213,120), so screen coords = window coords + (213,120).
  Screenshots: `DISPLAY=:99 import -window root -crop 854x480+213+120 out.png`.
- **Waiting:** don't `sleep` long in the foreground (it's blocked). Use
  `until grep -q "<pattern>" log; do sleep 2; done`. A typical "in world and generating" wait:
  `until [ $(grep -c "\[presenter\].*generating=true" log) -ge 3 ]; do sleep 2; done`.
  Beware: `grep crash` matches the mod id `fabric-crash-report-info`. Match `---- Minecraft Crash Report`.
- **llvmpipe numbers:** 2–10 real fps and 20–100 output fps. Only *relative* numbers mean anything; the user has an RTX 2070.
- **Stop the game:** `pkill -f KnotClient`, then wait until `pgrep -f devlaunchinjector` is empty.

## 2. The user's setup (it matters for every decision)

- Windows, **NVIDIA RTX 2070** (driver 610.88), **2560×1440 fullscreen, 144 Hz**. V-Sync was off (the surface
  log said IMMEDIATE); we recommended turning it on.
- Prism Launcher, **181 mods**: Sodium 0.9.2, Nvidium (active on their GPU), ImmediatelyFast, Entity Model
  Features, Entity Texture Features, 3D Skin Layers, Player Animation Library, Xaero's Minimap, Litematica,
  Dynamic FPS, Simple Voice Chat, …
- **Their own mods:**
  - **Supersample** (`com.novotnya.supersample`, not public): dynamic supersampling. Details in section 4.
  - **Hand Size** (`cz.headers.handsize`): only scales and translates held items in
    `FirstPersonHandsAndItemsRenderer.submitArmWithItem` via the PoseStack. It's harmless to FrameGen.
- Real FPS ≈ 110 at 32 render distance with supersampling. With FrameGen on, Supersample is asked to target
  ~72 and FrameGen doubles the camera to 144.
- **Their preferences, learned the hard way:**
  - **Interpolate** is the default and the mode they want. **Extrapolate looked "SO bad"** at their frame rates;
    don't push it.
  - They asked us to **undo** the delay-reduction changes. Any latency work must not make low-FPS motion less
    smooth.
  - They **can't download files from chat**. Always give the GitHub raw link:
    `https://github.com/Aktus11-creator/random/raw/claude/magical-euler-cfwal6/release/framegen-<version>.jar`.

## 3. Architecture (read the code: `src/client/java/dev/framegen/`)

Minecraft 26.3 uses **SDL3** (not GLFW) and a new GPU abstraction **renderpearl** (`com.mojang.renderpearl.*`)
with **OpenGL** and **Vulkan** backends. OpenGL is the default. The renderer is split into **extract** (game
state → render state) and **render**. Depth is **reversed-Z** (cleared to 0 = far) and `glClipControl` is
ZERO_TO_ONE when available.

**Presentation is decoupled:**
- `engine/FrameGenEngine.wrapSurface` replaces Minecraft's `GpuSurface` (OpenGL only) with `FrameGenSurface`.
  It creates a **second GL context** in Minecraft's share group, made current on the visible window by the
  **`Presenter` thread**. The no-error flag is mirrored from Minecraft's context, because Sodium enables
  `KHR_no_error`.
- Minecraft's own context stays on its hidden utility window and only ever renders offscreen. `Presenter` is the
  only thing that swaps.
- `FrameExchange` hands frames from the render thread to the presenter: fenced, with slot pools for world and
  overlay textures, and GLsync fences in both directions.
- `Presenter`:
  - runs at the display refresh (V-Sync, or a timer if V-Sync isn't honored) and keeps a history of 3 world
    frames;
  - a **playback clock** picks the display time: Interpolate = render latency + 1 interval in the past;
    Extrapolate = latency only;
  - interpolates or extrapolates the camera `Pose` (double precision, slerp);
  - runs `presenter/warp.fsh`;
  - has a **native mode**: if real fps ≥ ~0.9× output, real frames are shown as-is, with hysteresis;
  - is timed with GPU timer queries, and has debug self-test and bench helpers.
- `presenter/warp.fsh`: depth-aware **inverse reprojection** solved by fixed-point iteration with early exit.
  It uses `textureGather` (injected `HAS_GATHER` define), edge-aware bilinear depth and a background search for
  disocclusion. It blends A and B by temporal weight × confidence and snaps to the nearer frame when colors
  disagree, which is how moving objects avoid double images. Sky (depth 0) uses rotation-only matrices.
  Composite: `overlay + (1−overlay.a)·vignette·world`.

**Layer capture on the render thread** (`engine/FrameCapture`, hooks in `mixin/GameRendererMixin`):
- **A** `render3dHud` HEAD:
  - capture the world color and depth at the **window's native size**: a 1:1 copy, or a box-filter downscale if
    the main target is bigger (supersampling);
  - record P (the projection incl. bobbing, captured via `@ModifyArg` on `ProjectionMatrixBuffer.getBuffer` in
    `renderLevel`), the view rotation and the camera position;
  - then clear the main color to transparent so the hand renders alone.
- **B** `renderLevel` TAIL:
  - `HAND_CAPTURE` builds the hand layer, with **coverage = max(alpha, depth written)**: Minecraft clears depth
    right before the hand, and some mods don't write alpha;
  - `HAND_RESTORE` puts the world back under the hand.
- **B2** after `applyPostEffects`: if post effects or entity outlines ran, the whole image is baked into the
  world layer.
- **C** before `GuiRenderer.render`:
  - if the main target was **swapped** (Supersample), its image is saved as `baseImage` (that mod's own
    downscale of world + hand);
  - clear the GUI target to transparent.
  - The **vignette** (multiply blend) and **crosshair** (invert blend) blits are intercepted in
    `GuiGraphicsExtractorMixin` and replayed later.
- **D** `FrameGenSurface.blitFromTexture` → `finishFrame`:
  - `COMPOSE_OVERLAY` builds the premultiplied overlay `G + (1−Ga)·V·H` for the presenter;
  - the presented texture is rebuilt into the exact vanilla image (`FINAL`, or GUI-over-base with Supersample),
    so screenshots and other mods see vanilla;
  - frames where a screen/menu is open, or FG is off, are captured **opaque** (full image, no world layer).
- **All color copies are render passes** (`DOWNSAMPLE` at 1:1 is an exact copy). That's because of a
  **26.3 bug**: `GlCommandEncoder.copyTextureToBuffer` on a depth texture sets the shared read FBO's read buffer
  to `GL_NONE` permanently, which silently breaks later `copyTextureToTexture` color copies.
  `mixin/GlCommandEncoderMixin` fixes that state too. **Don't reintroduce `copyTextureToTexture` for color**,
  except as the startup fallback already in `downsample()`, used while pipelines aren't compiled yet.
- Main-thread pipelines live in `engine/FrameGenPipelines` and are registered via `RenderPipelines.register`
  (Fabric widens it). Shaders: `src/client/resources/assets/framegen/shaders/core/*.fsh`, written in the GLSL
  dialect Minecraft's compiler expects (`#version 330` + `GL_ARB_separate_shader_objects`, vanilla
  `core/screenquad` vertex shader, `texCoord`). Avoid `gl_FragCoord`; use `texCoord * textureSize`.

**Other pieces:**
- `compat/PresentHookReplayer` replays other mods' `GlSurface.present` injections (Sodium, ImmediatelyFast) by
  reading `@Inject` metadata from their mixin bytecode.
- `compat/SodiumConfigEntry` adds a FrameGen page to Sodium's settings.
- `compat/ModMenuIntegration`.
- `gui/FrameGenScreen`: vanilla options screen.
- `mixin/compat/SupersampleTargetFpsMixin` (`@Pseudo`, only applied when `supersample` is loaded, via
  `FrameGenMixinPlugin`): makes Supersample's dynamic scaler target `output / max(2, multiplier)`.
- `FrameGenConfig` (JSON, `configVersion` migrations: currently 4).
- `FrameGenMod`: keybinds. F8 toggles; **F9 debug snapshot** (`engine/DebugSnapshot`) writes every layer as a
  PNG plus an info txt to `<game dir>/framegen-debug/`.

**Debug and verification tools (use them):**
- **Self-test:** reprojects real frame A into B's exact camera and logs PSNR vs. naive reuse, plus the
  percentage of pixels reconstructed confidently. Baselines (llvmpipe): ~10° turn → ~90–96% of pixels at
  45–51 dB; static → 99 dB. Depth decoding was checked against the crosshair raycast: 1.620 vs 1.620 blocks.
- **F9 snapshot:** when something "disappears", this shows which stage loses it.
- **Supersample stand-in:** `devtools/build-fakess.sh` builds `run/mods/fakess.jar`, which mimics the user's
  Supersample (2× render, native output swapped in before the GUI, swapped back at render end, presented via a
  `renderFrame` mainRenderTarget redirect). Remove it from `run/mods` for vanilla tests.
- **The user's other mods** for compat testing come from Modrinth (`api.modrinth.com/v2/project/<slug>/version`).
  Slugs: `immediatelyfast`, `entity-model-features`, `entitytexturefeatures`, `3dskinlayers`,
  `player-animation-library`, `badoptimizations`. Hand Size is the user's own and harmless.

## 4. The user's Supersample, in detail (FrameGen must keep working with it)

Source of its renderer was shared; key behavior:
- `GameRenderer.extractWindow` TAIL: multiplies `windowRenderState.width/height` (and line width) by a dynamic
  scale between 100% and 400% → the **main target, hud3D target and level targets render big**. Not active when
  there's no level, during loading, or at 100%.
- `renderLevel`: `beforeLevel` / `afterLevel` around `LevelRenderer.render` toggle **variable-rate shading**
  (NVIDIA) for the level only.
- `GameRenderer.render` after `FogRenderer.endFrame`: downsamples main → a native `output` TextureTarget with
  its own filter, **swaps `GameRenderer.mainRenderTarget` to `output`** (`@Mutable` accessor), restores native
  window state, and re-binds a native-size GlobalSettingsUniform. The GUI then renders into `output`.
- `GameRenderer.render` TAIL: swaps the big target back.
- `Minecraft.renderFrame`: `mainRenderTarget()` calls are redirected to `output`, so the swapchain blit presents
  `output`. Screenshots are redirected the same way.
- The dynamic scaler targets the refresh rate. `targetFps(Window, Options)` returns a double, and FrameGen hooks
  it (see above).
- Nvidium compat: feeds Nvidium's triangle culling the big target size.

## 5. Release process (every change the user should test)

1. Bump `version=` in `gradle.properties`.
2. Add a short changelog entry at the top of `## Changelog` in `README.md`, in plain language.
3. `./gradlew --no-daemon build`.
4. `git rm release/framegen-<old>.jar`, then copy `build/libs/framegen-<new>.jar` into `release/`. Keep only the
   newest jar there.
5. Commit with a descriptive message ending with the attribution lines the system tells you to use, then
   `git push origin claude/magical-euler-cfwal6`.
6. Tell the user the raw download link and to delete the old FrameGen jar.

If you change the meaning of a config field or a default, add a migration step in `FrameGenConfig.load()` and
bump `CURRENT_VERSION`.

---

## 6. The work: four improvements (one release each; suggested order)

**Progress:** #2 done in 1.5.0 (see `FrameCapture.beginOverscan` / hook O and `Pose.display`). #1 done in 1.6.0
(entity boxes + motion, `FrameCapture.captureEntities`, `Presenter.uploadMotion`, `forwardMapMoving`/`reprojectAll`
in warp.fsh). The dev build also runs an **interpolation self-test** (real frames 1 and 3 interpolated to frame 2's
moment, compared with frame 2, with and without entity motion) and dumps images to `run/framegen-debug/selftest`.
Test scene: a no-AI pig tagged `m` circling via a repeating command block
(`execute as @e[tag=m] at @s run tp @s ^ ^ ^0.04 ~1 ~`). llvmpipe gotchas found: after startup the playback delay
can sit at several seconds for ~10 s (wait for `delay` < 1000 ms in the presenter log before judging visuals), and
updating a uniform buffer that the previous draw used stalls (hence the ring of buffers). #3 done in 1.7.0 (`InputLatch`: SDL event peeking on
the render thread at several points per frame, presenter plays samples back one gap late; `Presenter.applyResponsiveCamera`).
Verified: final player yaw identical with it on and off for the same XTest input.

### #2 first: no stretched edges on fast turns (overscan)
**Problem:** when the camera turns, the newly revealed strip at the screen edge has no source pixels; the warp
clamps or edge-stretches it (see `reproject()` in `warp.fsh`: `outside` penalty + clamp). At the user's ~72
real fps with fast mouse flicks it's very visible.

**Approach:** render the *world* with a slightly larger field of view than the display ("overscan", e.g. 5–15%
per side, configurable, default ~8%), keep the extra margin in the world layer, and crop when presenting.
- Real frames must still look exactly vanilla: same FOV and framing on screen.
- Options: (a) widen the level projection and render a larger target, then crop; or (b) the same target
  size with a wider FOV, then scale the center up. (b) loses resolution, so prefer (a) or a hybrid.
- The world-layer depth, matrices and presenter math must use the overscanned projection. The record already
  carries `projection`, and `warp.fsh` works in the source frame's NDC.
- The presenter's output pose uses the *display* projection; source frames use the overscanned one. The pose
  blend currently lerps projections, so separate "display projection" from "source projection".
- The hand, HUD and the rebuilt real frame must be unaffected (the hand uses its own projection in
  `render3dHud`).
- **Interaction with Supersample:** it already resizes the main target via `windowRenderState`. Overscan could
  use the same mechanism (enlarge window render state + adjust the projection), but then must cooperate with
  Supersample's scale and the `FrameCapture` "scaled" path. Think this through and test with `fakess`.
- **Entity/terrain culling:** the frustum must include the margin, or the edges will be empty. The cull frustum
  comes from the camera; check `Camera.prepareCullFrustum`, `cameraState.cullFrustum`, and Sodium/Nvidium
  culling (Nvidium reads the window size; Supersample hooks that).
- **Verify:** run the self-test with fast turns (`xin.py rel:20:0:30`). The "% of pixels reconstructed
  confidently" should rise noticeably vs. the current build. Take before/after screenshot bursts during a flick.

### #1: moving mobs, players, items and particles (motion vectors)
**Problem:** only camera motion is generated; entities move at the real frame rate. In Interpolate, conflicting
pixels snap to the nearer frame (no ghosting, but choppy).

**Approach:** produce a per-pixel **screen-space motion vector** buffer for dynamic geometry, and use it in the
warp.
- Simplest robust variant: for entities, compute each entity's previous-frame and current-frame positions
  (they're already interpolated per frame in extraction: `EntityRenderState`, partial ticks), and write
  velocity in an extra render target or pass.
- Alternative: an entity-ID/velocity buffer rendered in a separate cheap pass (entities only, flat shaded,
  writing the screen-space delta between this frame's and the last frame's model matrices).
- In `warp.fsh`, for pixels covered by a moving object: advect by `motion × t` instead of camera-only
  reprojection, and treat disocclusion behind moving objects with the background fill.
- Watch out for mod interference: EMF/ETF, 3D Skin Layers and Player Animation Library all change entity
  rendering, so test with them.
- Particles may be skipped at first (they're small and short-lived).
- Keep a config toggle, default on once it's solid.
- **Verify:** spawn a mob walking past (creative + spawn egg, or `/summon`), record bursts, and compare
  generated frames.

### #3: less input delay in Interpolate (without hurting smoothness)
**Constraint:** the user explicitly rejected changes that reduced smoothness. Extrapolate was "SO bad". This
must keep Interpolate's smoothness at low FPS.

**Idea:** late-latch the newest camera *rotation*. Translation and the world come from interpolated frames as
today, but the view rotation used for the presented pose comes from the most recent mouse input, reprojected
on top (rotation-only reprojection is exact and artifact-free apart from edges, which #2 fixes).
- Input is pumped on the main thread once per real frame (`MouseHandler.handleAccumulatedMovement` in
  `Minecraft.runTick`; SDL events only arrive there).
- Option: export the latest applied yaw/pitch with a timestamp from the main thread every frame (already
  in the record), and also sample input more often, e.g. an extra `SDL_PumpEvents` + mouse-delta read hook
  between frames. Investigate what 26.3's `MouseHandler` and `SDLEventHandler` allow.
- Even without more frequent input, using the newest real frame's rotation (instead of the delayed
  interpolated one) for the rotational part halves the felt delay, and rotation is what PvP players feel.
- Make it a setting ("Responsive camera", default on if it looks good), and compare the delay in the stats line.

### #4: water, glass, clouds and particles
**Problem:** transparent surfaces don't write depth, so they're reprojected with the depth of what's behind
them and wobble during camera motion.

**Approach options:**
- (a) Capture a "translucent depth" (Minecraft 26.3 has an OIT path; check `LevelRenderer` translucency targets
  and depth handling), and use the nearer depth where a translucent layer exists.
- (b) Detect pixels where translucent geometry contributed (e.g. an extra pass or a color difference before
  and after the translucent pass) and lower their confidence, so the temporally nearer real frame wins.
- Clouds: rendered separately (`CloudRenderer`), at a fixed far distance, so they could get the sky treatment.
- **Verify:** look at water or stained glass while turning, and check self-test numbers near water.

---

## 7. Conventions and gotchas

- Code style: tabs, Javadoc on non-obvious classes, comments explain *why*. Match the existing files.
- Don't use `copyTextureToTexture` for color (section 3). Don't use `gl_FragCoord` in Minecraft-pipeline
  shaders.
- Presenter-thread GL code is raw LWJGL (`GL33C`) in its own context. Never call `GlStateManager` or
  renderpearl there. Render-thread code uses renderpearl only, plus raw `GL32C` fences.
- Never sample a texture in a pass that renders into it: copy to a temp first (`ensureTemp`).
- Anything new that's shared between the threads goes through `FrameRecord` (immutable after publish) or
  `FrameExchange`.
- Vulkan backend: FrameGen deliberately disables itself (`FrameGenEngine.Status.UNSUPPORTED_BACKEND`).
- Keep `README.md` accurate, including the "Limitations" and "Testing status" sections. Everything is
  verified on llvmpipe only; the user tests on real hardware.
- Commit and push after each release; a stop hook in this environment insists on a clean tree.
