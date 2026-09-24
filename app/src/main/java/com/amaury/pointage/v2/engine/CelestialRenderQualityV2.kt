package com.amaury.pointage.v2.engine

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

enum class CelestialRenderQualityV2 {
    REDUCED,
    BALANCED,
    HIGH;

    val maxPanoramaWidthPx: Int
        get() = when (this) {
            REDUCED -> 640
            BALANCED -> 900
            HIGH -> 1080
        }

    val maxPanoramaHeightPx: Int
        get() = when (this) {
            REDUCED -> 1280
            BALANCED -> 1600
            HIGH -> 1920
        }

    val maxCloudClusters: Int
        get() = when (this) {
            REDUCED -> 5
            BALANCED -> 8
            HIGH -> 11
        }
}

/**
 * Politique pure : seule la qualité graphique varie.
 *
 * Les règles astronomiques, météo, GPS et métier restent strictement identiques
 * quel que soit le niveau retenu.
 */
object CelestialRenderQualityPolicyV2 {
    fun resolve(
        lowRamDevice: Boolean,
        powerSaveMode: Boolean,
        thermalSevere: Boolean,
        memoryClassMb: Int
    ): CelestialRenderQualityV2 {
        if (lowRamDevice || powerSaveMode || thermalSevere || memoryClassMb < 256) {
            return CelestialRenderQualityV2.REDUCED
        }
        if (memoryClassMb < 384) {
            return CelestialRenderQualityV2.BALANCED
        }
        return CelestialRenderQualityV2.HIGH
    }
}

object CelestialRenderQualityProviderV2 {
    @Volatile private var cachedQuality: CelestialRenderQualityV2? = null
    @Volatile private var cachedAtElapsedMs: Long = Long.MIN_VALUE

    fun current(
        context: Context,
        nowElapsedMs: Long = SystemClock.elapsedRealtime()
    ): CelestialRenderQualityV2 {
        val cached = cachedQuality
        if (cached != null &&
            cachedAtElapsedMs != Long.MIN_VALUE &&
            nowElapsedMs - cachedAtElapsedMs in 0 until CACHE_AGE_MS
        ) {
            return cached
        }

        synchronized(this) {
            val secondCached = cachedQuality
            if (secondCached != null &&
                cachedAtElapsedMs != Long.MIN_VALUE &&
                nowElapsedMs - cachedAtElapsedMs in 0 until CACHE_AGE_MS
            ) {
                return secondCached
            }

            val activityManager =
                context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val powerManager =
                context.getSystemService(Context.POWER_SERVICE) as? PowerManager

            val lowRam = activityManager?.isLowRamDevice ?: false
            val memoryClass = activityManager?.memoryClass ?: 256
            val powerSave = powerManager?.isPowerSaveMode ?: false
            val thermalSevere = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                (powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE) >=
                    PowerManager.THERMAL_STATUS_SEVERE
            } else {
                false
            }

            return CelestialRenderQualityPolicyV2.resolve(
                lowRamDevice = lowRam,
                powerSaveMode = powerSave,
                thermalSevere = thermalSevere,
                memoryClassMb = memoryClass
            ).also {
                cachedQuality = it
                cachedAtElapsedMs = nowElapsedMs
            }
        }
    }

    private const val CACHE_AGE_MS = 5_000L
}
