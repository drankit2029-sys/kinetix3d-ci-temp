# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- Detach Audio: linked audio clip on a new audio track.
- Phone-verify the new track and persistence.

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

## In progress
- Commit/push/CI for the Detach Audio slice.
- Measure sustained 30/60fps playback on the main display when available.

## Next
- Multi-source compositor (decode two tracks at once) and pre-seek wiring.
- More operator kernels (Warp, Glitch, Vectorify, Keyer, …).
- Phone-first interaction audit for every new control.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play/Pause button is `[518,1114][595,1171]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :engine-media:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
