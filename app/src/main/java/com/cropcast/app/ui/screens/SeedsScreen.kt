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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.cropcast.app.data.model.MonthlyCropRecommendation
import com.cropcast.app.data.model.MonthlySensorSummary
import com.cropcast.app.data.model.SeedRecommendation
import com.cropcast.app.ui.components.RoundedCard
import com.cropcast.app.ui.components.SectionTitle
import com.cropcast.app.ui.localization.tr
import com.cropcast.app.ui.theme.CropGreen
import kotlin.math.abs

@Composable
fun SeedsScreen(
    nextMonthRecommendation: MonthlyCropRecommendation?,
    monthlySummary: MonthlySensorSummary,
    monthlyRecommendations: List<MonthlyCropRecommendation>,
    forecastHistory: List<MonthlyCropRecommendation>
) {
    val forecastComparisons = forecastHistory.mapNotNull { forecast ->
        monthlyRecommendations.firstOrNull { it.monthKey == forecast.monthKey }?.let { observed ->
            forecast to observed
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SectionTitle("✨", tr("Crop Recommendation")) }
        item { ForecastSummaryCard(nextMonthRecommendation, monthlySummary) }
        item { RecommendationCaveat() }
        nextMonthRecommendation?.let { forecast ->
            item {
                SeedCard(
                    seed = forecast.recommendedCrop,
                    confidenceLabel = forecast.confidenceLabel,
                    isForecast = true
                )
            }
            item { RecommendationDetails(forecast) }
        }
        if (monthlyRecommendations.isNotEmpty()) {
            item { SectionTitle("📚", tr("Monthly recommendation history")) }
            items(monthlyRecommendations, key = { it.monthKey }) { recommendation ->
                MonthlyRecommendationRow(recommendation)
            }
        }
        if (forecastComparisons.isNotEmpty()) {
            item { SectionTitle("🔎", tr("Forecast results")) }
            items(forecastComparisons, key = { it.first.monthKey }) { (forecast, observed) ->
                ForecastComparisonRow(forecast, observed)
            }
        }
        item { Spacer(Modifier.height(14.dp)) }
    }
}

