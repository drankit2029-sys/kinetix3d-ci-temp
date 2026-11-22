# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- Adaptive timeline height for short/landscape screens.
- Verify the compact layout.

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

## In progress
- CI runs on the temporary public repo `drankit2029-sys/kinetix3d-ci-temp` (the main repo's Actions billing is blocked). Local build/tests/lint are green.
- Re-verify culling/transient trim, audio/mute/Rust limiter/drift, per-asset waveforms, the V2 composite, slip/slide, composite export, cancel/completion, Fit, project switching, media pool, markers, bitrate/resolution, Open, Oscillate, Optics, Style and Corner Pin on a stable display.

## Next
- V2 clip-driven overlay source and per-track compositing.
- Export foreground service for long exports.
- Rust audio engine wiring.
- More operator kernels (Warp, Glitch, Vectorify, Keyer, …).
- Phone-first interaction audit for every new control.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play/Pause button is `[518,1114][595,1171]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :engine-media:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
