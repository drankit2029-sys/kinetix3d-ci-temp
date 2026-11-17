# Kinetix3D Studio

Mobile pro-grade video editor — multi-module Android project.
**AGP 9.5.1 · Kotlin 2.4.21 · Gradle 9.9.1 · Compose BOM 2026.09.00 · JDK 21 · minSdk 26 · targetSdk 37 / compileSdk 37 (android-37.1)**

The design specs are kept in the parent folder (not versioned):
`../kinetix3d_master_project_blueprint.md`,
`../kinetix3d_refined_features_and_pipelines.md`,
`../kinetix3d_step_by_step_implementation_roadmap.md`.

## Modules

| Module | Type | Responsibility |
| :--- | :--- | :--- |
| `:app` | Application | Thin shell: `MainActivity`, app-level wiring root (`PlatformApiProvider` singleton), crash-guard entry, adaptive launcher icon |
| `:core-platform-api` | Android library | All 7 version-adaptive OS API managers (API 26–37+) behind the `PlatformApi` façade + hand-written `PlatformApiProvider` (no DI framework); first-boot `HardwareCapabilityProfiler`; `AndroidWalJournal` (WAL/SQLite, `user_version=1`, idempotent replay) |
| `:core-model` | Kotlin/JVM | `DeviceCapabilityProfile`, immutable `ProjectState` tree (`trackId`, `sourceInUs`/`sourceOutUs`, `timeRemap`/`TimeRemapSpec` (`ConstantRate`/`VelocityBezierCurve`)), `NodeGraph` + `topoSort()`, `WalJournal` contract (in-memory double for JVM tests), `DirtyRenderLock`, `TransientMutationManager`, `KinetixOperatorSchema` + 14-operator v1.0 catalog |
| `:core-evaluator` | Kotlin/JVM | Cubic-Bezier / damped-spring solvers (O(1), closed-form), `SplineArcLengthTable` (512-entry LUT + Bishop frames), expression bytecode VM, `SpeedRampIntegrator` |
| `:engine-media` | Android library (+ **Rust**) | MediaCodec decoder pool, VFR→CFR H.264 All-I proxy worker, Rust audio DSP & beat detection (`rust/`, cargo-ndk) |
| `:engine-render` | Android library | OpenGL ES 3.2 DAG compiler, `DoD` FBO pool, 512×512 tile-bucket composer, stateless `Particles2D/3D` |
| `:engine-ai` | Android library | Offline NPU AI suite (SAM 2, DepthAnythingV2, Whisper, RIFE Lite) |
| `:ui-studio` | Android library | Adaptive Compose studio: timeline canvas, `OmniTrackpad` deck, graph editor, node UI |

Dependency rule: `:app` → `:ui-studio` → engines → `core-model`/`core-evaluator`.
`core-model` and `core-evaluator` are pure Kotlin/JVM so the math and model layers
unit-test without an Android SDK. Nothing depends on `:ui-studio`.

## Native code policy

No first-party C++. Native code is **Rust** (`:engine-media/rust`, cargo-ndk via
direct Gradle wiring (x86_64 hosts/CI: all four ABIs via cargo-ndk + NDK; ARM hosts like the Termux phone: `arm64-v8a` via pkg clang, since the NDK ships no Linux/ARM64 host toolchain), `libkinetix_audio.so`, loaded with
`System.loadLibrary`). Bundled upstream-maintained native engines — Google
Filament (3D stage) and Google LiteRT (AI runtime) — are explicitly permitted,
always version-pinned, with untrusted inputs validated at import boundaries
(see the roadmap, §1.4 "Native Code Policy"). Toolchain: `rustup` stable +
targets `aarch64-linux-android`, `armv7-linux-androideabi`,
`i686-linux-android`, `x86_64-linux-android`; Android NDK `r28c`.

## Build & dev loop

The phone is the build machine; the PC is only the editor.

```bash
build-on-phone              # pack -> push -> incremental build on the Termux phone -> install -> relaunch
build-on-phone --check      # lint (all modules) + ktlint (all module sources) on the phone only
scripts/check.sh            # same, as a project-local script
./gradlew :core-model:test :core-evaluator:test   # JVM unit tests (model & evaluator)
```

CI (`.github/workflows/android-ci.yml`): Temurin JDK 21 → `gradle/actions/setup-gradle@v4` →
SDK/NDK via `sdkmanager` (API 37.1, build-tools 37.0.0, NDK r28c) → Rust toolchain + Android targets →
JVM unit tests → `assembleDebug` → debug-signed APK via `actions/upload-artifact@v4`
(`kinetix3d-studio-debug-signed-apk`).

## Manifest & permissions

Manifests request only permissions paired with shipped features. Phase 1 ships
`VIBRATE` (haptics in `HapticsAndInputApiManager`) and `WRITE_EXTERNAL_STORAGE`
with `maxSdkVersion="28"` (API ≤ 28 `DIRECTORY_MOVIES` export fallback; MediaStore
`IS_PENDING` on 29+ needs none). A merged-in `READ_EXTERNAL_STORAGE` with no
Phase-1 feature was stripped with a `tools:node="remove"` merger marker; it returns
(API 26–32) together with Phase 3 media import. `media3-common` (still pulled in by
`media3-extractor` for container demuxing) merges `ACCESS_NETWORK_STATE`; it is
stripped with the same `tools:node="remove"` marker because the compositor path
never opens a network stream.
Add any further permission only together with the feature that needs it
(`INTERNET` for v1.5+ local transfer, `RECORD_AUDIO` for the Phase 3 voiceover
recorder, `READ_MEDIA_*`/legacy `READ_EXTERNAL_STORAGE` for Phase 3 import,
`FOREGROUND_SERVICE` for export).
The template's `INTERNET`/`REQUEST_INSTALL_PACKAGES`/cleartext-traffic/`FileProvider`
were removed deliberately; re-add only when a feature requires them.

## Status

Repository structure, Gradle setup and CI/CD pipeline are complete and verified on
device (SM-M366B, Android 17). Phase 1 — OS API boundary (`PlatformApi` + seven
managers), first-boot hardware profiler, immutable project model/WAL, math core and
the Rust JNI chain — is complete. Phase 2 (OpenGL ES 3.2 render engine) is next —
see the roadmap, §3.