@Composable
private fun RecommendationCaveat() {
    RoundedCard(color = MaterialTheme.colorScheme.surface) {
        Text(
            tr("Decision support only: confirm the result with local weather, field conditions, and farming advice before planting"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun ForecastSummaryCard(
    forecast: MonthlyCropRecommendation?,
    monthlySummary: MonthlySensorSummary
) {
    RoundedCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(
                "📅 ${tr("Next month recommendation")}",
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )
            if (forecast != null) {
                Text(
                    "${tr("Forecast for")} ${forecast.monthKey} · ${forecast.basedOnMonths.size} ${tr("past months used")}",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp
                )
                Text(
                    "${tr("Based on historical sensor data")}: ${forecast.basedOnMonths.joinToString()}",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = .78f),
                    fontSize = 12.sp
                )
            } else if (monthlySummary.hasData) {
                Text(
                    tr("More closed-month history is needed before forecasting"),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            } else {
                Text(
                    tr("A recommendation will appear after monthly sensor data is collected"),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
private fun RecommendationDetails(recommendation: MonthlyCropRecommendation) {
    var matchingFieldNames = ""
    for (index in recommendation.matchingFields.indices) {
        if (index > 0) matchingFieldNames += ", "
        matchingFieldNames += tr(fieldLabel(recommendation.matchingFields[index]))
    }
    var limitingFieldNames = ""
    for (index in recommendation.limitingFields.indices) {
        if (index > 0) limitingFieldNames += ", "
        limitingFieldNames += tr(fieldLabel(recommendation.limitingFields[index]))
    }
    var unstableFieldNames = ""
    for (index in recommendation.unstableFields.indices) {
        if (index > 0) unstableFieldNames += ", "
        unstableFieldNames += tr(fieldLabel(recommendation.unstableFields[index]))
    }
    val notableTrends = recommendation.trendByField.entries
        .sortedByDescending { abs(it.value) }
        .take(3)
    var trendSummary = ""
    for (index in notableTrends.indices) {
        if (index > 0) trendSummary += " · "
        val trend = notableTrends[index]
        val direction = if (trend.value >= 0.0) "↑" else "↓"
        trendSummary += "${tr(fieldLabel(trend.key))} $direction ${"%.1f".format(abs(trend.value))}"
    }
    val rangeFields = (if (recommendation.unstableFields.isNotEmpty()) {
        recommendation.unstableFields
    } else {
        listOf("temperature", "soilMoisture", "soilPh")
    }).take(3)
    var rangeSummary = ""
    for (index in rangeFields.indices) {
        val field = rangeFields[index]
        val minimum = recommendation.minimumByField[field] ?: continue
        val maximum = recommendation.maximumByField[field] ?: continue
        if (rangeSummary.isNotBlank()) rangeSummary += " · "
        rangeSummary += "${tr(fieldLabel(field))} ${"%.1f".format(minimum)}–${"%.1f".format(maximum)}"
    }
    RoundedCard(color = MaterialTheme.colorScheme.surface) {
        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(
                tr("Why this crop"),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 15.sp
            )
            Text(
                "${tr("Best matching fields")}: $matchingFieldNames",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                "${tr("Fields to monitor")}: $limitingFieldNames",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (recommendation.runnerUpName.isNotBlank()) {
                Text(
                    "${tr("Second choice")}: ${recommendation.runnerUpName} (${recommendation.runnerUpScore}%)",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            Text(
                "${tr("Reading stability")}: ${tr(recommendation.stabilityLabel)}" +
                    if (unstableFieldNames.isNotBlank()) " · ${tr("Variable fields")}: $unstableFieldNames" else "",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            if (rangeSummary.isNotBlank()) {
                Text(
                    "${tr("Measured ranges")}: $rangeSummary",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            if (trendSummary.isNotBlank()) {
                Text(
                    "${tr("Change from previous month")}: $trendSummary",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            if (recommendation.seasonLabel.isNotBlank()) {
                Text(
                    tr(recommendation.seasonLabel),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp
                )
                Text(
                    tr(recommendation.seasonNote),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            if (recommendation.feedbackSamplesUsed > 0) {
                Text(
                    "${recommendation.feedbackSamplesUsed} ${tr("local farm outcomes considered")}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Text(
                "${tr("Used")} ${recommendation.sampleCount} ${tr("latest-month readings")} · ${tr(recommendation.confidenceLabel)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
            Text(
                tr("NPK and moisture ranges are provisional until locally calibrated"),
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .78f),
                fontSize = 11.sp
            )
        }
    }
}

@Composable
private fun MonthlyRecommendationRow(recommendation: MonthlyCropRecommendation) {
    RoundedCard(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            if (recommendation.cropName.equals("Alugbati", ignoreCase = true)) {
                Image(
                    painter = painterResource(R.drawable.crop_alugbati),
                    contentDescription = tr("Alugbati crop"),
                    modifier = Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)),
                    contentScale = ContentScale.Crop
                )
            } else {
                Text(recommendation.cropIcon, fontSize = 29.sp)
            }
            Spacer(Modifier.size(11.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    recommendation.monthKey,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
                Text(
                    "${recommendation.cropName} (${recommendation.cropVariety})",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    "${recommendation.sampleCount} ${tr("readings")} · ${tr(recommendation.confidenceLabel)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
            Text(
                "${recommendation.score}%",
                color = scoreColor(recommendation.score),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 18.sp
            )
        }
    }
}

@Composable
private fun ForecastComparisonRow(
    forecast: MonthlyCropRecommendation,
    observed: MonthlyCropRecommendation
) {
    val matches = forecast.cropName == observed.cropName
    RoundedCard(color = MaterialTheme.colorScheme.surface) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                forecast.monthKey,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )
            Text(
                "${tr("Forecast")}: ${forecast.cropName} · ${tr("Observed")}: ${observed.cropName}",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
            )
            Text(
                if (matches) tr("Forecast matched") else tr("Forecast differed"),
                color = if (matches) CropGreen else Color(0xFFE95D5D),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun SeedCard(seed: SeedRecommendation, confidenceLabel: String, isForecast: Boolean) {
    val color = scoreColor(seed.confidence)
    RoundedCard(color = MaterialTheme.colorScheme.primaryContainer) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(72.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .78f), RoundedCornerShape(18.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (seed.name.equals("Alugbati", ignoreCase = true)) {
                    Image(
                        painter = painterResource(R.drawable.crop_alugbati),
                        contentDescription = tr("Alugbati crop"),
                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(18.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(seed.icon, fontSize = 39.sp)
                }
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    "${seed.name} (${seed.variety})",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 17.sp,
                    maxLines = 1
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    "${tr("Grow period")}: ${seed.growDays} ${tr("days")} · ${seed.soil}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
                Spacer(Modifier.height(7.dp))
                Text(
                    if (isForecast) tr("Forecast") else tr("Observed month"),
                    color = color,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(5.dp))
                LinearProgressIndicator(
                    progress = { seed.confidence / 100f },
                    modifier = Modifier.fillMaxWidth().height(7.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
            Spacer(Modifier.size(14.dp))
            Column(horizontalAlignment = Alignment.End) {
                Text("${seed.confidence}%", color = color, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                Text(tr("Suitability"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                Text(tr(confidenceLabel), color = color, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

private fun scoreColor(score: Int): Color = when {
    score >= 90 -> CropGreen
    score >= 80 -> Color(0xFFFFBD16)
    score >= 70 -> Color(0xFF7D68EE)
    else -> Color(0xFFE95D5D)
}

private fun fieldLabel(key: String): String = when (key) {
    "temperature" -> "Temperature"
    "humidity" -> "Humidity"
    "soilMoisture" -> "Soil Moisture"
    "soilPh" -> "Soil pH"
    "nitrogen" -> "Nitrogen"
    "phosphorus" -> "Phosphorus"
    "potassium" -> "Potassium"
    else -> key
}
