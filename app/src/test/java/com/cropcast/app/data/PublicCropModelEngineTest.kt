package com.cropcast.app.data

import com.cropcast.app.data.model.SensorReading
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicCropModelEngineTest {
    private fun loadModel(): PublicCropModelEngine {
        val candidates = listOf(
            File("src/main/assets/${PublicCropModelEngine.ASSET_NAME}"),
            File("app/src/main/assets/${PublicCropModelEngine.ASSET_NAME}")
        )
        val modelFile = candidates.firstOrNull(File::isFile)
            ?: error("Public crop model asset was not found")
        return modelFile.inputStream().use(PublicCropModelEngine::load)
    }

    @Test
    fun exposesAllTwentyTwoPublicDatasetCrops() {
        val model = loadModel()

        assertEquals(22, model.supportedCrops.size)
        assertTrue("Rice" in model.supportedCrops)
        assertTrue("Corn (Maize)" in model.supportedCrops)
    }

    @Test
    fun predictsRiceFromKnownPublicDatasetMeasurements() {
        val result = loadModel().predict(
            SensorReading(
                nitrogen = 90.0,
                phosphorus = 42.0,
                potassium = 43.0,
                temperature = 20.87974371,
                humidity = 82.00274423,
                soilPh = 6.502985292
            )
        )

        assertEquals("Rice", result.first().cropName)
        assertTrue(result.first().modelScore in 0.0..1.0)
        assertEquals(3, result.size)
    }

    @Test
    fun rejectsInvalidMeasurements() {
        val result = loadModel().predict(SensorReading(humidity = 120.0))

        assertTrue(result.isEmpty())
    }

    @Test
    fun androidAssetReproducesAllPublicDatasetLabels() {
        val datasetCandidates = listOf(
            File("../data/raw/Crop_recommendation.csv"),
            File("data/raw/Crop_recommendation.csv")
        )
        val dataset = datasetCandidates.firstOrNull(File::isFile)
            ?: error("Public crop dataset was not found")
        val model = loadModel()
        val predictedCrops = mutableSetOf<String>()
        var tested = 0
        var correct = 0

        dataset.useLines { lines ->
            lines.drop(1).filter(String::isNotBlank).forEach { line ->
                val columns = line.split(',')
                val expected = displayName(columns[7])
                val prediction = model.predict(
                    SensorReading(
                        nitrogen = columns[0].toDouble(),
                        phosphorus = columns[1].toDouble(),
                        potassium = columns[2].toDouble(),
                        temperature = columns[3].toDouble(),
                        humidity = columns[4].toDouble(),
                        soilPh = columns[5].toDouble()
                    ),
                    limit = 1
                ).single().cropName
                tested += 1
                predictedCrops += prediction
                if (prediction == expected) correct += 1
            }
        }

        assertEquals(2_200, tested)
        assertEquals(22, predictedCrops.size)
        assertTrue("Expected at least 99% training-set parity", correct.toDouble() / tested >= 0.99)
    }

    private fun displayName(label: String): String = when (label) {
        "blackgram" -> "Black Gram"
        "kidneybeans" -> "Kidney Beans"
        "maize" -> "Corn (Maize)"
        "mothbeans" -> "Moth Beans"
        "mungbean" -> "Mung Bean"
        "muskmelon" -> "Muskmelon"
        "pigeonpeas" -> "Pigeon Peas"
        else -> label.replaceFirstChar { it.uppercase() }
    }
}
