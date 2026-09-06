package com.cropcast.app.data

import com.cropcast.app.data.model.MonthlyCropRecommendation
import com.cropcast.app.data.model.CropOutcomeFeedback
import com.cropcast.app.data.model.MonthlySensorSummary
import com.cropcast.app.data.model.SeedRecommendation
import com.cropcast.app.data.model.SensorReading
import com.cropcast.app.data.model.isValidForRecommendation
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class CropScoreBreakdown(
    val crop: SeedRecommendation,
    val fieldScores: Map<String, Int>,
    val matchingFields: List<String>,
    val limitingFields: List<String>,
    val profileOrder: Int
)

object SeedRecommendationEngine {
    const val MIN_MONTHLY_SAMPLES = 8
    const val ALGORITHM_VERSION = "monthly-v2"
    const val OBSERVED_MONTH = "observed_month"
    const val NEXT_MONTH_FORECAST = "next_month_forecast"

    private data class CropProfile(
        val name: String,
        val variety: String,
        val icon: String,
        val days: Int,
        val soil: String,
        val temp: ClosedFloatingPointRange<Double>,
        val humidity: ClosedFloatingPointRange<Double>,
        val moisture: ClosedFloatingPointRange<Double>,
        val ph: ClosedFloatingPointRange<Double>,
        val nitrogen: ClosedFloatingPointRange<Double>,
        val phosphorus: ClosedFloatingPointRange<Double>,
        val potassium: ClosedFloatingPointRange<Double>
    )

    // NPK, temperature, humidity, and pH values mirror
    // data/processed/crop_requirements_selected.json. The source does not state
    // an NPK unit, so these are prototype suitability ranges until the RS485
    // readings are checked against a laboratory soil test. Moisture ranges are
    // explicit local-calibration defaults recorded in that processed table.
    private val crops = listOf(
        CropProfile(
            "Tomato", "Roma", "🍅", 80, "Loamy",
            temp = 18.0..25.0,
            humidity = 65.0..75.0,
            moisture = 40.0..70.0,
            ph = 6.0..6.8,
            nitrogen = 80.0..150.0,
            phosphorus = 50.0..70.0,
            potassium = 100.0..150.0
        ),
        CropProfile(
            "Okra", "Native", "🌱", 55, "Well-drained loam",
            temp = 22.0..35.0,
            humidity = 50.0..60.0,
            moisture = 45.0..75.0,
            ph = 6.0..6.8,
            nitrogen = 80.0..120.0,
            phosphorus = 40.0..80.0,
            potassium = 60.0..120.0
        ),
        CropProfile(
            "Alugbati", "Green stem", "🌿", 60, "Rich loam",
            temp = 25.0..35.0,
            humidity = 50.0..70.0,
            moisture = 55.0..85.0,
            ph = 6.5..7.5,
            nitrogen = 180.0..180.0,
            phosphorus = 90.0..90.0,
            potassium = 120.0..120.0
        ),
        CropProfile(
            "Potato", "Common", "🥔", 90, "Loose, well-drained loam",
            temp = 12.0..18.0,
            humidity = 80.0..90.0,
            moisture = 50.0..75.0,
            ph = 4.8..5.5,
            nitrogen = 80.0..150.0,
            phosphorus = 50.0..100.0,
            potassium = 100.0..200.0
        ),
        CropProfile(
            "Rice", "Lowland", "🌾", 120, "Clay loam / paddy",
            temp = 20.0..27.0,
            humidity = 80.0..83.0,
            moisture = 70.0..95.0,
            ph = 5.5..6.8,
            nitrogen = 60.0..100.0,
            phosphorus = 35.0..58.0,
            potassium = 36.0..44.0
        ),
        CropProfile(
            "Corn", "Yellow", "🌽", 90, "Well-drained loam",
            temp = 18.0..27.0,
            humidity = 65.0..75.0,
            moisture = 45.0..70.0,
            ph = 5.5..7.0,
            nitrogen = 150.0..200.0,
            phosphorus = 30.0..70.0,
            potassium = 40.0..140.0
        ),
        CropProfile(
            "Eggplant", "Native", "🍆", 75, "Well-drained loam",
            temp = 24.0..30.0,
            humidity = 60.0..70.0,
            moisture = 50.0..75.0,
            ph = 5.5..6.5,
            nitrogen = 80.0..120.0,
            phosphorus = 50.0..80.0,
            potassium = 100.0..150.0
        ),
        CropProfile(
            "Cucumber", "Slicing", "🥒", 55, "Well-drained loam",
            temp = 15.0..33.0,
            humidity = 60.0..80.0,
            moisture = 60.0..85.0,
            ph = 5.5..6.8,
            nitrogen = 150.0..200.0,
            phosphorus = 50.0..100.0,
            potassium = 150.0..200.0
        ),
        CropProfile(
            "Cabbage", "Green", "🥬", 80, "Fertile loam",
            temp = 15.0..20.0,
            humidity = 60.0..70.0,
            moisture = 60.0..85.0,
            ph = 6.0..7.0,
            nitrogen = 120.0..150.0,
            phosphorus = 80.0..100.0,
            potassium = 150.0..200.0
        ),
        CropProfile(
            "Sweet Potato", "Common", "🍠", 120, "Loose sandy loam",
            temp = 24.0..26.0,
            humidity = 60.0..80.0,
            moisture = 45.0..70.0,
            ph = 5.5..6.5,
            nitrogen = 80.0..120.0,
            phosphorus = 40.0..60.0,
            potassium = 100.0..150.0
        ),
        CropProfile(
            "Lettuce", "Leaf", "🥬", 45, "Fertile loam",
            temp = 7.0..24.0,
            humidity = 60.0..70.0,
            moisture = 60.0..85.0,
            ph = 6.0..7.0,
            nitrogen = 80.0..140.0,
            phosphorus = 40.0..70.0,
            potassium = 100.0..200.0
        ),
        CropProfile(
            "Spinach", "Leaf", "🌿", 45, "Fertile loam",
            temp = 6.0..24.0,
            humidity = 60.0..70.0,
            moisture = 60.0..85.0,
            ph = 6.0..7.0,
            nitrogen = 80.0..120.0,
            phosphorus = 40.0..60.0,
            potassium = 90.0..130.0
        )
    )

