/*
 * Kinetix3D Studio — core-platform-api
 * Thermal/memory pressure strategy feeding the eco-preview and FBO-flush policies.
 * API 29+ uses the platform thermal listener; API 26–28 polls the battery
 * temperature as a conservative proxy.
 */

package com.kinetix3d.platform.api

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

interface ThermalAndMemoryApiManager {
    fun currentThermalStatus(): ThermalStatus

    fun registerTrimCallback(cb: (TrimReason) -> Unit): AutoCloseable

    /**
     * Feeds one frame's render duration into the EMA-15 tracker and returns `true`
     * exactly when the moving average crosses [FRAME_TIME_BUDGET_MS] while rising
     * (edge-triggered). The eco-preview / cold-FBO-flush policy uses this to step
     * down preview resolution *before* the platform reaches
     * `THERMAL_STATUS_SEVERE`.
     */
    fun recordFrameRenderTime(nanos: Long): Boolean

    companion object {
        /** 25 ms ≈ a 40 fps floor; below that the preview is visibly stuttering. */
        const val FRAME_TIME_BUDGET_MS: Int = 25
        const val EMA_WINDOW_FRAMES: Int = 15
    }
}

enum class ThermalStatus {
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    SHUTDOWN,
    EMERGENCY,
    UNKNOWN,
}

enum class TrimReason {
    THERMAL,
    LOW_MEMORY,
    BACKGROUND,
    FOREGROUND,
    UI_HIDDEN,
    UNKNOWN,
}

