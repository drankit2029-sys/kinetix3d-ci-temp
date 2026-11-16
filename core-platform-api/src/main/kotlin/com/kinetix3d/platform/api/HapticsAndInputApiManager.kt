/*
 * Kinetix3D Studio — core-platform-api
 * Haptic strategy for the vernier scrub wheel and keyframe snapping.
 * VIBRATE is checked at runtime: a denied permission degrades to silence, never
 * to a crash.
 */

package com.kinetix3d.platform.api

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat

interface HapticsAndInputApiManager {
    fun tickVernierDetent(intensity: Float)

    fun clickKeyframeSnap()
}

class AndroidHapticsAndInputApiManager(
    context: Context,
) : HapticsAndInputApiManager {
    private val appContext = context.applicationContext
    private val vibrator: Vibrator? by lazy { resolveVibrator() }

    override fun tickVernierDetent(intensity: Float) {
        val scale = intensity.coerceIn(0f, 1f)
        if (scale <= 0f) return
        val effect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                detentEffect(scale)
            } else {
                VibrationEffect.createOneShot(ONE_SHOT_DURATION_MS, ONE_SHOT_AMPLITUDE)
            }
        vibrate(effect)
    }

    override fun clickKeyframeSnap() {
        val effect =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                snapEffect()
            } else {
                VibrationEffect.createOneShot(ONE_SHOT_DURATION_MS, ONE_SHOT_AMPLITUDE)
            }
        vibrate(effect)
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun detentEffect(scale: Float): VibrationEffect {
        val primitive =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                VibrationEffect.Composition.PRIMITIVE_LOW_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_TICK
            }
        return VibrationEffect
            .startComposition()
            .addPrimitive(primitive, scale, 0)
            .compose()
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun snapEffect(): VibrationEffect =
        VibrationEffect
            .startComposition()
            .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, SNAP_SCALE, 0)
            .compose()

    @SuppressLint("MissingPermission")
    private fun vibrate(effect: VibrationEffect) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.VIBRATE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val device = vibrator ?: return
        if (device.hasVibrator()) {
            device.vibrate(effect)
        }
    }

    private fun resolveVibrator(): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            vibratorFromManager()
        } else {
            appContext.getSystemService(Vibrator::class.java)
        }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun vibratorFromManager(): Vibrator? =
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private companion object {
        const val ONE_SHOT_DURATION_MS = 5L
        const val ONE_SHOT_AMPLITUDE = 80
        const val SNAP_SCALE = 1.0f
    }
}
