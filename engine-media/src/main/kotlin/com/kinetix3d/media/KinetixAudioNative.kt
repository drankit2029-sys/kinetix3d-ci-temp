/*
 * Kinetix3D Studio — :engine-media native bridge (Rust).
 *
 * Day-1 placeholder proving the cargo-ndk -> jniLibs -> System.loadLibrary
 * chain. Phase 3 (roadmap §4.3) grows this into the real audio engine.
 * The JNI symbol matches `Java_com_kinetix3d_media_KinetixAudioNative_abiVersion`
 * in engine-media/rust/src/lib.rs.
 */

package com.kinetix3d.media

import android.util.Log

/**
 * Bridge to `libkinetix_audio.so` (Rust). Init logs the loaded ABI version.
 */
object KinetixAudioNative {
    private const val TAG = "KinetixAudioNative"

    init {
        System.loadLibrary("kinetix_audio")
        Log.i(TAG, "libkinetix_audio loaded, abi=${abiVersion()}")
    }

    /** ABI version of the native Rust core; bump with the JNI contract. */
    external fun abiVersion(): Int
}
