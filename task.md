# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- GPU/codec hardening on the VM emulator matrix.
- Then the remaining MVP UI polish and physical-device sign-off.

## Done
- Phase 1: model, WAL, evaluator, 7 OS managers, hardware profiler.
- Phase 2: pure DAG/DoD planner, EGL render thread, FBO pool, tile/ROI executor.
- Kernels: pass-through, ColorSuite, separable Blur, Tiles, Glow.
- Timeline UI: tracks/clips/ruler/playhead/transport, real `ProjectState` wiring.
- Clip selection + edge-drag trim with 24dp hit targets and JVM geometry tests.
- MediaCodec external-OES video source; SurfaceTexture transform matrix fixes video orientation.
- Clean preview default + FX toggle; sharp 960p preview.
- `PlaybackClock` (monotonic play/pause/seek/speed/loop/frame quantization) with JVM tests.
- `DecoderPool` (capacity ceiling + export reservation) with JVM tests; wired into `RenderThread`.
- Async `MediaCodecSource` decode thread: render thread only latches frames; seek requests are coalesced.
- Phone-verified: play advances in real time, pause freezes, paused seek and seek-while-playing both update the frame.
- `.kproj` container (`K3DP` magic + version + CRC32 + protobuf `ProjectState`) with JVM tests.
- `ProjectStore`: atomic snapshot writes, WAL append on trim, replay + checkpoint on startup; JVM tests.
- Phone-verified persistence: trimmed "Winter Olympics" to 00:06.500, force-stopped, restarted → "project: restored demo" and 00:06.500 survived.
- `HardwareExportSession`: synchronous frame-accurate decode → DAG → encoder input surface → MediaMuxer; `EglCore.setPresentationTime`; export planner at full comp resolution.
- Export UI: 3s 1080p H.264 button, progress bar, atomic MediaStore publish, cache cleanup.
- Phone-verified export: 180 frames, 1920x1080, 3.000s, avc1, 6.0MB, published to `content://media/external_primary/video/media/212868`.
- Kernels: Warp (7 modes), Glitch (RGB split/block/scanline/VHS/tear), Vectorify (Oklab posterize), Keyer (chroma/luma + despill + checkerboard preview).
- FX chip rack: Clean / Color / Warp / Glitch / Vectorify / Keyer, chain order preserved; phone-verified each kernel and the clean reset.
- `ProjectEditor.splitClip` / `removeClip`, `ADD_CLIP` / `REMOVE_CLIP` mutations, reducer cases, `ProjectStore.recordClipSplit` / `recordClipDelete`; JVM tests for ranges, unique ids, add/remove.
- Split/Delete buttons in the selected-clip row; seeking no longer clears the selection; tapping the selected clip toggles it off.
- Keyboard transport: C cycles clips, S splits, Del/Backspace deletes, Space toggles play, Home/Left/Right seek by 0.5s.
- Phone-verified on the scrcpy virtual display: split Title at 2.5s, restart → both halves survived; delete right half, restart → deletion survived.
- `RenderStats` telemetry: EMA FPS, frame time, tile count and format posted to the UI every 250ms.
- Phone-verified readout while playing: `preview 48 fps · 6.9 ms · 1 tiles RGBA16F` on the scrcpy virtual display.
- Undo/redo: 50-entry in-memory stacks, Undo/Redo buttons, Ctrl+Z / Ctrl+Shift+Z, and `ProjectMutationCodec.replaceState` full-state checkpoints persisted through the WAL.
- Phone-verified: delete → Undo restored the clip → Redo deleted it again → restart kept the redo state.
- Media import: `OpenDocument` picker, copy to app external files, `KinetixRenderView.setSourcePath` recreates the decode source, path persisted in SharedPreferences, Import button + source label.
- Fixed still-image orientation by flipping the bitmap before `GLUtils.texImage2D`.
- Phone-verified on display 8: imported a PNG through the picker, preview switched upright, source label showed the imported file, and the path survived a restart.
- Phone-verified video import: picked a Camera MP4, decoder ready, playback advanced to 00:04.299 at ~41 fps, source label showed the imported file.
- Split All: `ProjectEditor.splitAll` splits every spanning clip across tracks; Shift+S and a Split All button; persisted as a full-state checkpoint.
- Phone-verified: Split All at 2.000s split V1 and A1 into two segments each; restart kept both splits.
- `DecoderPoolManager`: half-open interval schedule with 1.5s lookahead, priority ordering and max-decoder cap; JVM tests cover lookahead, priority, cap, active clip and edge cases.
- UI builds `ScheduledClip`s from video tracks and shows the active clip; `setSourcePath` follows the active clip at cuts.
- Phone-verified: at 00:00 the source line showed `clip: Winter Olympics`; at 00:03 it showed `clip: Winter Olympics-2` with the matching frame.
- Ripple trim out: `ProjectEditor.rippleTrimOut` trims the out point and shifts later clips on the same track; R key and a Ripple Out button; persisted as a full-state checkpoint.
- Phone-verified: rippled the V1 clip from 2.0s to 1.5s; Winter Olympics-2 shifted left while A1 stayed at 2.0s; restart kept the ripple.
- Detach Audio: `ProjectEditor.detachAudio` creates a linked `AudioClipPayload` clip on a new `A<n>` track, sets `linkGroupId` on both clips, and no-ops for audio/already-linked clips. D key and a Detach Audio button.
- Phone-verified: detached Winter Olympics → new A2 track with the green audio clip at 0–1.5s; restart kept the A2 track.
- Export range: `HardwareExportSession.startUs`, Set In / Set Out buttons, I/O shortcuts, timeline range overlay, dynamic "Export N.Ns" label.
- Phone-verified: set in=1.0s/out=3.0s, exported 120 frames; MediaStore reports 1920×1080, 2000ms, 2.98MB.
- Project settings dialog: 1080p/4K and 30/60 fps, persisted as a full-state checkpoint; preview scale adapts (4K→0.25, 1080p→0.5); label shows resolution, fps and preview height.
- Phone-verified: switched to 1920×1080 / 30 fps, label updated to `1920×1080 · 30 fps · 540p PREVIEW`, restart kept the settings.
- Timeline thumbnails: `MediaMetadataRetriever` frames at each video clip's midpoint, scaled to 64px, drawn at 55% opacity with a dark label scrim.
- Phone-verified: V1 clips show distinct thumbnails; audio clips stay flat; labels remain readable.
- Clip move: body drag moves a clip in time (edge drag still trims), Shift+Left/Right moves the selected clip by 0.5s, one undo entry per drag, persisted as a snapshot.
- Phone-verified: moved Winter Olympics from 0.0s to 0.5s, leaving a gap; restart kept the move.
- Two-source compositor: `kinetix.composite` schema, `CompositeKernel` (2D + external OES variants), `DagRenderExecutor` overlay source + `overlay-input` skip, `RenderThread.setOverlaySourcePath`, `demoCompositeGraph`, Composite chip.
- Phone-verified: Composite 50% shows a clear double-exposure of the moving people at 60 fps; the overlay decoder shares the pool.
- V2 clip-driven overlay: `ProjectEditor.addClip`, Add Overlay button, `TimelineClipUi.sourceInUs`, `RenderTicket.overlayPtsUs`, overlay source time = `sourceInUs + (playhead - clipStart)`.
- Phone-verified Add Overlay: a 3s V2 video clip appears at the playhead; the overlay source/time wiring is built and unit-tested. Full V2 composite screenshot was blocked by the scrcpy server restarting repeatedly.
- Export foreground service: `ExportForegroundService` (mediaProcessing), progress notification, `ExportRequest`/`ExportState` contract, `POST_NOTIFICATIONS` runtime request, UI observes the service state.
- Phone-verified: export ran through the service, notification channel created, MediaStore reports 1920×1080 / 3000ms / 4.5MB, service stopped after completion.
- Linked clip moves: `ProjectEditor.moveClip` shifts every clip sharing the `linkGroupId`; JVM tests cover video→audio and audio→video moves.
- Phone-verified: moving Winter Olympics by +0.5s moved the linked A2 audio too; restart kept both.
- Slip trim: `ProjectEditor.slipClip` shifts `sourceInUs`/`sourceOutUs` without moving the timeline range; Slip −/+ buttons and `,`/`.` shortcuts; JVM tests cover the shift, zero clamp and missing clips.
- Slip UI verified on device; the frame-change screenshot was blocked by the scrcpy display disappearing mid-test.
- Slide trim: `ProjectEditor.slideClip` moves a clip and adjusts the previous out/next in points so track duration is unchanged; Slide −/+ buttons and `;`/`'` shortcuts; JVM tests cover right/left slides and missing clips.
- Slip + slide are unit-tested; the frame-change screenshot was blocked by the scrcpy display disappearing mid-test.
- Audio playback: `AudioPlayback` decodes the active media's audio track to PCM and writes it to `AudioTrack`; the studio starts/stops it with the transport and releases it when the source changes.
- Audio playback is built and lint-clean; phone verification is pending because the main display is in use and the scrcpy server keeps restarting.
- Composite export: `HardwareExportSession` accepts an overlay path + source-time offset, creates a second `SynchronousVideoSource`, and renders `demoCompositeGraph`; the service and UI pass the V2 overlay path/offset.
- Composite export is built and lint-clean; phone verification is pending on a stable display.
- Audio waveforms: `WaveformGenerator` decodes the audio track to 256 peak buckets; the timeline draws white waveform bars inside audio clips.
- Waveforms are built and lint-clean; phone verification is pending on a stable display.
- Export cancellation: `HardwareExportSession` checks `isCancelled` per frame; the service handles the notification Cancel action, sets `ExportState.Cancelled`, and stops.
- Cancellation is built and lint-clean; phone verification is pending on a stable display.
- Timeline Fit button: sets `pxPerSecond` so the whole project duration fits the visible width (clamped 32–160 dp/s).
- Project catalog: `ProjectCatalog` lists/creates `.kproj` files; the Projects dialog opens a project or creates a new one; `MainActivity` swaps the `ProjectStore` per active project. JVM tests cover create/list, corrupt files and missing directories.
- Export success now shows an Open button that launches the gallery viewer with the published MediaStore URI.
- Oscillate / Auto-Shake kernel: sine/cos UV offset with amplitude/frequency/seed/time; `oscillate` chip in the FX rack.
- Optics kernel: radial god rays, anamorphic streak and chromatic aberration; `optics` chip in the FX rack.
- Layer Style kernel: drop shadow, outline, bevel and emboss; `style` chip in the FX rack.
- Corner Pin: `Homography` (unit square → quad, inverse mat3) + `CornerPinKernel`; `ParamChannel.valueY` supports Vec2 params; `corner` chip with a trapezoid preset. JVM tests cover identity/affine/perspective inverses.
- Media pool: imports add a `MediaAssetDescriptor` (mime, size, duration, dimensions) to `ProjectState.mediaPool`, persisted in `.kproj`; the Media dialog lists assets and re-selects one as the active source.
- Audio mute: `AudioPlayback.setVolume` + Mute/Unmute transport button.
- Timeline markers: `TimelineMarker` in `ProjectState`, `ProjectEditor.addMarker/removeMarker`, Add Marker button, amber ruler ticks; JVM tests cover add/remove and `.kproj` round-trip.
- Export bitrate presets: 6/12/24 Mbps in the settings dialog, carried through `ExportRequest`/the service to the encoder; the status label shows the selected bitrate.
- Export resolution presets: 1080p/4K in the settings dialog, carried through `ExportRequest` to the encoder; the status label shows the selected resolution.
- Markers dialog: lists markers with their time/label and a Delete action; removal is persisted as a snapshot.
- Export notification now has a content intent that opens the studio on tap.
- Rust DSP: `KinetixAudioNative.applyGainAndLimit` (JNI, ABI 2) applies gain + a symmetric limiter to interleaved float samples; `AudioPlayback` converts decoded PCM to float, calls the native limiter, and writes it back to AudioTrack.
- Audio/video drift correction: `AudioPlayback.positionUs()` exposes the AudioTrack frame position; the studio checks every 500ms and restarts audio at the playhead when drift exceeds 250ms (covers seek-while-playing).
- Export completion notification: a non-ongoing notification with the frame count and a gallery content intent, posted after the foreground notification is removed.
- Roadmap UI compliance: timeline clips are culled to the visible scroll range; trim drags now update the preview state only and commit one undo entry + one snapshot on release (matching the TransientMutationManager guideline).
- Media pool asset deletion: the Media dialog has a Delete action that removes the asset from `mediaPool` and persists the snapshot.
- Per-asset thumbnails/waveforms: `TimelineClipUi.assetId` maps clips to media-pool URIs; thumbnails and waveforms are generated per asset (cached per path) and fall back to the active source.
- Tappable markers: `hitTestMarker` gives ruler ticks a 12dp coarse hit target; tapping a marker seeks to it. JVM test covers hit/miss/ruler bounds.
- Keys dialog documents the keyboard transport/editing shortcuts (Space, arrows, C/S/Shift+S, Del, R, D, comma/period, semicolon/apostrophe, I/O, Ctrl+Z).
- Transport header split into two rows: project name + time, then a horizontally scrollable Play/Mute/−/+/Fit row. Fixes the project name being squeezed to one character per line on 411dp phones.
- Phone-verified: the two-row header renders correctly; the Keys and Media dialogs open and are readable.
- Adaptive timeline height: 220dp on screens shorter than 600dp (landscape/small phones), 300dp otherwise.
- Rust soft-knee compressor (ABI 3): `applyCompressor` runs a one-pole envelope follower with attack/release coefficients and a 4:1 soft knee above 0.7; the Kotlin AudioTrack path applies it before the limiter. DSP extracted into pure Rust functions with 4 `cargo test` cases (quiet passthrough, attenuation, envelope tracking, cross-buffer state).
- Actions row is horizontally scrollable: Add Marker / Markers / Keys / Media / Projects / Add Overlay / Settings / Set In / Set Out / Split All / Undo / Redo no longer squeeze "Projects" into a vertical column on 411dp phones. Phone-verified.
- Rust 3-band EQ (ABI 4): low shelf 200 Hz, peak 1 kHz (Q 0.7), high shelf 4 kHz, direct-form-II biquads with 12-float cross-buffer state. `AudioPlayback.setEq` applies it before the compressor; the EQ dialog has −/value/+ rows (1 dB steps, ±12 dB) and Reset. 4 new `cargo test` cases (flat identity, low boost, high cut, split-buffer continuity) — 8/8 total.
- EQ verified on the VM emulator (`k3d-api36`, API 36): ABI 4 loads (`libkinetix_audio loaded, abi=4`), the EQ dialog opens, Low +3 dB updates, Reset returns all bands to 0 dB.
- Media pool bins/tags: `MediaAssetDescriptor.binId` + `tags`; `ProjectEditor.setAssetBin` / `addAssetTag` / `removeAssetTag`; the Media dialog groups assets by bin, cycles bins with a tap, opens a tag editor, and adds a specific asset to the timeline with its `assetId` bound. `addOverlay` now binds the media-pool asset ID instead of the file path. JVM tests cover bin moves, tag add/remove, asset-bound clips, and protobuf round-trip.
- Bins/tags verified on the VM emulator (`k3d-api36`): imported a real MP4, cycled `default → b-roll`, added `#hero`, and Add-to-timeline created a clip bound to the asset.
- Human-readable media names: `MediaAssetDescriptor.name` + `displayName()`; the SAF import stores `OpenableColumns.DISPLAY_NAME`; timeline labels and the Media dialog use the name (falling back to the URI file name). Verified on the emulator: the V1 clip now shows `kinetix-import-…mp4` instead of the raw asset ID.
- Audio master clock: `AudioPlayback.isActive()`; `TimelinePanel` drives the playhead from the AudioTrack position when audio is active and falls back to the wall clock otherwise; the 500ms drift-correction restart loop is gone. Verified on the audio-enabled API 36 emulator with an H.264+AAC test clip: `audio track playing: 3 48000Hz 1ch`, playhead advanced to **00:04.093** at 30 fps, waveform visible on the A1 clip.
- Composite blend modes: the composite shader supports Normal/Screen/Multiply/Add via a `uBlendMode` uniform; `demoCompositeGraph(blendMode)` carries the param; the UI shows blend-mode chips when Composite is selected. `DemoGraphTest` covers the graph param.
- Export polish: Cancel button + `ExportForegroundService.cancel`, ranged export duration fixed (`rangeEnd - rangeStart`), export uses the active/overlay asset paths, and the status moved to its own ellipsized line.
- Compact header: Kinetix3D + project name row, 16dp content padding, single-line preview/status/source labels, and timeline clip labels constrained to the clip width.
- Portrait-only MVP: `MainActivity` is `screenOrientation="portrait"` and handles orientation/screenSize config changes, so the editor state survives rotation. The emulator's landscape layout was broken (content clipped/zoomed), so landscape is deferred to v1.0; the phone-first editor is portrait-first.
- Timeline height: 220dp on screens shorter than 800dp (e.g. Pixel 2 at 731dp), 300dp otherwise, so the timeline fits short phones better.
- Settings and Markers dialogs verified on the audio-enabled API 36 emulator: resolution/fps/export presets and the empty-markers state render correctly.
- Project catalog: the Projects dialog lists the saved project; New Project creates and switches to a fresh demo project, and the timeline reloads with the demo clips. Verified on the API 36 emulator.
- 1080p export completed and **Open** launched the video player; the exported test pattern plays. The Cancel button is visible during export and `ExportForegroundService.cancel` is implemented, but the 1080p export finishes too quickly on the emulator to exercise the cancel path; a longer export is needed for that check.
- FX rack collapse: the preview label row has a **Hide FX / Show FX** toggle. Collapsing hides the effect chips and composite blend-mode chips; verified on the API 36 emulator, the timeline moves up and is fully visible.
- Timeline snapping: clip body drags and trim drags snap to clip edges, the playhead, and markers within a 10dp threshold; the dragged clip is excluded from its own targets. JVM tests cover the threshold and target collection. Emulator gesture injection for timeline drags remains unreliable (the same limitation as earlier), so the visual snap check is pending on the phone.
- Playhead follow: during playback the timeline scrolls to keep the playhead visible, centering it when it leaves the viewport. Verified on the API 36 emulator: playback at 00:03.422 with the timeline scrolled to the playhead.
- Fullscreen preview: the **Full** button expands the preview to the screen height with an **Exit** overlay; verified on the API 36 emulator (letterboxed 16:9 video, Exit returns to the editor). On the phone the Exit button is a `Popup` above the SurfaceView (Compose overlays inside the preview Box are hidden by the SurfaceView), and the system Back button also exits.
- Phone sign-off (SM-M366B, USB): snapping verified (Title start snapped to 0s); fullscreen preview verified (Exit Popup visible and working); playback + audio master clock verified (00:03.800, 59 fps, AudioTrack 48 kHz stereo); the timeline follows the playhead.
- Phone export sign-off: **180 frames** (3s at 60 fps) exported on the SM-M366B hardware encoder; the completion notification and **Open** button were verified.

