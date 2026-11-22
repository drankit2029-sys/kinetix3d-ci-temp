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
const KINETIX_AUDIO_ABI_VERSION: jint = 4;

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

/// Direct-form-II biquad coefficients (already normalized by a0).
#[derive(Clone, Copy, Default)]
struct Biquad {
    b0: f32,
    b1: f32,
    b2: f32,
    a1: f32,
    a2: f32,
}

impl Biquad {
    fn identity() -> Self {
        Self {
            b0: 1.0,
            ..Self::default()
        }
    }

    fn low_shelf(sample_rate: f32, freq: f32, gain_db: f32) -> Self {
        Self::shelf(sample_rate, freq, gain_db, false)
    }

    fn high_shelf(sample_rate: f32, freq: f32, gain_db: f32) -> Self {
        Self::shelf(sample_rate, freq, gain_db, true)
    }

    fn shelf(sample_rate: f32, freq: f32, gain_db: f32, high: bool) -> Self {
        if gain_db.abs() < 0.001 {
            return Self::identity();
        }
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = 2.0 * std::f32::consts::PI * freq / sample_rate.max(1.0);
        let cos_w0 = w0.cos();
        let sin_w0 = w0.sin();
        // Shelf slope S = 1, so the alpha term simplifies to sin(w0)/2 * sqrt(2).
        let alpha = sin_w0 / 2.0 * std::f32::consts::SQRT_2;
        let two_sqrt_a_alpha = 2.0 * a.sqrt() * alpha;
        let (b0, b1, b2, a0, a1, a2) = if high {
            (
                a * ((a + 1.0) + (a - 1.0) * cos_w0 + two_sqrt_a_alpha),
                -2.0 * a * ((a - 1.0) + (a + 1.0) * cos_w0),
                a * ((a + 1.0) + (a - 1.0) * cos_w0 - two_sqrt_a_alpha),
                (a + 1.0) - (a - 1.0) * cos_w0 + two_sqrt_a_alpha,
                2.0 * ((a - 1.0) - (a + 1.0) * cos_w0),
                (a + 1.0) - (a - 1.0) * cos_w0 - two_sqrt_a_alpha,
            )
        } else {
            (
                a * ((a + 1.0) - (a - 1.0) * cos_w0 + two_sqrt_a_alpha),
                2.0 * a * ((a - 1.0) - (a + 1.0) * cos_w0),
                a * ((a + 1.0) - (a - 1.0) * cos_w0 - two_sqrt_a_alpha),
                (a + 1.0) + (a - 1.0) * cos_w0 + two_sqrt_a_alpha,
                -2.0 * ((a - 1.0) + (a + 1.0) * cos_w0),
                (a + 1.0) + (a - 1.0) * cos_w0 - two_sqrt_a_alpha,
            )
        };
        Self {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
        }
    }

    fn peak(sample_rate: f32, freq: f32, q: f32, gain_db: f32) -> Self {
        if gain_db.abs() < 0.001 {
            return Self::identity();
        }
        let a = 10f32.powf(gain_db / 40.0);
        let w0 = 2.0 * std::f32::consts::PI * freq / sample_rate.max(1.0);
        let cos_w0 = w0.cos();
        let alpha = w0.sin() / (2.0 * q.max(0.01));
        let b0 = 1.0 + alpha * a;
        let b1 = -2.0 * cos_w0;
        let b2 = 1.0 - alpha * a;
        let a0 = 1.0 + alpha / a;
        let a1 = -2.0 * cos_w0;
        let a2 = 1.0 - alpha / a;
        Self {
            b0: b0 / a0,
            b1: b1 / a0,
            b2: b2 / a0,
            a1: a1 / a0,
            a2: a2 / a0,
        }
    }

    fn process(&self, x: f32, z1: &mut f32, z2: &mut f32) -> f32 {
        let y = self.b0 * x + *z1;
        *z1 = self.b1 * x - self.a1 * y + *z2;
        *z2 = self.b2 * x - self.a2 * y;
        y
    }
}

/// Three-band EQ: low shelf at 200 Hz, peak at 1 kHz (Q 0.7), high shelf at
/// 4 kHz. `state` is 6 floats per channel (z1/z2 for each band).
fn apply_eq(
    buf: &mut [f32],
    low_db: f32,
    mid_db: f32,
    high_db: f32,
    sample_rate: f32,
    channels: usize,
    state: &mut [f32],
) {
    if low_db.abs() < 0.001 && mid_db.abs() < 0.001 && high_db.abs() < 0.001 {
        return;
    }
    let channels = channels.clamp(1, 2);
    let low = Biquad::low_shelf(sample_rate, 200.0, low_db);
    let mid = Biquad::peak(sample_rate, 1_000.0, 0.7, mid_db);
    let high = Biquad::high_shelf(sample_rate, 4_000.0, high_db);
    for (index, sample) in buf.iter_mut().enumerate() {
        let base = (index % channels) * 6;
        let mut value = *sample;
        let (mut low_z1, mut low_z2) = (state[base], state[base + 1]);
        value = low.process(value, &mut low_z1, &mut low_z2);
        state[base] = low_z1;
        state[base + 1] = low_z2;
        let (mut mid_z1, mut mid_z2) = (state[base + 2], state[base + 3]);
        value = mid.process(value, &mut mid_z1, &mut mid_z2);
        state[base + 2] = mid_z1;
        state[base + 3] = mid_z2;
        let (mut high_z1, mut high_z2) = (state[base + 4], state[base + 5]);
        value = high.process(value, &mut high_z1, &mut high_z2);
        state[base + 4] = high_z1;
        state[base + 5] = high_z2;
        *sample = value;
    }
}

