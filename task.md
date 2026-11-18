# Kinetix3D Studio — Task Board

Updated: 2026-11-19

## Current focus
- Phase 2 render engine → Phase 3 media playback transition.
- Verify the clean preview default + FX toggle on the phone.
- Decoder pool / playback clock next.

## Done
- Phase 1: model, WAL, evaluator, 7 OS managers, hardware profiler.
- Phase 2: pure DAG/DoD planner, EGL render thread, FBO pool, tile/ROI executor.
- Kernels: pass-through, ColorSuite, separable Blur, Tiles, Glow.
- Timeline UI: tracks/clips/ruler/playhead/transport, real `ProjectState` wiring.
- Clip selection + edge-drag trim with 24dp hit targets and JVM geometry tests.
- MediaCodec external-OES video source (seek/decode to playhead).
- Playback preview at 960p with 33ms playhead throttle and a large Play button.

## In progress
- Install the clean-preview build; verify sharp video and the FX toggle.
- Verify playback/seek on the phone using exact `uiautomator` bounds.
- Commit/push/CI for the clean-preview + FX toggle change.

## Next
- Decoder pool + playback clock for continuous 30/60fps playback.
- Project persistence (`.kproj` + WAL wiring).
- Hardware export session.
- More operator kernels (Warp, Glitch, Vectorify, Keyer, …).
- Phone-first interaction audit for every new control.

## Testing notes
- Phone: USB `RZCY61Z8TPD`; WiFi `10.49.246.235:5555`; hotspot fallback if USB drops.
- scrcpy virtual display: `scrcpy -s <serial> --new-display=1080x2400/420 --no-audio --no-control`; capture with `screencap -d <long-id>`.
- Use `uiautomator dump` for exact tap bounds; the Play button was `[518,1047][595,1104]` on the main display.
- Build: `JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true" ./gradlew :app:assembleDebug`.
- Tests: `:engine-render:testDebugUnitTest :ui-studio:testDebugUnitTest`; then `:app:lintDebug` and ktlint.