## MVP plan (target 2-4 weeks)

1. **Core polish + audio (week 1)**
   - Human-readable asset names in the timeline/media pool; tag chips and search/filter.
   - Audio master clock + native Rust audio engine (AAudio/cpal) with EQ/compressor/limiter; remove the drift-correction hack.
   - Multi-track compositor (V2 overlay per track, blend modes).
   - Export polish: in-UI progress, cancel, completion notification, error handling, 4K/1080p/bitrate/in-out.
2. **UI refinement (week 2)**
   - Design system: colors, typography, spacing, icons.
   - Timeline: track headers, clip labels, selected state, playhead, zoom, marker lane, snapping.
   - Preview: aspect ratio, safe areas, fullscreen, FX rack.
   - Media pool: grid/list, bins, tags, search, import progress.
   - Dialogs: settings, export, project, markers, EQ, keys; consistent layout.
   - Accessibility: 24dp targets, content descriptions, contrast, landscape/short screens.
3. **Stability + tests (week 3)**
   - Lifecycle: rotation, background/foreground, surface loss, decoder release.
   - Export cancel/retry, low-memory, corrupt media, SAF edge cases.
   - JVM tests for new logic; screenshot tests on the emulator.
   - Device matrix: API 26/28/30/33/35/36/37, low/mid/high RAM, GPU modes.
