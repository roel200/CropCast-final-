package com.cropcast.app.data

import com.cropcast.app.data.model.SensorReading
import java.io.DataInputStream
import java.io.InputStream

data class PublicCropPrediction(
    val cropName: String,
    val modelScore: Double
)

enum class PublicCropResultStatus {
    READY,
    LOW_MODEL_AGREEMENT,
    OUTSIDE_TRAINING_RANGE,
    INVALID_INPUT,
    MISSING_RAINFALL
}

enum class PublicCropConfidence(val label: String) {
    HIGH("High model agreement"),
    MODERATE("Moderate model agreement"),
    LOW("Low model agreement"),
    UNAVAILABLE("Agreement unavailable")
}

data class PublicCropRecommendationResult(
    val predictions: List<PublicCropPrediction> = emptyList(),
    val status: PublicCropResultStatus = PublicCropResultStatus.INVALID_INPUT,
    val confidence: PublicCropConfidence = PublicCropConfidence.UNAVAILABLE,
    val outsideTrainingFields: List<String> = emptyList(),
    val usesRainfall: Boolean = false,
    val rainfallMm: Double? = null
) {
    val topRecommendation: PublicCropPrediction?
        get() = predictions.firstOrNull().takeIf { status == PublicCropResultStatus.READY }
}