    /** Returns every crop score, sorted by score and then the existing profile order. */
    fun scoreAll(
        summary: MonthlySensorSummary,
        feedback: List<CropOutcomeFeedback> = emptyList()
    ): List<CropScoreBreakdown> {
        if (!summary.hasData) return emptyList()

        return crops.mapIndexed { profileOrder, crop ->
            val fieldScores = linkedMapOf(
                "temperature" to rangeScore(summary.average.temperature, crop.temp).roundToInt(),
                "humidity" to rangeScore(summary.average.humidity, crop.humidity).roundToInt(),
                "soilMoisture" to rangeScore(summary.average.soilMoisture, crop.moisture).roundToInt(),
                "soilPh" to rangeScore(summary.average.soilPh, crop.ph).roundToInt(),
                "nitrogen" to rangeScore(summary.average.nitrogen, crop.nitrogen).roundToInt(),
                "phosphorus" to rangeScore(summary.average.phosphorus, crop.phosphorus).roundToInt(),
                "potassium" to rangeScore(summary.average.potassium, crop.potassium).roundToInt()
            )
            val rawScore = fieldScores.values.average().roundToInt()
            val score = (rawScore + outcomeAdjustment(crop.name, feedback)).coerceIn(0, 100)
            CropScoreBreakdown(
                crop = SeedRecommendation(crop.name, crop.variety, crop.icon, crop.days, crop.soil, score),
                fieldScores = fieldScores,
                matchingFields = fieldScores.entries.sortedByDescending { it.value }.take(3).map { it.key },
                limitingFields = fieldScores.entries.sortedBy { it.value }.take(3).map { it.key },
                profileOrder = profileOrder
            )
        }.sortedWith(compareByDescending<CropScoreBreakdown> { it.crop.confidence }.thenBy { it.profileOrder })
    }

    /** Keeps the existing API used by the dashboard and older callers. */
    fun recommend(
        summary: MonthlySensorSummary,
        feedback: List<CropOutcomeFeedback> = emptyList()
    ): SeedRecommendation? = scoreAll(summary, feedback).firstOrNull()?.crop

    fun recommendForMonth(
        summary: MonthlySensorSummary,
        minimumSamples: Int = MIN_MONTHLY_SAMPLES,
        feedback: List<CropOutcomeFeedback> = emptyList()
    ): MonthlyCropRecommendation? {
        if (summary.sampleCount < minimumSamples || !summary.hasData) return null
        return buildRecommendation(
            summary = summary,
            recommendationType = OBSERVED_MONTH,
            basedOnMonths = listOf(summary.monthKey),
            minimumSamples = minimumSamples,
            limitedHistory = false,
            feedback = feedback
        )
    }

