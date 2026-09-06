package com.cropcast.app.data

import com.cropcast.app.data.model.SensorReading
import com.cropcast.app.data.model.MonthlySensorSummary
import com.cropcast.app.data.model.CropOutcomeFeedback
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.YearMonth

class SeedRecommendationEngineTest {
    @Test
    fun recommendsOneSeedFromMonthlySensorData() {
        val result = SeedRecommendationEngine.recommend(
            MonthlySensorSummary(
                monthKey = "2026-08",
                sampleCount = 30,
                average = SensorReading(
                    temperature = 22.0,
                    humidity = 70.0,
                    soilMoisture = 55.0,
                    soilPh = 6.4,
                    nitrogen = 100.0,
                    phosphorus = 60.0,
                    potassium = 120.0,
                    lightIntensity = 700.0
                )
            )
        )
        assertNotNull(result)
        assertEquals("Tomato", result!!.name)
        assertTrue(result.confidence in 0..100)
    }

    @Test
    fun recommendsOkraWhenAllSevenScoredReadingsMatchOkra() {
        val result = SeedRecommendationEngine.recommend(
            MonthlySensorSummary(
                monthKey = "2026-08",
                sampleCount = 30,
                average = SensorReading(
                    temperature = 30.0,
                    humidity = 55.0,
                    soilMoisture = 65.0,
                    soilPh = 6.4,
                    nitrogen = 100.0,
                    phosphorus = 60.0,
                    potassium = 90.0
                )
            )
        )

        assertEquals("Okra", result?.name)
        assertEquals(100, result?.confidence)
    }

    @Test
    fun recommendsAlugbatiWhenNpkAndClimateMatchItsProfile() {
        val result = SeedRecommendationEngine.recommend(
            MonthlySensorSummary(
                monthKey = "2026-08",
                sampleCount = 30,
                average = SensorReading(
                    temperature = 30.0,
                    humidity = 65.0,
                    soilMoisture = 70.0,
                    soilPh = 7.0,
                    nitrogen = 180.0,
                    phosphorus = 90.0,
                    potassium = 120.0
                )
            )
        )

        assertEquals("Alugbati", result?.name)
        assertEquals(100, result?.confidence)
    }

    @Test
    fun recommendsPotatoForSeededPotatoReadings() {
        val readings = listOf(
            SensorReading(14.5, 84.0, 60.0, 5.1, 105.0, 70.0, 145.0, 550.0, 100L),
            SensorReading(15.0, 85.0, 61.0, 5.2, 110.0, 75.0, 150.0, 575.0, 200L),
            SensorReading(15.5, 86.0, 62.0, 5.3, 115.0, 80.0, 155.0, 600.0, 300L)
        )
        val summary = MonthlySensorAggregator.summarize("2026-09", readings)

        val result = SeedRecommendationEngine.recommend(summary)

        assertEquals("Potato", result?.name)
        assertEquals(100, result?.confidence)
    }

    @Test
    fun recommendsRiceForSeededRiceReadings() {
        val readings = listOf(
            SensorReading(22.0, 81.0, 78.0, 5.8, 70.0, 40.0, 38.0, 550.0, 100L),
            SensorReading(23.0, 82.0, 80.0, 6.0, 80.0, 45.0, 40.0, 575.0, 200L),
            SensorReading(24.0, 83.0, 82.0, 6.2, 90.0, 50.0, 42.0, 600.0, 300L)
        )
        val summary = MonthlySensorAggregator.summarize("2026-09", readings)

        val result = SeedRecommendationEngine.recommend(summary)

        assertEquals("Rice", result?.name)
        assertEquals(100, result?.confidence)
    }

    @Test
    fun recommendsEachAdditionalCropForDistinctiveReadings() {
        val cases = mapOf(
            "Corn" to SensorReading(22.5, 74.0, 47.5, 6.85, 195.0, 34.0, 50.0),
            "Eggplant" to SensorReading(29.4, 65.0, 52.5, 5.6, 100.0, 77.0, 145.0),
            "Cucumber" to SensorReading(31.2, 78.0, 82.5, 6.15, 175.0, 95.0, 195.0),
            "Cabbage" to SensorReading(17.5, 65.0, 72.5, 6.9, 123.0, 90.0, 175.0),
            "Sweet Potato" to SensorReading(25.0, 78.0, 47.5, 5.6, 100.0, 42.0, 125.0),
            "Lettuce" to SensorReading(8.7, 65.0, 72.5, 6.9, 86.0, 67.0, 190.0),
            "Spinach" to SensorReading(15.0, 65.0, 72.5, 6.5, 100.0, 50.0, 94.0)
        )

        cases.forEach { (expectedCrop, reading) ->
            val result = SeedRecommendationEngine.recommend(
                MonthlySensorSummary(
                    monthKey = "2026-09",
                    sampleCount = 8,
                    average = reading
                )
            )
            assertEquals(expectedCrop, result?.name)
            assertEquals(100, result?.confidence)
        }
    }

