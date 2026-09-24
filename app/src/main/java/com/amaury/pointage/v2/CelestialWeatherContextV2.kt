package com.amaury.pointage.v2

import android.os.Handler
import android.os.Looper
import com.amaury.pointage.BuildConfig
import com.amaury.pointage.v2.engine.CelestialSnapshotV2
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.round

object CelestialWeatherContextV2 {
    data class State(
        val cloudCover: Double,
        val cloudCoverLow: Double?,
        val cloudCoverMid: Double?,
        val cloudCoverHigh: Double?,
        val weatherCode: Int?,
        val precipitationMm: Double?,
        val visibilityMeters: Double?,
        val fetchedAtMs: Long,
        val roundedLatitude: Double,
        val roundedLongitude: Double,
        val source: String
    ) {
        fun isFresh(nowMs: Long): Boolean =
            nowMs >= fetchedAtMs && nowMs - fetchedAtMs <= MAX_RENDER_AGE_MS

        val cloudTransmission: Double
            get() = (1.0 - cloudCover.coerceIn(0.0, 1.0) * 0.90).coerceIn(0.08, 1.0)
    }

    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, "HoraTrack-CelestialWeather").apply {
            priority = Thread.NORM_PRIORITY - 1
        }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var current: State? = null
    @Volatile private var inFlightKey: String? = null
    @Volatile private var lastAttemptMs: Long = 0L

    fun currentState(nowMs: Long = System.currentTimeMillis()): State? =
        current?.takeIf { it.isFresh(nowMs) }

    fun refreshIfNeeded(
        snapshot: CelestialSnapshotV2,
        onChanged: (() -> Unit)? = null
    ) {
        val endpoint = BuildConfig.CELESTIAL_WEATHER_ENDPOINT.trim()
        if (endpoint.isBlank() || !endpoint.startsWith("https://")) return

        val latitude = roundedCoordinate(snapshot.latitudeDeg)
        val longitude = roundedCoordinate(snapshot.longitudeDeg)
        val key = String.format(Locale.US, "%.2f:%.2f", latitude, longitude)
        val now = System.currentTimeMillis()
        val cached = current

        if (cached != null &&
            cached.roundedLatitude == latitude &&
            cached.roundedLongitude == longitude &&
            now - cached.fetchedAtMs < REFRESH_INTERVAL_MS
        ) return
        if (inFlightKey == key) return
        if (now - lastAttemptMs < RETRY_BACKOFF_MS) return

        inFlightKey = key
        lastAttemptMs = now

        executor.execute {
            val state = runCatching {
                val separator = if (endpoint.contains('?')) '&' else '?'
                val url = buildString {
                    append(endpoint)
                    append(separator)
                    append("latitude=").append(String.format(Locale.US, "%.2f", latitude))
                    append("&longitude=").append(String.format(Locale.US, "%.2f", longitude))
                    append("&current=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,weather_code,precipitation,visibility")
                    append("&timezone=UTC")
                }
                val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8_000
                    readTimeout = 10_000
                    requestMethod = "GET"
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("User-Agent", "HoraTrack-Celeste/2")
                }
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) error("weather http $code")
                    val json = connection.inputStream.bufferedReader().use { it.readText() }
                    parseCurrentWeather(
                        json = json,
                        fetchedAtMs = System.currentTimeMillis(),
                        latitude = latitude,
                        longitude = longitude,
                        source = endpoint
                    )
                } finally {
                    connection.disconnect()
                }
            }.getOrNull()

            inFlightKey = null
            if (state != null) {
                current = state
                onChanged?.let { callback -> mainHandler.post(callback) }
            }
        }
    }

    internal fun parseCurrentWeather(
        json: String,
        fetchedAtMs: Long,
        latitude: Double,
        longitude: Double,
        source: String
    ): State {
        val current = JSONObject(json).optJSONObject("current")
            ?: error("weather current absent")
        val cloudPercent = current.optDouble("cloud_cover", Double.NaN)
        require(cloudPercent.isFinite())

        fun optionalPercent(name: String): Double? =
            current.optDouble(name, Double.NaN)
                .takeIf { it.isFinite() }
                ?.div(100.0)
                ?.coerceIn(0.0, 1.0)

        fun optionalDouble(name: String): Double? =
            current.optDouble(name, Double.NaN).takeIf { it.isFinite() }

        return State(
            cloudCover = (cloudPercent / 100.0).coerceIn(0.0, 1.0),
            cloudCoverLow = optionalPercent("cloud_cover_low"),
            cloudCoverMid = optionalPercent("cloud_cover_mid"),
            cloudCoverHigh = optionalPercent("cloud_cover_high"),
            weatherCode = current.optInt("weather_code", Int.MIN_VALUE)
                .takeIf { it != Int.MIN_VALUE },
            precipitationMm = optionalDouble("precipitation"),
            visibilityMeters = optionalDouble("visibility"),
            fetchedAtMs = fetchedAtMs,
            roundedLatitude = latitude,
            roundedLongitude = longitude,
            source = source
        )
    }

    private fun roundedCoordinate(value: Double): Double = round(value * 100.0) / 100.0

    private const val REFRESH_INTERVAL_MS = 15 * 60_000L
    private const val RETRY_BACKOFF_MS = 60_000L
    private const val MAX_RENDER_AGE_MS = 45 * 60_000L
}
