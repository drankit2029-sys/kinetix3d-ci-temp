/*
 * Kinetix3D Studio — :app composition root.
 * Application.onCreate resolves the device profile (cached, else conservative
 * Tier-2), wires PlatformApiProvider, then runs the first-boot benchmark and the
 * DirtyRenderLock crash-guard inspection off the main thread. UI modules observe
 * the StateFlows; engine modules consume the PlatformApi façade.
 */

package com.kinetix3d.studio

import android.app.Application
import android.util.Log
import com.kinetix3d.model.DeviceCapabilityProfile
import com.kinetix3d.model.DirtyRenderLock
import com.kinetix3d.platform.HardwareCapabilityProfiler
import com.kinetix3d.platform.PlatformApiProvider
import com.kinetix3d.platform.api.PlatformApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class KinetixApplication : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var platformApiOrNull: PlatformApi? = null

    /**
     * The single OS façade consumed by engine modules. Constructed during
     * [onCreate] around the cached profile, then rebuilt off the main thread when
     * the first-boot benchmark finishes with a different profile.
     */
    val platformApi: PlatformApi
        get() = checkNotNull(platformApiOrNull) { "PlatformApiProvider has not been initialized" }

    private val mutableDeviceProfile =
        MutableStateFlow(DeviceCapabilityProfile.tier2Default(approximate = true))

    /** Live hardware profile; starts conservative and is replaced by the probe result. */
    val deviceProfile: StateFlow<DeviceCapabilityProfile> = mutableDeviceProfile.asStateFlow()

    private val mutableRenderGuard = MutableStateFlow<DirtyRenderLock.Inspection?>(null)

    /**
     * Crash-guard inspection; `null` until the asynchronous startup check
     * completes. [DirtyRenderLock.Inspection.requiresRecovery] arms Safe Recovery
     * Mode for the next render pass.
     */
    val renderGuard: StateFlow<DirtyRenderLock.Inspection?> = mutableRenderGuard.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        val profiler = HardwareCapabilityProfiler(this)
        val cachedProfile = profiler.loadCachedProfile()
        val initialProfile = cachedProfile ?: DeviceCapabilityProfile.tier2Default(approximate = true)
        mutableDeviceProfile.value = initialProfile
        platformApiOrNull = PlatformApiProvider.create(this, initialProfile)

        appScope.launch {
            inspectRenderGuard()
            if (cachedProfile == null) {
                val probedProfile = profiler.runFirstBootBenchmark()
                if (probedProfile != mutableDeviceProfile.value) {
                    mutableDeviceProfile.value = probedProfile
                    platformApiOrNull = PlatformApiProvider.create(this@KinetixApplication, probedProfile)
                }
            }
        }
    }

    private suspend fun inspectRenderGuard() {
        val inspection =
            withContext(Dispatchers.IO) {
                try {
                    val directory = File(filesDir, DirtyRenderLock.DIRECTORY_NAME)
                    DirtyRenderLock(directory).use { lock -> lock.inspect() }
                } catch (throwable: Throwable) {
                    // An unreadable guard must never trigger Safe Recovery Mode;
                    // CORRUPT is the "ignore the record" branch of the contract.
                    Log.w(TAG, "DirtyRenderLock inspection failed", throwable)
                    DirtyRenderLock.Inspection(DirtyRenderLock.State.CORRUPT)
                }
            }
        if (inspection.requiresRecovery) {
            Log.w(
                TAG,
                "Safe Recovery armed: interrupted frame generation=${inspection.generation} " +
                    "nodeIdHash=${inspection.nodeIdHash}",
            )
        }
        mutableRenderGuard.value = inspection
    }

    override fun onTerminate() {
        appScope.cancel()
        super.onTerminate()
    }

    private companion object {
        const val TAG = "KinetixApplication"
    }
}