    @Test
    fun doesNotRecommendWithoutMonthlySensorData() {
        assertNull(SeedRecommendationEngine.recommend(MonthlySensorSummary(monthKey = "2026-08")))
    }

    @Test
    fun monthlySummaryAveragesAllSensorFields() {
        val result = MonthlySensorAggregator.summarize(
            "2026-08",
            listOf(
                SensorReading(temperature = 20.0, humidity = 60.0, soilMoisture = 40.0, soilPh = 6.0, nitrogen = 30.0, phosphorus = 20.0, potassium = 40.0, lightIntensity = 500.0, timestamp = 100L),
                SensorReading(temperature = 30.0, humidity = 80.0, soilMoisture = 60.0, soilPh = 7.0, nitrogen = 50.0, phosphorus = 40.0, potassium = 60.0, lightIntensity = 700.0, timestamp = 200L)
            )
        )

        assertEquals(2, result.sampleCount)
        assertEquals(25.0, result.average.temperature, 0.001)
        assertEquals(70.0, result.average.humidity, 0.001)
        assertEquals(50.0, result.average.soilMoisture, 0.001)
        assertEquals(6.5, result.average.soilPh, 0.001)
        assertEquals(200L, result.lastReadingAt)
    }

    @Test
    fun emptyMonthDoesNotProduceData() {
        val result = MonthlySensorAggregator.summarize("2026-08", emptyList())
        assertEquals(0, result.sampleCount)
        assertTrue(!result.hasData)
    }

    @Test
    fun scoreAllReturnsEveryCropWithFieldBreakdown() {
        val result = SeedRecommendationEngine.scoreAll(
            MonthlySensorSummary(
                monthKey = "2026-08",
                sampleCount = 8,
                average = SensorReading(
                    temperature = 22.0,
                    humidity = 70.0,
                    soilMoisture = 55.0,
                    soilPh = 6.4,
                    nitrogen = 100.0,
                    phosphorus = 60.0,
                    potassium = 120.0
                )
            )
        )

        assertEquals(12, result.size)
        assertEquals(7, result.first().fieldScores.size)
        assertTrue(result.zipWithNext().all { (first, second) -> first.crop.confidence >= second.crop.confidence })
        assertTrue(result.all { it.crop.confidence in 0..100 })
    }

    @Test
    fun monthlyHistoryExcludesCurrentMonthAndShortMonths() {
        val summaries = listOf(
            summary("2026-07", potatoReading()),
            summary("2026-08", potatoReading()),
            summary("2026-09", potatoReading()),
            summary("2026-06", potatoReading(), sampleCount = 7)
        )

        val result = SeedRecommendationEngine.recommendHistory(
            summaries = summaries,
            currentMonth = YearMonth.of(2026, 9)
        )

        assertEquals(listOf("2026-08", "2026-07"), result.map { it.monthKey })
        assertTrue(result.all { it.recommendationType == SeedRecommendationEngine.OBSERVED_MONTH })
    }

    @Test
    fun nextMonthForecastUsesLatestAndAvailablePastHistory() {
        val summaries = listOf(
            summary("2025-08", SensorReading(22.0, 70.0, 55.0, 6.4, 100.0, 60.0, 120.0)),
            summary("2026-07", SensorReading(23.0, 71.0, 56.0, 6.3, 101.0, 61.0, 121.0)),
            summary("2026-08", potatoReading())
        )

        val result = SeedRecommendationEngine.recommendNextMonth(
            summaries = summaries,
            currentMonth = YearMonth.of(2026, 9)
        )

        assertEquals("2026-09", result?.monthKey)
        assertEquals(SeedRecommendationEngine.NEXT_MONTH_FORECAST, result?.recommendationType)
        assertEquals(8, result?.sampleCount)
        assertTrue(result?.basedOnMonths.orEmpty().contains("2026-08"))
        assertTrue(result?.basedOnMonths.orEmpty().contains("2025-08"))
        assertTrue(result?.basedOnMonths.orEmpty().contains("2026-07"))
    }

