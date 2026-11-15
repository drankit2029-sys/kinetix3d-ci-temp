//! Kinetix3D Studio — Rust native core for `:engine-media`.
//!
//! Phase 3 will grow this into the AAudio/cpal audio engine and the
//! spectral-flux beat detector. For now it only carries the ABI probe used to
//! verify the cargo-ndk build and `System.loadLibrary` wiring on device.

use jni::objects::JObject;
use jni::sys::jint;
use jni::EnvUnowned;

/// ABI version of the native Rust core. Bump whenever the JNI contract
/// changes so callers can assert compatibility at startup.
const KINETIX_AUDIO_ABI_VERSION: jint = 1;

#[no_mangle]
pub extern "system" fn Java_com_kinetix3d_media_KinetixAudioNative_abiVersion<'frame>(
    _env: EnvUnowned<'frame>,
    _class: JObject,
) -> jint {
    KINETIX_AUDIO_ABI_VERSION
}
