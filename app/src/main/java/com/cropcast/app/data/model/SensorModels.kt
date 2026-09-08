package com.cropcast.app.data.model

data class SensorReading(
    val temperature: Double = 0.0,
    val humidity: Double = 0.0,
    val soilMoisture: Double = 0.0,
    val soilPh: Double = 0.0,
    val nitrogen: Double = 0.0,
    val phosphorus: Double = 0.0,
    val potassium: Double = 0.0,
    val lightIntensity: Double = 0.0,
    val timestamp: Long = 0L
)

/** Shared plausibility gate for imported, simulated, and live sensor readings. */
fun SensorReading.isValidForRecommendation(): Boolean =
    timestamp > 0L &&
        temperature.isFinite() && temperature in -20.0..80.0 &&
        humidity.isFinite() && humidity in 0.0..100.0 &&
        soilMoisture.isFinite() && soilMoisture in 0.0..100.0 &&
        soilPh.isFinite() && soilPh in 0.0..14.0 &&
        nitrogen.isFinite() && nitrogen >= 0.0 &&
        phosphorus.isFinite() && phosphorus >= 0.0 &&
        potassium.isFinite() && potassium >= 0.0 &&
        lightIntensity.isFinite() && lightIntensity >= 0.0

data class MonthlySensorSummary(
    val monthKey: String = "",
    val sampleCount: Int = 0,
    val firstReadingAt: Long = 0L,
    val lastReadingAt: Long = 0L,
    val average: SensorReading = SensorReading(),
    val minimum: SensorReading = SensorReading(),
    val maximum: SensorReading = SensorReading(),
    val standardDeviation: SensorReading = SensorReading()
) {
    val hasData: Boolean get() = sampleCount > 0
}

data class DeviceStatus(
    val online: Boolean = false,
    val lastSeen: Long = 0L,
    val firmware: String = "",
    val sensors: List<String> = emptyList()
)

data class AlertSettings(
    val enabled: Boolean = true,
    val lowMoistureAlertEnabled: Boolean = true,
    val soilMoistureThreshold: Double = 40.0,
    val highTemperatureThreshold: Double = 35.0,
    val minSoilPh: Double = 5.5,
    val maxSoilPh: Double = 7.5,
    val deviceOfflineMinutes: Int = 2,
    val repeatIntervalMinutes: Int = 30,
    val soundEnabled: Boolean = true,
    val vibrationEnabled: Boolean = true,
    val farmName: String = "My Farm",
    val deviceName: String = "Field Sensor 01",
    val farmLatitude: String = "",
    val farmLongitude: String = "",
    val currentCrop: String = "Rice",
    val cropVariety: String = "IR64",
    val plantingDate: String = "",
    val language: String = "English",
    val darkModeEnabled: Boolean = false,
    val cloudHistoryEnabled: Boolean = true,
    val diagnosticsEnabled: Boolean = false
)

data class AccountInfo(
    val displayName: String = "Guest Farmer",
    val role: String = "Farmer",
    val email: String = "Anonymous account",
    val isAnonymous: Boolean = true
)

data class AlertEvent(
    val id: String = "",
    val type: String = "soil_moisture_low",
    val message: String = "",
    val value: Double = 0.0,
    val timestamp: Long = 0L,
    val acknowledged: Boolean = false
)

data class SeedRecommendation(
    val name: String = "",
    val variety: String = "",
    val icon: String = "🌱",
    val growDays: Int = 0,
    val soil: String = "",
    val confidence: Int = 0
)

data class MonthlyCropRecommendation(
    val monthKey: String = "",
    val cropName: String = "",
    val cropVariety: String = "",
    val cropIcon: String = "🌱",
    val growDays: Int = 0,
    val soil: String = "",
    val score: Int = 0,
    val runnerUpName: String = "",
    val runnerUpScore: Int = 0,
    val scoreByCrop: Map<String, Int> = emptyMap(),
    val sampleCount: Int = 0,
    val firstReadingAt: Long = 0L,
    val lastReadingAt: Long = 0L,
    val basedOnMonths: List<String> = emptyList(),
    val recommendationType: String = "observed_month",
    val confidenceLabel: String = "Low confidence",
    val matchingFields: List<String> = emptyList(),
    val limitingFields: List<String> = emptyList(),
    val stabilityLabel: String = "Unknown stability",
    val unstableFields: List<String> = emptyList(),
    val variabilityByField: Map<String, Double> = emptyMap(),
    val minimumByField: Map<String, Double> = emptyMap(),
    val maximumByField: Map<String, Double> = emptyMap(),
    val trendByField: Map<String, Double> = emptyMap(),
    val seasonLabel: String = "",
    val seasonNote: String = "",
    val feedbackSamplesUsed: Int = 0,
    val generatedAt: Long = 0L,
    val algorithmVersion: String = "monthly-v2"
) {
    val recommendedCrop: SeedRecommendation
        get() = SeedRecommendation(cropName, cropVariety, cropIcon, growDays, soil, score)

    val runnerUp: SeedRecommendation?
        get() = runnerUpName.takeIf { it.isNotBlank() }?.let {
            SeedRecommendation(name = it, confidence = runnerUpScore)
        }
}

data class CropOutcomeFeedback(
    val id: String = "",
    val monthKey: String = "",
    val recommendedCrop: String = "",
    val recommendationScore: Double = 0.0,
    val plantedCrop: String = "",
    val harvestedKg: Double = 0.0,
    val rating: Int = 0,
    val problems: String = "",
    val temperature: Double = 0.0,
    val humidity: Double = 0.0,
    val soilMoisture: Double = 0.0,
    val soilPh: Double = 0.0,
    val nitrogen: Double = 0.0,
    val phosphorus: Double = 0.0,
    val potassium: Double = 0.0,
    val rainfallMm: Double? = null,
    val rainfallSource: String = "",
    val farmLatitude: String = "",
    val farmLongitude: String = "",
    val modelVariant: String = "",
    val submittedAt: Long = 0L
)
