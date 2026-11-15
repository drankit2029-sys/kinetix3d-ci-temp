# Kinetix3D Studio

Mobile pro-grade video editor — multi-module Android project (Kotlin 2.4.10,
AGP 9.5.0, Gradle 9.7.1, Compose BOM 2026.09.00).

The design specs live in the parent folder:
`../kinetix3d_master_project_blueprint.md`,
`../kinetix3d_refined_features_and_pipelines.md`,
`../kinetix3d_step_by_step_implementation_roadmap.md`.

## Modules

| Module | Type | Responsibility |
| :--- | :--- | :--- |
| `:app` | Application | Thin shell: `MainActivity`, DI root, crash-guard entry |
| `:core-platform-api` | Android library | Version-adaptive OS API managers (API 26–37+), first-boot `HardwareCapabilityProfiler` |
| `:core-model` | Kotlin/JVM | Immutable `ProjectState` tree, `NodeGraph`, `TransientMutationManager`, SQLite `<0.8ms` WAL journal |
| `:core-evaluator` | Kotlin/JVM | Cubic-Bezier / damped-spring solvers, `SplineArcLengthTable`, Oklab/SLERP math, expression bytecode VM |
| `:engine-media` | Android library | MediaCodec decoder pool, VFR→CFR H.264 All-I proxy worker, Oboe audio DSP |
| `:engine-render` | Android library | OpenGL ES 3.2 DAG compiler, `DoD` FBO pool, 512×512 tile-bucket composer, stateless `Particles2D/3D` |
| `:engine-ai` | Android library | Offline NPU AI suite (SAM 2, DepthAnythingV2, Whisper, RIFE Lite) |
| `:ui-studio` | Android library | Adaptive Compose studio: timeline canvas, `OmniTrackpad` deck, graph editor, node UI |

Dependency rule: `:app` → `:ui-studio` → engines → `core-model`/`core-evaluator`.
`core-model` and `core-evaluator` are pure Kotlin/JVM so the math and model
layers unit-test without an Android SDK. Nothing depends on `:ui-studio`.

## Build & dev loop

The phone is the build machine; the PC is only the editor.

```bash
build-on-phone              # pack -> push -> incremental build on the Termux phone -> install -> relaunch
build-on-phone --check      # lint + ktlint on the phone only
scripts/check.sh            # NewApi lint on ALL modules + ktlint on ALL module sources
./gradlew :core-model:test :core-evaluator:test   # JVM unit tests (model & evaluator)
```

CI runs the same JVM tests plus `assembleDebug` (see `.github/workflows/android-ci.yml`).

## Status

Day 1 — repository structure, Gradle setup & CI/CD pipeline complete.
Phase 1 (OS API boundary, hardware profiler, immutable state, math core) is next
— see `kinetix3d_step_by_step_implementation_roadmap.md`, §2.
