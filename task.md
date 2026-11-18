# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- Phase 3 persistence: `.kproj` snapshots + WAL replay.
- Phone-verify trim → force-stop → restore.

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

## In progress
- Commit/push/CI for the persistence slice.
- Measure sustained 30/60fps playback and decoder-pool stats on the phone.

## Next
- Hardware export session (uses the reserved decoder slot).
- Multi-clip decoder scheduling (interval index + pre-seek 1.5s ahead).
- More operator kernels (Warp, Glitch, Vectorify, Keyer, …).
- Phone-first interaction audit for every new control.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play/Pause button is `[518,1114][595,1171]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :engine-media:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