    fun recommendHistory(
        summaries: List<MonthlySensorSummary>,
        currentMonth: YearMonth = YearMonth.now(ZoneOffset.UTC),
        minimumSamples: Int = MIN_MONTHLY_SAMPLES
    ): List<MonthlyCropRecommendation> =
        summaries
            .mapNotNull { summary ->
                val month = parseMonth(summary.monthKey) ?: return@mapNotNull null
                if (month >= currentMonth) return@mapNotNull null
                recommendForMonth(summary, minimumSamples)
            }
            .distinctBy { it.monthKey }
            .sortedByDescending { parseMonth(it.monthKey) }

    fun recommendNextMonth(
        summaries: List<MonthlySensorSummary>,
        currentMonth: YearMonth = YearMonth.now(ZoneOffset.UTC),
        minimumSamples: Int = MIN_MONTHLY_SAMPLES,
        feedback: List<CropOutcomeFeedback> = emptyList()
    ): MonthlyCropRecommendation? {
        val eligible = summaries
            .filter { it.sampleCount >= minimumSamples && it.hasData }
            .mapNotNull { summary -> parseMonth(summary.monthKey)?.let { it to summary } }
            .filter { (month, _) -> month < currentMonth }
            .distinctBy { it.first }
            .sortedByDescending { it.first }

        val latest = eligible.firstOrNull() ?: return null
        val latestMonth = latest.first
        val sameCalendarMonth = eligible
            .drop(1)
            .filter { (month, _) -> month.monthValue == latestMonth.monthValue }
        val precedingMonths = eligible
            .drop(1)
            .take(3)

        data class WeightedProfile(
            val reading: SensorReading,
            val weight: Double,
            val monthKeys: List<String>
        )

        val signals = mutableListOf(
            WeightedProfile(latest.second.average, 0.60, listOf(latest.second.monthKey))
        )
        if (sameCalendarMonth.isNotEmpty()) {
            signals += WeightedProfile(
                averageReadings(sameCalendarMonth.map { it.second }),
                0.25,
                sameCalendarMonth.map { it.second.monthKey }
            )
        }
        if (precedingMonths.isNotEmpty()) {
            val recencyWeighted = precedingMonths.mapIndexed { index, (_, summary) ->
                summary.average to (3 - index).toDouble()
            }
            signals += WeightedProfile(
                blendReadings(recencyWeighted),
                0.15,
                precedingMonths.map { it.second.monthKey }
            )
        }

        val totalWeight = signals.sumOf { it.weight }
        val forecastAverage = blendReadings(signals.map { it.reading to it.weight / totalWeight })
        val targetMonth = latestMonth.plusMonths(1)
        val forecastSummary = MonthlySensorSummary(
            monthKey = targetMonth.toString(),
            sampleCount = latest.second.sampleCount,
            firstReadingAt = latest.second.firstReadingAt,
            lastReadingAt = latest.second.lastReadingAt,
            average = forecastAverage,
            minimum = latest.second.minimum,
            maximum = latest.second.maximum,
            standardDeviation = latest.second.standardDeviation
        )
        val basedOnMonths = signals.flatMap { it.monthKeys }.distinct()
        val trendByField = eligible.getOrNull(1)?.second?.let { previous ->
            readingDifference(latest.second.average, previous.average)
        }.orEmpty()

        return buildRecommendation(
            summary = forecastSummary,
            recommendationType = NEXT_MONTH_FORECAST,
            basedOnMonths = basedOnMonths,
            minimumSamples = minimumSamples,
            limitedHistory = eligible.size < 2,
            feedback = feedback,
            trendByField = trendByField
        )
    }

