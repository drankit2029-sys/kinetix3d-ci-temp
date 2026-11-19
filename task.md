# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- NLE tools: split at playhead, delete clip, keyboard transport.
- Phone-verify split/delete persistence.

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

## In progress
- Commit/push/CI for the split/delete slice.
- Measure sustained 30/60fps playback and decoder-pool stats on the phone.

## Next
- Multi-clip decoder scheduling (interval index + pre-seek 1.5s ahead).
- More operator kernels (Warp, Glitch, Vectorify, Keyer, …).
- Phone-first interaction audit for every new control.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play/Pause button is `[518,1114][595,1171]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :engine-media:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