4. **Risk hardening (weeks 4-8)**
   - GPU: GLES 3.2/3.1/3.0/2.0 fallbacks, FBO formats, max texture size, shader compile, context loss, 16KB pages.
   - Codec: H.264/H.265/AV1, 4K, 10-bit, HDR, VFR, rotation, audio layouts, MediaCodec quirks.
   - Thermal/battery/storage; device profiles.
   - Fix issues found; final sign-off on physical devices (the VM covers API/GPU-mode regressions, not real hardware quirks).

## In progress
- CI runs on the temporary public repo `drankit2029-sys/kinetix3d-ci-temp` (the main repo's Actions billing is blocked). Local build/tests/lint are green.
- Heavy work moved to the Oracle Cloud VM (16 vCPU, 31 GB RAM, KVM; direct SSH on port 443 from campus + Cloudflare HTTPS fallback). VM access doc: `../VM-ACCESS.md`; VM coordination log: `/home/ubuntu/talk.txt`. VM setup complete: JDK 21, Rust 1.100 + cargo-ndk 4.1.2 + four Android targets, NDK r28c, SDK platform 37.1, repo cloned; `:app:assembleDebug`, the full unit-test/lint suite, ktlint (101 files) and `cargo test` (8/8) are green there.
- Re-verify culling/transient trim, audio/mute/Rust limiter/drift, per-asset waveforms, the V2 composite, slip/slide, composite export, cancel/completion, Fit, project switching, media pool, markers, bitrate/resolution, Open, Oscillate, Optics, Style and Corner Pin on a stable display.

## Next
- MVP week 1: human-readable asset names, audio master clock + native Rust audio engine, multi-track compositor, export polish.
- MVP week 2: design-system/UI refinement, timeline/media/preview/dialog polish, accessibility.
- MVP week 3: lifecycle/stability tests, emulator device matrix.
- Risk hardening: GPU fallbacks, codec matrix, thermal/storage, physical-device sign-off.
- After the hardened MVP: Phase 4 (OmniTrackpad/curve editor), Phase 5 (plugin SDK/operator parity), Phase 6 (3D stage/HDR), Phase 7 (AI suite).

## Device matrix (VM emulators, 2026-11-22)

- API 26 (Pixel 2, 2 GB): launches, **R11F_G11F_B10F** fallback, no app crash.
- API 28 (Pixel 2, 2 GB): launches, **R11F_G11F_B10F**, no app crash.
- API 30 (Pixel 2, 2 GB): launches, **R11F_G11F_B10F**, no app crash.
- API 33 (Pixel 2, 2 GB): launches, **RGBA16F**, notification permission, no app crash.
- API 35 (Pixel 2, 2 GB): launches, **RGBA16F**, notification permission, no app crash.
- API 36 (Pixel 6, 4 GB): launches, **RGBA16F**, full UI verified.
- API 37: the emulator's system UI/launcher crash with `Failed to initialize display event receiver status=-32` under both SwiftShader and `-gpu off`; the app is not testable on this emulator. Needs a physical device or a newer emulator build.
- AVDs: `k3d-api26/28/30/33/35/36`; `api36` belongs to the Teleprompter agent, `k3d-api36` is Kinetix3D's.

## Codec matrix (VM emulator, API 36)

- H.264 4K (3840x2160, 62 Mbps): imports and decodes at ~20 fps, RGBA16F, no crash.
- H.264 1080p + AAC audio: imports, plays with the audio master clock, waveform visible.
- HEVC 1080p: imports and decodes at ~14 fps, no crash.
- AV1 1080p: imports and decodes at ~10 fps, no crash.
- Portrait H.264 screen recording: imports and plays.
- Export from the AV1 1080p source: **180 frames**, H.264 1080p, published to `Movies/Kinetix3D/kinetix-export-…mp4`, no crash.
- 4K export on the emulator's software H.264 encoder: configure fails (the encoder does not support 3840×2160). The app reports **"Encoder does not support 3840×2160; choose a lower export resolution"** as a fallback, and the Settings dialog now **disables the 4K radio** with the hint "4K encoding is not supported on this device; 1080p is selected" when the device profile's encode matrix says 4K is unsupported. A stale 4K selection resets to 1080p. Verified on the API 36 emulator; 1080p export remains green.
- Software GL (`-gpu off`): the app renders the AV1 source at 52 fps, RGBA16F, no crash.
- 16KB page size: the Rust `.so` LOAD segments are aligned `0x4000`; `zipalign -c -P 16` passes.
- The emulator's software decoders (`c2.android.avc.decoder`, `c2.android.hevc.decoder`, `c2.android.av1-dav1d.decoder`) all initialize cleanly.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play/Pause button is `[518,1114][595,1171]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :engine-media:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