    private fun buildRecommendation(
        summary: MonthlySensorSummary,
        recommendationType: String,
        basedOnMonths: List<String>,
        minimumSamples: Int,
        limitedHistory: Boolean,
        feedback: List<CropOutcomeFeedback>,
        trendByField: Map<String, Double> = emptyMap()
    ): MonthlyCropRecommendation? {
        val scores = scoreAll(summary, feedback)
        val top = scores.firstOrNull() ?: return null
        val runnerUp = scores.getOrNull(1)
        val variability = readingFields(summary.standardDeviation)
        val unstableFields = variability.filter { (field, deviation) ->
            deviation > variabilityThreshold(field)
        }.keys.toList()
        val season = seasonalContext(summary.monthKey)
        val feedbackSamples = eligibleFeedback(top.crop.name, feedback).size
        return MonthlyCropRecommendation(
            monthKey = summary.monthKey,
            cropName = top.crop.name,
            cropVariety = top.crop.variety,
            cropIcon = top.crop.icon,
            growDays = top.crop.growDays,
            soil = top.crop.soil,
            score = top.crop.confidence,
            runnerUpName = runnerUp?.crop?.name.orEmpty(),
            runnerUpScore = runnerUp?.crop?.confidence ?: 0,
            scoreByCrop = scores.associate { it.crop.name to it.crop.confidence },
            sampleCount = summary.sampleCount,
            firstReadingAt = summary.firstReadingAt,
            lastReadingAt = summary.lastReadingAt,
            basedOnMonths = basedOnMonths,
            recommendationType = recommendationType,
            confidenceLabel = confidenceLabel(
                score = top.crop.confidence,
                runnerUpScore = runnerUp?.crop?.confidence ?: 0,
                sampleCount = summary.sampleCount,
                minimumSamples = minimumSamples,
                limitedHistory = limitedHistory,
                highVariability = unstableFields.size >= 3
            ),
            matchingFields = top.matchingFields,
            limitingFields = top.limitingFields,
            stabilityLabel = when {
                unstableFields.isEmpty() -> "Stable readings"
                unstableFields.size <= 2 -> "Some variability"
                else -> "High variability"
            },
            unstableFields = unstableFields,
            variabilityByField = variability,
            minimumByField = readingFields(summary.minimum),
            maximumByField = readingFields(summary.maximum),
            trendByField = trendByField,
            seasonLabel = season.first,
            seasonNote = season.second,
            feedbackSamplesUsed = feedbackSamples,
            generatedAt = System.currentTimeMillis(),
            algorithmVersion = ALGORITHM_VERSION
        )
    }

    private fun confidenceLabel(
        score: Int,
        runnerUpScore: Int,
        sampleCount: Int,
        minimumSamples: Int,
        limitedHistory: Boolean,
        highVariability: Boolean
    ): String = when {
        limitedHistory -> "Limited history"
        highVariability -> "Moderate confidence"
        score >= 80 && sampleCount >= minimumSamples && score - runnerUpScore >= 10 -> "High confidence"
        score >= 60 -> "Moderate confidence"
        else -> "Low confidence"
    }

    private fun eligibleFeedback(
        cropName: String,
        feedback: List<CropOutcomeFeedback>
    ): List<CropOutcomeFeedback> = feedback.filter {
        it.rating in 1..5 && it.plantedCrop.equals(cropName, ignoreCase = true)
    }

    private fun outcomeAdjustment(cropName: String, feedback: List<CropOutcomeFeedback>): Int {
        val eligible = eligibleFeedback(cropName, feedback)
        if (eligible.size < MIN_FEEDBACK_SAMPLES) return 0
        val averageRating = eligible.map { it.rating }.average()
        return ((averageRating - 3.0) * 4.0).roundToInt().coerceIn(-8, 8)
    }

    private fun readingFields(reading: SensorReading): Map<String, Double> = linkedMapOf(
        "temperature" to reading.temperature,
        "humidity" to reading.humidity,
        "soilMoisture" to reading.soilMoisture,
        "soilPh" to reading.soilPh,
        "nitrogen" to reading.nitrogen,
        "phosphorus" to reading.phosphorus,
        "potassium" to reading.potassium
    )

    private fun readingDifference(current: SensorReading, previous: SensorReading): Map<String, Double> {
        val currentFields = readingFields(current)
        val previousFields = readingFields(previous)
        return currentFields.mapValues { (field, value) -> value - previousFields.getValue(field) }
    }

    private fun variabilityThreshold(field: String): Double = when (field) {
        "temperature" -> 3.0
        "humidity" -> 10.0
        "soilMoisture" -> 12.0
        "soilPh" -> 0.6
        "nitrogen" -> 30.0
        "phosphorus" -> 20.0
        "potassium" -> 30.0
        else -> Double.MAX_VALUE
    }

    /** Advisory context only; it does not alter suitability scores or replace a weather forecast. */
    private fun seasonalContext(monthKey: String): Pair<String, String> {
        val month = parseMonth(monthKey)?.monthValue ?: return "Season unavailable" to
            "Add a valid target month to show general seasonal guidance."
        return if (month in 5..10) {
            "General Philippine wet season" to
                "Rainfall is often higher from May to October; confirm drainage and check a local forecast."
        } else {
            "General Philippine dry season" to
                "Rainfall is often lower from November to April; confirm irrigation and check a local forecast."
        }
    }

