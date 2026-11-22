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
const KINETIX_AUDIO_ABI_VERSION: jint = 3;

#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_abiVersion<'frame>(
    _env: EnvUnowned<'frame>,
    _class: JObject,
) -> jint {
    KINETIX_AUDIO_ABI_VERSION
}

fn apply_gain_and_limit(buf: &mut [f32], gain: f32, limit: f32) {
    for sample in buf.iter_mut() {
        *sample = (*sample * gain).clamp(-limit, limit);
    }
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
        apply_gain_and_limit(&mut buf, gain, limit);
        samples.set_region(env, 0, &buf)?;
        Ok(())
    });
}

/// One-pole envelope coefficients for the compressor, derived from the
/// attack/release times in seconds and the sample rate.
struct EnvelopeCoefficients {
    attack: f32,
    release: f32,
}

impl EnvelopeCoefficients {
    fn new(attack_seconds: f32, release_seconds: f32, sample_rate: f32) -> Self {
        let rate = sample_rate.max(1.0);
        Self {
            attack: (-1.0 / (attack_seconds.max(0.0001) * rate)).exp(),
            release: (-1.0 / (release_seconds.max(0.0001) * rate)).exp(),
        }
    }
}

/// Soft-knee compressor. `envelope` carries the follower state across buffers.
fn compress(
    buf: &mut [f32],
    threshold: f32,
    ratio: f32,
    coefficients: &EnvelopeCoefficients,
    envelope: &mut f32,
) {
    let ratio = ratio.max(1.0);
    for sample in buf.iter_mut() {
        let level = sample.abs();
        let coeff = if level > *envelope {
            coefficients.attack
        } else {
            coefficients.release
        };
        *envelope = coeff * *envelope + (1.0 - coeff) * level;
        let gain = if *envelope > threshold {
            let compressed = threshold + (*envelope - threshold) / ratio;
            compressed / *envelope
        } else {
            1.0
        };
        *sample *= gain;
    }
}

/// Soft-knee compressor with an envelope follower. `state` is a one-element
/// array carrying the envelope across buffers.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_applyCompressor<'frame>(
    mut env: EnvUnowned<'frame>,
    _class: JObject,
    samples: JFloatArray<'frame>,
    threshold: jfloat,
    ratio: jfloat,
    attackSeconds: jfloat,
    releaseSeconds: jfloat,
    sampleRate: jfloat,
    state: JFloatArray<'frame>,
) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        let len = samples.len(env)?;
        let mut buf = vec![0.0f32; len];
        samples.get_region(env, 0, &mut buf)?;
        let mut envelope_state = [0.0f32; 1];
        state.get_region(env, 0, &mut envelope_state)?;
        let mut envelope = envelope_state[0];
        let coefficients =
            EnvelopeCoefficients::new(attackSeconds, releaseSeconds, sampleRate);
        compress(&mut buf, threshold, ratio, &coefficients, &mut envelope);
        state.set_region(env, 0, &[envelope])?;
        samples.set_region(env, 0, &buf)?;
        Ok(())
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn gain_and_limit_scales_and_clamps() {
        let mut buf = [0.25f32, -0.5, 0.9, -0.9];
        apply_gain_and_limit(&mut buf, 2.0, 1.0);
        assert_eq!(buf, [0.5, -1.0, 1.0, -1.0]);
    }

    #[test]
    fn compressor_leaves_quiet_signal_untouched() {
        let coefficients = EnvelopeCoefficients::new(0.01, 0.1, 48_000.0);
        let mut envelope = 0.0f32;
        let mut buf = [0.1f32, -0.1, 0.05];
        let original = buf;
        compress(&mut buf, 0.7, 4.0, &coefficients, &mut envelope);
        assert_eq!(buf, original);
    }

    #[test]
    fn compressor_reduces_loud_signal_and_tracks_envelope() {
        let coefficients = EnvelopeCoefficients::new(0.001, 0.1, 48_000.0);
        let mut envelope = 0.0f32;
        let mut buf = vec![1.0f32; 4_800];
        compress(&mut buf, 0.5, 4.0, &coefficients, &mut envelope);
        assert!(envelope > 0.5, "envelope should follow the loud signal");
        let last = *buf.last().unwrap();
        assert!(last < 1.0, "loud samples must be attenuated");
        assert!(last > 0.5, "compression should be gentle at 4:1");
    }

    #[test]
    fn compressor_state_carries_across_buffers() {
        let coefficients = EnvelopeCoefficients::new(0.001, 0.1, 48_000.0);
        let mut envelope = 0.0f32;
        let mut first = vec![1.0f32; 480];
        compress(&mut first, 0.5, 4.0, &coefficients, &mut envelope);
        let carried = envelope;
        assert!(carried > 0.0);
        let mut second = vec![1.0f32; 480];
        compress(&mut second, 0.5, 4.0, &coefficients, &mut envelope);
        assert!(envelope > carried, "the follower keeps rising on a held tone");
    }
}