class PublicCropModelEngine private constructor(
    private val featureNames: List<String>,
    private val classLabels: List<String>,
    private val trees: List<Tree>
) {
    val supportedCrops: List<String> = classLabels.map(::displayName)
    val usesRainfall: Boolean = "rainfall" in featureNames

    fun evaluate(
        reading: SensorReading,
        rainfallMm: Double? = null,
        limit: Int = 3
    ): PublicCropRecommendationResult {
        val inputByFeature = mapOf(
            "N" to reading.nitrogen,
            "P" to reading.phosphorus,
            "K" to reading.potassium,
            "temperature" to reading.temperature,
            "humidity" to reading.humidity,
            "ph" to reading.soilPh
        )
        if (!isPlausible(reading)) {
            return PublicCropRecommendationResult(
                status = PublicCropResultStatus.INVALID_INPUT,
                usesRainfall = usesRainfall,
                rainfallMm = rainfallMm
            )
        }
        if (usesRainfall && (rainfallMm == null || !rainfallMm.isFinite() || rainfallMm < 0.0)) {
            return PublicCropRecommendationResult(
                status = PublicCropResultStatus.MISSING_RAINFALL,
                usesRainfall = true
            )
        }

        val inputs = DoubleArray(featureNames.size) { index ->
            val feature = featureNames[index]
            if (feature == "rainfall") requireNotNull(rainfallMm) else requireNotNull(inputByFeature[feature])
        }
        val outsideFields = featureNames.filterIndexed { index, feature ->
            inputs[index] !in TRAINING_RANGES.getValue(feature)
        }
        if (outsideFields.isNotEmpty()) {
            return PublicCropRecommendationResult(
                status = PublicCropResultStatus.OUTSIDE_TRAINING_RANGE,
                outsideTrainingFields = outsideFields,
                usesRainfall = usesRainfall,
                rainfallMm = rainfallMm
            )
        }

        val scores = DoubleArray(classLabels.size)
        trees.forEach { tree ->
            val leafScores = tree.predict(inputs)
            leafScores.forEachIndexed { index, score -> scores[index] += score }
        }
        val predictions = classLabels.indices
            .map { index ->
                PublicCropPrediction(
                    cropName = displayName(classLabels[index]),
                    modelScore = scores[index] / trees.size
                )
            }
            .sortedByDescending { it.modelScore }
            .take(limit.coerceIn(1, classLabels.size))
        val topScore = predictions.firstOrNull()?.modelScore ?: 0.0
        val runnerUpScore = predictions.getOrNull(1)?.modelScore ?: 0.0
        val confidence = confidenceFor(topScore, topScore - runnerUpScore)
        val status = if (topScore >= MINIMUM_MODEL_SCORE) {
            PublicCropResultStatus.READY
        } else {
            PublicCropResultStatus.LOW_MODEL_AGREEMENT
        }
        return PublicCropRecommendationResult(
            predictions = predictions,
            status = status,
            confidence = confidence,
            usesRainfall = usesRainfall,
            rainfallMm = rainfallMm
        )
    }

    fun predict(
        reading: SensorReading,
        limit: Int = 3,
        rainfallMm: Double? = null
    ): List<PublicCropPrediction> = evaluate(reading, rainfallMm, limit).predictions

    private fun isPlausible(reading: SensorReading): Boolean =
        reading.nitrogen.isFinite() && reading.nitrogen >= 0.0 &&
            reading.phosphorus.isFinite() && reading.phosphorus >= 0.0 &&
            reading.potassium.isFinite() && reading.potassium >= 0.0 &&
            reading.temperature.isFinite() && reading.temperature in -20.0..80.0 &&
            reading.humidity.isFinite() && reading.humidity in 0.0..100.0 &&
            reading.soilPh.isFinite() && reading.soilPh in 0.0..14.0

    private data class Node(
        val feature: Int,
        val threshold: Double,
        val left: Int,
        val right: Int,
        val probabilities: FloatArray?
    )

    private data class Tree(val nodes: Array<Node>) {
        fun predict(inputs: DoubleArray): FloatArray {
            var nodeIndex = 0
            var visited = 0
            while (visited++ <= nodes.size) {
                val node = nodes[nodeIndex]
                node.probabilities?.let { return it }
                nodeIndex = if (inputs[node.feature] <= node.threshold) node.left else node.right
            }
            error("Invalid public crop model tree")
        }
    }

    companion object {
        const val ASSET_NAME = "public_crop_forest.bin"
        const val RAINFALL_ASSET_NAME = "public_crop_forest_rainfall.bin"
        const val MINIMUM_MODEL_SCORE = 0.30

        private val MAGIC = "CCRF0001".encodeToByteArray()
        private val SUPPORTED_FEATURE_SETS = setOf(
            listOf("N", "P", "K", "temperature", "humidity", "ph"),
            listOf("N", "P", "K", "temperature", "humidity", "ph", "rainfall")
        )
        private val TRAINING_RANGES = mapOf(
            "N" to 0.0..140.0,
            "P" to 5.0..145.0,
            "K" to 5.0..205.0,
            "temperature" to 8.825674745..43.67549305,
            "humidity" to 14.25803981..99.98187601,
            "ph" to 3.504752314..9.93509073,
            "rainfall" to 20.21126747..298.5601175
        )

        fun load(input: InputStream): PublicCropModelEngine = DataInputStream(input.buffered()).use { stream ->
            val magic = ByteArray(MAGIC.size).also(stream::readFully)
            require(magic.contentEquals(MAGIC)) { "Unsupported public crop model format" }

            val features = readStrings(stream, maximum = 32)
            require(features in SUPPORTED_FEATURE_SETS) { "Unexpected public crop model features" }
            val classes = readStrings(stream, maximum = 100)
            require(classes.isNotEmpty()) { "Public crop model has no classes" }

            val treeCount = stream.readInt()
            require(treeCount in 1..2_000) { "Invalid public crop model tree count" }
            val trees = List(treeCount) {
                val nodeCount = stream.readInt()
                require(nodeCount in 1..100_000) { "Invalid public crop model node count" }
                val nodes = Array(nodeCount) {
                    val feature = stream.readInt()
                    val threshold = stream.readDouble()
                    val left = stream.readInt()
                    val right = stream.readInt()
                    val probabilities = if (feature < 0) {
                        FloatArray(classes.size) { stream.readFloat() }
                    } else {
                        require(feature in features.indices) { "Invalid feature index in public crop model" }
                        null
                    }
                    Node(feature, threshold, left, right, probabilities)
                }
                nodes.forEach { node ->
                    if (node.probabilities == null) {
                        require(node.left in nodes.indices && node.right in nodes.indices) {
                            "Invalid child index in public crop model"
                        }
                    }
                }
                Tree(nodes)
            }
            PublicCropModelEngine(features, classes, trees)
        }

        private fun confidenceFor(topScore: Double, margin: Double): PublicCropConfidence = when {
            topScore >= 0.70 && margin >= 0.25 -> PublicCropConfidence.HIGH
            topScore >= 0.45 && margin >= 0.12 -> PublicCropConfidence.MODERATE
            else -> PublicCropConfidence.LOW
        }

        private fun readStrings(stream: DataInputStream, maximum: Int): List<String> {
            val count = stream.readInt()
            require(count in 1..maximum) { "Invalid public crop model string count" }
            return List(count) {
                val byteCount = stream.readInt()
                require(byteCount in 1..1_024) { "Invalid public crop model string length" }
                val bytes = ByteArray(byteCount)
                stream.readFully(bytes)
                bytes.decodeToString()
            }
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
}