class AndroidThermalAndMemoryApiManager(
    context: Context,
) : ThermalAndMemoryApiManager {
    private val appContext = context.applicationContext

    /** EMA-15 state; `-1` means "no frame sampled yet". RenderThread-only writer. */
    private val frameTimeEmaNanos = AtomicLong(NO_FRAME_SAMPLE)
    private val frameBudgetExceeded = AtomicBoolean(false)

    override fun currentThermalStatus(): ThermalStatus =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            thermalStatusFromPlatform(currentPlatformThermalStatus())
        } else {
            thermalStatusFromBatteryTemperature(readBatteryTemperatureTenths())
        }

    override fun recordFrameRenderTime(nanos: Long): Boolean {
        if (nanos <= 0L) return false
        val previous = frameTimeEmaNanos.get()
        val sample = nanos.toDouble()
        val ema =
            if (previous == NO_FRAME_SAMPLE) {
                sample
            } else {
                previous + EMA_ALPHA * (sample - previous)
            }
        frameTimeEmaNanos.set(ema.toLong())
        if (ema >= FRAME_TIME_BUDGET_NANOS) {
            // Edge-triggered: one callback per over-budget episode, not one per frame.
            return !frameBudgetExceeded.getAndSet(true)
        }
        frameBudgetExceeded.set(false)
        return false
    }

    override fun registerTrimCallback(cb: (TrimReason) -> Unit): AutoCloseable {
        val componentCallbacks =
            object : ComponentCallbacks2 {
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onTrimMemory(level: Int) {
                    cb(trimReasonOf(level))
                }

                override fun onLowMemory() {
                    cb(TrimReason.LOW_MEMORY)
                }

                override fun onConfigurationChanged(newConfig: Configuration) = Unit
            }
        appContext.registerComponentCallbacks(componentCallbacks)

        val thermalListener: Any? =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                registerThermalListenerQ { cb(TrimReason.THERMAL) }
            } else {
                null
            }
        val legacyPoller: HandlerThread? =
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                startLegacyPolling { cb(TrimReason.THERMAL) }
            } else {
                null
            }

        val closed = AtomicBoolean(false)
        return AutoCloseable {
            if (closed.compareAndSet(false, true)) {
                appContext.unregisterComponentCallbacks(componentCallbacks)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    unregisterThermalListenerQ(thermalListener)
                } else {
                    legacyPoller?.quitSafely()
                }
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun currentPlatformThermalStatus(): Int =
        appContext.getSystemService(PowerManager::class.java)?.currentThermalStatus
            ?: PowerManager.THERMAL_STATUS_NONE

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun thermalStatusFromPlatform(status: Int): ThermalStatus =
        when (status) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.NONE
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.LIGHT
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.MODERATE
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.SEVERE
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.CRITICAL
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.EMERGENCY
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.SHUTDOWN
            else -> ThermalStatus.UNKNOWN
        }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun registerThermalListenerQ(onThermalPressure: () -> Unit): Any? {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return null
        val listener =
            PowerManager.OnThermalStatusChangedListener { status ->
                // Only rising thermal pressure trims; cooling back to NONE/LIGHT
                // must not masquerade as a thermal emergency.
                if (thermalStatusFromPlatform(status).isAtLeastModerate()) onThermalPressure()
            }
        powerManager.addThermalStatusListener(listener)
        return listener
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun unregisterThermalListenerQ(token: Any?) {
        val powerManager = appContext.getSystemService(PowerManager::class.java) ?: return
        val listener = token as? PowerManager.OnThermalStatusChangedListener ?: return
        powerManager.removeThermalStatusListener(listener)
    }

    /**
     * API 26–28 fallback. An EMA of frame render time is not available at this
     * layer, so battery temperature (0.1 °C units) is the only system proxy.
     */
    private fun startLegacyPolling(onThermalChange: () -> Unit): HandlerThread {
        val thread = HandlerThread(LEGACY_POLL_THREAD_NAME).apply { start() }
        val handler = Handler(thread.looper)
        var lastStatus = thermalStatusFromBatteryTemperature(readBatteryTemperatureTenths())
        val poll =
            object : Runnable {
                override fun run() {
                    val status = thermalStatusFromBatteryTemperature(readBatteryTemperatureTenths())
                    if (status != lastStatus) {
                        lastStatus = status
                        if (status.isAtLeastModerate()) onThermalChange()
                    }
                    handler.postDelayed(this, LEGACY_POLL_INTERVAL_MS)
                }
            }
        handler.postDelayed(poll, LEGACY_POLL_INTERVAL_MS)
        return thread
    }

    private fun readBatteryTemperatureTenths(): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val sticky =
            ContextCompat.registerReceiver(
                appContext,
                null,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            ) ?: return Int.MIN_VALUE
        return sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
    }

    private fun thermalStatusFromBatteryTemperature(tenthsCelsius: Int): ThermalStatus {
        if (tenthsCelsius == Int.MIN_VALUE) return ThermalStatus.UNKNOWN
        val celsius = tenthsCelsius / 10.0
        return when {
            celsius < 40.0 -> ThermalStatus.NONE
            celsius < 45.0 -> ThermalStatus.LIGHT
            celsius < 50.0 -> ThermalStatus.MODERATE
            celsius < 55.0 -> ThermalStatus.SEVERE
            celsius < 60.0 -> ThermalStatus.CRITICAL
            celsius < 65.0 -> ThermalStatus.EMERGENCY
            else -> ThermalStatus.SHUTDOWN
        }
    }

    private fun ThermalStatus.isAtLeastModerate(): Boolean =
        when (this) {
            ThermalStatus.MODERATE,
            ThermalStatus.SEVERE,
            ThermalStatus.CRITICAL,
            ThermalStatus.EMERGENCY,
            ThermalStatus.SHUTDOWN,
            -> true

            ThermalStatus.NONE,
            ThermalStatus.LIGHT,
            ThermalStatus.UNKNOWN,
            -> false
        }

    @Suppress("DEPRECATION")
    private fun trimReasonOf(level: Int): TrimReason =
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            -> TrimReason.LOW_MEMORY

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> TrimReason.UI_HIDDEN

            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND,
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE,
            -> TrimReason.BACKGROUND

            else -> TrimReason.UNKNOWN
        }

    private companion object {
        const val LEGACY_POLL_THREAD_NAME = "kinetix-thermal-poller"
        const val LEGACY_POLL_INTERVAL_MS = 10_000L
        const val NO_FRAME_SAMPLE = -1L
        const val EMA_ALPHA = 2.0 / (ThermalAndMemoryApiManager.EMA_WINDOW_FRAMES + 1)
        const val FRAME_TIME_BUDGET_NANOS = ThermalAndMemoryApiManager.FRAME_TIME_BUDGET_MS * 1_000_000L
    }
}
