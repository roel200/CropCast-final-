package com.cropcast.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.min

enum class RainfallStatus {
    NOT_CONFIGURED,
    LOADING,
    AVAILABLE,
    UNAVAILABLE
}

data class RainfallEstimate(
    val millimeters: Double,
    val startDate: String,
    val endDate: String,
    val source: String = "Open-Meteo historical weather"
)

data class RainfallState(
    val status: RainfallStatus = RainfallStatus.NOT_CONFIGURED,
    val estimate: RainfallEstimate? = null,
    val message: String = ""
)

class WeatherRainfallRepository {
    suspend fun loadRecentRainfall(
        latitude: Double,
        longitude: Double,
        latestReadingAt: Long
    ): RainfallEstimate = withContext(Dispatchers.IO) {
        require(latitude in -90.0..90.0) { "Latitude must be between -90 and 90" }
        require(longitude in -180.0..180.0) { "Longitude must be between -180 and 180" }

        val yesterday = LocalDate.now(ZoneOffset.UTC).minusDays(1)
        val readingDate = latestReadingAt.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
            ?: yesterday
        val endDate = if (readingDate.isBefore(yesterday)) readingDate else yesterday
        val startDate = endDate.minusDays(RAINFALL_WINDOW_DAYS - 1L)
        val endpoint = URL(
            "$ARCHIVE_ENDPOINT?latitude=$latitude&longitude=$longitude" +
                "&start_date=$startDate&end_date=$endDate" +
                "&daily=rain_sum&precipitation_unit=mm&timezone=auto"
        )
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("Accept", "application/json")
            val responseCode = connection.responseCode
            val body = (if (responseCode in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() }
                .orEmpty()
            check(responseCode in 200..299) {
                JSONObject(body.ifBlank { "{}" }).optString("reason", "Weather service returned $responseCode")
            }
            RainfallEstimate(
                millimeters = parseRainfallTotal(body),
                startDate = startDate.toString(),
                endDate = endDate.toString()
            )
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ARCHIVE_ENDPOINT = "https://archive-api.open-meteo.com/v1/archive"
        private const val RAINFALL_WINDOW_DAYS = 30L

        internal fun parseRainfallTotal(responseBody: String): Double {
            val root = JSONObject(responseBody)
            val rain = root.optJSONObject("daily")?.optJSONArray("rain_sum")
                ?: error("Weather response did not include daily rainfall")
            var total = 0.0
            var samples = 0
            for (index in 0 until rain.length()) {
                if (!rain.isNull(index)) {
                    val value = rain.optDouble(index, Double.NaN)
                    if (value.isFinite() && value >= 0.0) {
                        total += value
                        samples += 1
                    }
                }
            }
            check(samples > 0) { "Weather response did not contain usable rainfall values" }
            return total
        }

        fun parseCoordinates(latitude: String, longitude: String): Pair<Double, Double>? {
            val parsedLatitude = latitude.trim().toDoubleOrNull() ?: return null
            val parsedLongitude = longitude.trim().toDoubleOrNull() ?: return null
            if (parsedLatitude !in -90.0..90.0 || parsedLongitude !in -180.0..180.0) return null
            return parsedLatitude to parsedLongitude
        }
    }
}