/// Three-band EQ JNI entry. `state` is a 12-float array (2 channels x 3 bands
/// x 2 history values) carried across decoded buffers.
#[allow(non_snake_case)]
#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_applyEq<'frame>(
    mut env: EnvUnowned<'frame>,
    _class: JObject,
    samples: JFloatArray<'frame>,
    lowDb: jfloat,
    midDb: jfloat,
    highDb: jfloat,
    sampleRate: jfloat,
    channels: jint,
    state: JFloatArray<'frame>,
) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        let len = samples.len(env)?;
        let mut buf = vec![0.0f32; len];
        samples.get_region(env, 0, &mut buf)?;
        let mut eq_state = vec![0.0f32; 12];
        state.get_region(env, 0, &mut eq_state)?;
        apply_eq(
            &mut buf,
            lowDb,
            midDb,
            highDb,
            sampleRate,
            channels.max(1) as usize,
            &mut eq_state,
        );
        state.set_region(env, 0, &eq_state)?;
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

    fn rms(buf: &[f32]) -> f32 {
        (buf.iter().map(|sample| sample * sample).sum::<f32>() / buf.len() as f32).sqrt()
    }

    fn sine(freq: f32, sample_rate: f32, seconds: f32) -> Vec<f32> {
        let frames = (sample_rate * seconds) as usize;
        (0..frames)
            .map(|i| {
                (2.0 * std::f32::consts::PI * freq * i as f32 / sample_rate).sin() * 0.5
            })
            .collect()
    }

    #[test]
    fn flat_eq_is_identity() {
        let mut buf = [0.25f32, -0.5, 0.9, -0.9];
        let original = buf;
        let mut state = [0.0f32; 12];
        apply_eq(&mut buf, 0.0, 0.0, 0.0, 48_000.0, 2, &mut state);
        assert_eq!(buf, original);
        assert!(state.iter().all(|value| *value == 0.0));
    }

    #[test]
    fn low_boost_increases_low_frequency_rms() {
        let sample_rate = 48_000.0;
        let input = sine(100.0, sample_rate, 1.0);
        let mut output = input.clone();
        let mut state = [0.0f32; 12];
        apply_eq(&mut output, 12.0, 0.0, 0.0, sample_rate, 1, &mut state);
        let half = input.len() / 2;
        let input_rms = rms(&input[half..]);
        let output_rms = rms(&output[half..]);
        assert!(
            output_rms > input_rms * 1.5,
            "low shelf should boost 100 Hz: {input_rms} -> {output_rms}"
        );
    }

    #[test]
    fn high_cut_reduces_high_frequency_rms() {
        let sample_rate = 48_000.0;
        let input = sine(8_000.0, sample_rate, 1.0);
        let mut output = input.clone();
        let mut state = [0.0f32; 12];
        apply_eq(&mut output, 0.0, 0.0, -12.0, sample_rate, 1, &mut state);
        let half = input.len() / 2;
        let input_rms = rms(&input[half..]);
        let output_rms = rms(&output[half..]);
        assert!(
            output_rms < input_rms * 0.7,
            "high shelf should cut 8 kHz: {input_rms} -> {output_rms}"
        );
    }

    #[test]
    fn eq_state_carries_across_buffers() {
        let sample_rate = 48_000.0;
        let input = sine(100.0, sample_rate, 0.5);
        let mut one_shot = input.clone();
        let mut one_shot_state = [0.0f32; 12];
        apply_eq(
            &mut one_shot,
            6.0,
            -3.0,
            4.0,
            sample_rate,
            1,
            &mut one_shot_state,
        );

        let split = input.len() / 2;
        let mut first = input[..split].to_vec();
        let mut second = input[split..].to_vec();
        let mut split_state = [0.0f32; 12];
        apply_eq(
            &mut first,
            6.0,
            -3.0,
            4.0,
            sample_rate,
            1,
            &mut split_state,
        );
        apply_eq(
            &mut second,
            6.0,
            -3.0,
            4.0,
            sample_rate,
            1,
            &mut split_state,
        );
        let mut split_output = first;
        split_output.extend_from_slice(&second);
        let max_diff = one_shot
            .iter()
            .zip(split_output.iter())
            .map(|(a, b)| (a - b).abs())
            .fold(0.0f32, f32::max);
        assert!(
            max_diff < 1e-6,
            "split buffers should match the one-shot output (max diff {max_diff})"
        );
    }
}
