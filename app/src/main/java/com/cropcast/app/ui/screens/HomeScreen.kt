package com.cropcast.app.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cropcast.app.R
import com.cropcast.app.data.PublicCropRecommendationResult
import com.cropcast.app.data.PublicCropResultStatus
import com.cropcast.app.data.SeedRecommendationEngine
import com.cropcast.app.data.model.AlertEvent
import com.cropcast.app.data.model.MonthlyCropRecommendation
import com.cropcast.app.ui.CropCastUiState
import com.cropcast.app.ui.components.RoundedCard
import com.cropcast.app.ui.components.SectionTitle
import com.cropcast.app.ui.components.StatusPill
import com.cropcast.app.ui.localization.tr
import com.cropcast.app.ui.theme.CropGreen
import com.cropcast.app.ui.theme.Orange
import com.cropcast.app.ui.theme.Pink
import com.cropcast.app.ui.theme.Purple
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    state: CropCastUiState,
    publicRecommendation: PublicCropRecommendationResult?,
    publicModelAvailable: Boolean,
    onOpenRecommendations: () -> Unit,
    onOpenSensors: () -> Unit
) {
    val activeAlerts = state.alerts.filterNot { it.acknowledged }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { FarmOverviewCard(state) }
        item { SectionTitle("🤖", tr("ML crop recommendation")) }
        item {
            RecommendationPreviewCard(
                recommendation = publicRecommendation,
                sampleCount = state.monthlySummary.sampleCount,
                monthKey = state.monthlySummary.monthKey,
                modelAvailable = publicModelAvailable,
                onOpenRecommendations = onOpenRecommendations
            )
        }
        item { SectionTitle("📊", tr("Current Conditions")) }
        item { KeyConditionsGrid(state) }
        item { SectionTitle("📅", tr("Monthly data readiness")) }
        item { DataReadinessCard(state) }
        item { SectionTitle("⚠️", tr("Important notices")) }
        item { NoticesCard(state, activeAlerts) }
        item { QuickActions(onOpenRecommendations, onOpenSensors) }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun FarmOverviewCard(state: CropCastUiState) {
    val statusText = when {
        state.isDemo -> tr("Demo")
        state.status.online -> tr("Online")
        else -> tr("Offline")
    }
    val statusColor = if (state.isDemo || state.status.online) CropGreen else MaterialTheme.colorScheme.error
    val lastSync = state.reading.timestamp.takeIf { it > 0L }?.let {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
    } ?: tr("Waiting for data")

    RoundedCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "${tr("Welcome")}, ${state.account.displayName}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 20.sp
                    )
                    Text(
                        "${state.settings.farmName} · ${state.settings.deviceName}",
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f),
                        fontSize = 12.sp
                    )
                }
                StatusPill(statusText, statusColor)
            }
            Text(
                "${tr("Last synchronization")}: $lastSync",
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f),
                fontSize = 11.sp
            )
            if (state.isDemo) {
                Text(
                    tr("Test data is simulated and is not field evidence"),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp
                )
            }
        }
    }
}