    private const val MIN_FEEDBACK_SAMPLES = 3

    private fun averageReadings(summaries: List<MonthlySensorSummary>): SensorReading =
        blendReadings(summaries.map { it.average to max(it.sampleCount, 1).toDouble() })

    private fun blendReadings(weightedReadings: List<Pair<SensorReading, Double>>): SensorReading {
        val valid = weightedReadings.filter { it.second > 0.0 && it.second.isFinite() }
        if (valid.isEmpty()) return SensorReading()
        val totalWeight = valid.sumOf { it.second }
        fun average(selector: (SensorReading) -> Double): Double =
            valid.sumOf { selector(it.first) * it.second } / totalWeight

        return SensorReading(
            temperature = average { it.temperature },
            humidity = average { it.humidity },
            soilMoisture = average { it.soilMoisture },
            soilPh = average { it.soilPh },
            nitrogen = average { it.nitrogen },
            phosphorus = average { it.phosphorus },
            potassium = average { it.potassium },
            lightIntensity = average { it.lightIntensity },
            timestamp = valid.maxOf { it.first.timestamp }
        )
    }

    private fun parseMonth(monthKey: String): YearMonth? =
        runCatching { YearMonth.parse(monthKey) }.getOrNull()

    private fun rangeScore(value: Double, range: ClosedFloatingPointRange<Double>): Double {
        if (!value.isFinite()) return 0.0
        if (value in range) return 100.0
        val width = range.endInclusive - range.start
        val distance = if (value < range.start) range.start - value else value - range.endInclusive
        val scale = if (width > 0.0) width else max(abs(range.start) * 0.20, 1.0)
        return (100.0 - distance / scale * 70.0).coerceIn(0.0, 100.0)
    }
}

object MonthlySensorAggregator {
    fun summarize(monthKey: String, readings: List<SensorReading>): MonthlySensorSummary {
        val valid = readings.filter { it.isValidForRecommendation() }
        if (valid.isEmpty()) return MonthlySensorSummary(monthKey = monthKey)

        fun average(selector: (SensorReading) -> Double) = valid.map(selector).average()
        fun minimum(selector: (SensorReading) -> Double) = valid.minOf(selector)
        fun maximum(selector: (SensorReading) -> Double) = valid.maxOf(selector)
        fun standardDeviation(selector: (SensorReading) -> Double): Double {
            val values = valid.map(selector)
            val mean = values.average()
            return sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        }
        return MonthlySensorSummary(
            monthKey = monthKey,
            sampleCount = valid.size,
            firstReadingAt = valid.minOf { it.timestamp },
            lastReadingAt = valid.maxOf { it.timestamp },
            average = SensorReading(
                temperature = average { it.temperature },
                humidity = average { it.humidity },
                soilMoisture = average { it.soilMoisture },
                soilPh = average { it.soilPh },
                nitrogen = average { it.nitrogen },
                phosphorus = average { it.phosphorus },
                potassium = average { it.potassium },
                lightIntensity = average { it.lightIntensity },
                timestamp = valid.maxOf { it.timestamp }
            ),
            minimum = SensorReading(
                temperature = minimum { it.temperature },
                humidity = minimum { it.humidity },
                soilMoisture = minimum { it.soilMoisture },
                soilPh = minimum { it.soilPh },
                nitrogen = minimum { it.nitrogen },
                phosphorus = minimum { it.phosphorus },
                potassium = minimum { it.potassium },
                lightIntensity = minimum { it.lightIntensity },
                timestamp = valid.minOf { it.timestamp }
            ),
            maximum = SensorReading(
                temperature = maximum { it.temperature },
                humidity = maximum { it.humidity },
                soilMoisture = maximum { it.soilMoisture },
                soilPh = maximum { it.soilPh },
                nitrogen = maximum { it.nitrogen },
                phosphorus = maximum { it.phosphorus },
                potassium = maximum { it.potassium },
                lightIntensity = maximum { it.lightIntensity },
                timestamp = valid.maxOf { it.timestamp }
            ),
            standardDeviation = SensorReading(
                temperature = standardDeviation { it.temperature },
                humidity = standardDeviation { it.humidity },
                soilMoisture = standardDeviation { it.soilMoisture },
                soilPh = standardDeviation { it.soilPh },
                nitrogen = standardDeviation { it.nitrogen },
                phosphorus = standardDeviation { it.phosphorus },
                potassium = standardDeviation { it.potassium },
                lightIntensity = standardDeviation { it.lightIntensity }
            )
        )
    }
}
