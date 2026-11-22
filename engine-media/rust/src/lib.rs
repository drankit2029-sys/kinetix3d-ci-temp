//! Kinetix3D Studio — Rust native core for `:engine-media`.
//!
//! Phase 3 will grow this into the AAudio/cpal audio engine and the
//! spectral-flux beat detector. The current slice carries the ABI probe and a
//! real-time-safe gain/limiter used by the Kotlin AudioTrack path.

use jni::objects::{JFloatArray, JObject};
use jni::sys::{jfloat, jint};
use jni::EnvUnowned;

/// ABI version of the native Rust core. Bump whenever the JNI contract
/// changes so callers can assert compatibility at startup.
const KINETIX_AUDIO_ABI_VERSION: jint = 2;

#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_abiVersion<'frame>(
    _env: EnvUnowned<'frame>,
    _class: JObject,
) -> jint {
    KINETIX_AUDIO_ABI_VERSION
}

/// Applies `gain` and a symmetric `limit` clamp to interleaved float samples.
/// The Kotlin audio path calls this per decoded PCM buffer.
#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_applyGainAndLimit<'frame>(
    mut env: EnvUnowned<'frame>,
    _class: JObject,
    samples: JFloatArray<'frame>,
    gain: jfloat,
    limit: jfloat,
) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        let len = samples.len(env)?;
        let mut buf = vec![0.0f32; len];
        samples.get_region(env, 0, &mut buf)?;
        for sample in buf.iter_mut() {
            *sample = (*sample * gain).clamp(-limit, limit);
        }
        samples.set_region(env, 0, &buf)?;
        Ok(())
    });
}