@Composable
private fun RecommendationPreviewCard(
    recommendation: PublicCropRecommendationResult?,
    sampleCount: Int,
    monthKey: String,
    modelAvailable: Boolean,
    onOpenRecommendations: () -> Unit
) {
    val prediction = recommendation?.topRecommendation
    RoundedCard {
        if (prediction == null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    tr(recommendationUnavailableMessage(recommendation, modelAvailable)),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp
                )
                if (recommendation?.outsideTrainingFields?.isNotEmpty() == true) {
                    Text(
                        recommendation.outsideTrainingFields.joinToString(", ", prefix = "${tr("Check these readings")}: ") {
                            tr(publicFeatureLabel(it))
                        },
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }
                Button(onClick = onOpenRecommendations, modifier = Modifier.fillMaxWidth()) {
                    Text(tr("View full recommendation"))
                }
            }
            return@RoundedCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(64.dp).background(
                        MaterialTheme.colorScheme.primaryContainer,
                        RoundedCornerShape(16.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Text("🌱", fontSize = 34.sp)
                }
                Spacer(Modifier.size(13.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        prediction.cropName,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = 17.sp
                    )
                    Text(
                        "${tr("Top match from 22 crops")} · $monthKey · ${tr(recommendation.confidence.label)}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 11.sp
                    )
                }
                Text(
                    "${(prediction.modelScore * 100).toInt()}%",
                    color = CropGreen,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 23.sp
                )
            }
            LinearProgressIndicator(
                progress = { prediction.modelScore.toFloat() },
                modifier = Modifier.fillMaxWidth().height(7.dp),
                color = CropGreen,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                "$sampleCount ${tr("latest-month readings")} · ${tr("model agreement, not field success probability")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            recommendation.rainfallMm?.let { rainfall ->
                Text(
                    "${tr("30-day weather rainfall")}: ${"%.1f".format(rainfall)} mm · Open-Meteo",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Button(onClick = onOpenRecommendations, modifier = Modifier.fillMaxWidth()) {
                Text(tr("View full recommendation"))
            }
        }
    }
}

private fun recommendationUnavailableMessage(
    recommendation: PublicCropRecommendationResult?,
    modelAvailable: Boolean
): String = when {
    !modelAvailable -> "The public crop model could not be loaded"
    recommendation == null -> "At least eight valid monthly readings are needed for the ML recommendation"
    recommendation.status == PublicCropResultStatus.LOW_MODEL_AGREEMENT ->
        "No reliable crop match was found for these readings"
    recommendation.status == PublicCropResultStatus.OUTSIDE_TRAINING_RANGE ->
        "Sensor conditions are outside the public dataset range"
    recommendation.status == PublicCropResultStatus.MISSING_RAINFALL ->
        "Rainfall is needed before this model can recommend a crop"
    else -> "The sensor readings are invalid for crop recommendation"
}

private fun publicFeatureLabel(feature: String): String = when (feature) {
    "N" -> "Nitrogen"
    "P" -> "Phosphorus"
    "K" -> "Potassium"
    "ph" -> "Soil pH"
    "temperature" -> "Temperature"
    "humidity" -> "Humidity"
    "rainfall" -> "Rainfall"
    else -> feature
}

@Composable
private fun CropThumbnail(recommendation: MonthlyCropRecommendation) {
    Box(
        modifier = Modifier.size(64.dp).background(
            MaterialTheme.colorScheme.primaryContainer,
            RoundedCornerShape(16.dp)
        ),
        contentAlignment = Alignment.Center
    ) {
        if (recommendation.cropName.equals("Alugbati", ignoreCase = true)) {
            Image(
                painter = painterResource(R.drawable.crop_alugbati),
                contentDescription = tr("Alugbati crop"),
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
                contentScale = ContentScale.Crop
            )
        } else {
            Text(recommendation.cropIcon, fontSize = 34.sp)
        }
    }
}

@Composable
private fun KeyConditionsGrid(state: CropCastUiState) {
    val reading = state.reading
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeMetricCard(Modifier.weight(1f), "💧", tr("Soil Moisture"), "${reading.soilMoisture.toInt()}%", CropGreen)
            HomeMetricCard(Modifier.weight(1f), "🌡️", tr("Temperature"), "%.1f°C".format(reading.temperature), Orange)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HomeMetricCard(Modifier.weight(1f), "🧪", tr("Soil pH"), "%.1f".format(reading.soilPh), Pink)
            HomeMetricCard(Modifier.weight(1f), "🌫️", tr("Humidity"), "${reading.humidity.toInt()}%", Purple)
        }
    }
}

@Composable
private fun HomeMetricCard(
    modifier: Modifier,
    icon: String,
    label: String,
    value: String,
    accent: Color
) {
    RoundedCard(modifier = modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(icon, fontSize = 21.sp)
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
            Text(value, color = accent, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
        }
    }
}

@Composable
private fun DataReadinessCard(state: CropCastUiState) {
    val summary = state.monthlySummary
    val required = SeedRecommendationEngine.MIN_MONTHLY_SAMPLES
    val collected = summary.sampleCount
    val progress = collected.toFloat() / required
    val isReady = summary.hasData && collected >= required

    RoundedCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    summary.monthKey.ifBlank { tr("Waiting for data") },
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "$collected / $required",
                    color = if (isReady) CropGreen else Orange,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                )
            }
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                color = if (isReady) CropGreen else Orange,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Text(
                "$collected ${tr("of")} $required ${tr("required readings collected")}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Text(
                tr(if (isReady) "Ready for recommendation" else "More readings needed"),
                color = if (isReady) CropGreen else Orange,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun NoticesCard(state: CropCastUiState, activeAlerts: List<AlertEvent>) {
    RoundedCard {
        Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (activeAlerts.isEmpty()) {
                NoticeLine("✓", tr("No active alerts"), CropGreen)
            } else {
                activeAlerts.take(2).forEach { alert ->
                    NoticeLine("!", alert.message.ifBlank { alert.type }, MaterialTheme.colorScheme.error)
                }
            }
            if (!state.status.online && !state.isDemo) {
                NoticeLine("!", tr("Sensor is offline; using the latest available data"), Orange)
            }
            NoticeLine(
                "i",
                tr("NPK and moisture ranges are provisional until locally calibrated"),
                MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun NoticeLine(icon: String, message: String, color: Color) {
    Row(verticalAlignment = Alignment.Top) {
        Box(
            modifier = Modifier.size(22.dp).background(color.copy(alpha = .12f), RoundedCornerShape(50)),
            contentAlignment = Alignment.Center
        ) {
            Text(icon, color = color, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
        Spacer(Modifier.size(9.dp))
        Text(
            message,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun QuickActions(
    onOpenRecommendations: () -> Unit,
    onOpenSensors: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(onClick = onOpenRecommendations, modifier = Modifier.weight(1f)) {
            Text(tr("Recommendation"), fontSize = 11.sp)
        }
        OutlinedButton(onClick = onOpenSensors, modifier = Modifier.weight(1f)) {
            Text(tr("Sensor details"), fontSize = 11.sp)
        }
    }
}