    @Test
    fun noForecastIsProducedWithoutAnEligibleClosedMonth() {
        val result = SeedRecommendationEngine.recommendNextMonth(
            summaries = listOf(summary("2026-09", potatoReading())),
            currentMonth = YearMonth.of(2026, 9)
        )

        assertNull(result)
    }

    @Test
    fun monthlySummaryRejectsImplausibleReadingsAndTracksVariability() {
        val result = MonthlySensorAggregator.summarize(
            "2026-08",
            listOf(
                potatoReading().copy(temperature = 10.0, timestamp = 100L),
                potatoReading().copy(temperature = 20.0, timestamp = 200L),
                potatoReading().copy(temperature = 100.0, timestamp = 300L)
            )
        )

        assertEquals(2, result.sampleCount)
        assertEquals(10.0, result.minimum.temperature, 0.001)
        assertEquals(20.0, result.maximum.temperature, 0.001)
        assertEquals(5.0, result.standardDeviation.temperature, 0.001)
    }

    @Test
    fun threeLocalOutcomeRatingsConservativelyAdjustCropScore() {
        val summary = summary("2026-08", potatoReading())
        val withoutFeedback = SeedRecommendationEngine.scoreAll(summary)
            .first { it.crop.name == "Potato" }.crop.confidence
        val feedback = (1..3).map {
            CropOutcomeFeedback(
                id = "feedback-$it",
                monthKey = "2026-0$it",
                recommendedCrop = "Potato",
                plantedCrop = "Potato",
                rating = 1,
                submittedAt = it.toLong()
            )
        }

        val withFeedback = SeedRecommendationEngine.scoreAll(summary, feedback)
            .first { it.crop.name == "Potato" }.crop.confidence

        assertEquals((withoutFeedback - 8).coerceAtLeast(0), withFeedback)
    }

    @Test
    fun recommendationReportsVariabilitySeasonAndFeedbackUsage() {
        val readings = (1..8).map { sample ->
            potatoReading().copy(
                temperature = if (sample % 2 == 0) 35.0 else 10.0,
                humidity = if (sample % 2 == 0) 95.0 else 45.0,
                soilMoisture = if (sample % 2 == 0) 90.0 else 30.0,
                timestamp = sample.toLong()
            )
        }
        val feedback = (1..3).map {
            CropOutcomeFeedback(
                id = "feedback-$it",
                monthKey = "2026-0$it",
                plantedCrop = "Potato",
                rating = 4,
                submittedAt = it.toLong()
            )
        }

        val result = SeedRecommendationEngine.recommendForMonth(
            MonthlySensorAggregator.summarize("2026-08", readings),
            feedback = feedback
        )

        assertEquals("High variability", result?.stabilityLabel)
        assertTrue(result?.unstableFields.orEmpty().contains("temperature"))
        assertEquals("General Philippine wet season", result?.seasonLabel)
        assertTrue(result?.seasonNote.orEmpty().contains("local forecast"))
    }

    @Test
    fun farmOutcomesAdjustFutureForecastButNotObservedHistory() {
        val summaries = listOf(summary("2026-08", potatoReading()))
        val feedback = (1..3).map {
            CropOutcomeFeedback(
                id = "feedback-$it",
                monthKey = "2026-0$it",
                plantedCrop = "Potato",
                rating = 1,
                submittedAt = it.toLong()
            )
        }

        val history = SeedRecommendationEngine.recommendHistory(
            summaries,
            currentMonth = YearMonth.of(2026, 9)
        )
        val forecast = SeedRecommendationEngine.recommendNextMonth(
            summaries,
            currentMonth = YearMonth.of(2026, 9),
            feedback = feedback
        )

        assertEquals(100, history.single().score)
        assertEquals(92, forecast?.score)
        assertEquals(3, forecast?.feedbackSamplesUsed)
    }

    private fun potatoReading(): SensorReading =
        SensorReading(15.0, 85.0, 61.0, 5.2, 110.0, 75.0, 150.0)

    private fun summary(
        monthKey: String,
        reading: SensorReading,
        sampleCount: Int = 8
    ): MonthlySensorSummary = MonthlySensorAggregator.summarize(
        monthKey,
        (1..sampleCount).map { reading.copy(timestamp = it.toLong()) }
    )
}
