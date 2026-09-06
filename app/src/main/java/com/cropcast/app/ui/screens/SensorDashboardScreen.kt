package com.cropcast.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cropcast.app.ui.CropCastUiState
import com.cropcast.app.ui.components.MetricCard
import com.cropcast.app.ui.components.SectionTitle
import com.cropcast.app.ui.localization.tr
import com.cropcast.app.ui.theme.CropGreen
import com.cropcast.app.ui.theme.DeepGreen
import com.cropcast.app.ui.theme.Orange
import com.cropcast.app.ui.theme.Pink
import com.cropcast.app.ui.theme.Purple
import com.cropcast.app.ui.theme.Yellow
import java.text.DateFormat
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale

@Composable
fun SensorDashboardScreen(state: CropCastUiState) {
    val r = state.reading
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item { SensorStatusCard(state) }
        item { SectionTitle("📅", tr("Monthly Sensor Readings")) }
        if (state.monthlySummaries.isEmpty()) {
            item { EmptyMonthlyCollectionCard() }
        } else {
            items(state.monthlySummaries, key = { it.monthKey }) { summary ->
                MonthlyReadingCard(summary)
            }
        }
        item { SectionTitle("📊", tr("Current Conditions")) }
        item {
            MetricRow {
                MetricCard(Modifier.weight(1f), "💧", tr("Soil Moisture"), "${r.soilMoisture.toInt()}%", (r.soilMoisture / 100).toFloat(), CropGreen)
                MetricCard(Modifier.weight(1f), "🌡️", tr("Temperature"), "%.1f°C".format(r.temperature), (r.temperature / 45).toFloat(), Orange)
            }
        }
        item {
            MetricRow {
                MetricCard(Modifier.weight(1f), "🌫️", tr("Humidity"), "${r.humidity.toInt()}%", (r.humidity / 100).toFloat(), Purple)
                MetricCard(Modifier.weight(1f), "🧪", tr("Soil pH"), "%.1f".format(r.soilPh), (r.soilPh / 14).toFloat(), Pink)
            }
        }
        item {
            MetricRow {
                MetricCard(Modifier.weight(1f), "🧬", tr("Nitrogen"), "${r.nitrogen.toInt()} mg/kg", (r.nitrogen / 100).toFloat(), CropGreen)
                MetricCard(Modifier.weight(1f), "🔬", tr("Phosphorus"), "${r.phosphorus.toInt()} mg/kg", (r.phosphorus / 100).toFloat(), Yellow)
            }
        }
        item {
            MetricRow {
                MetricCard(Modifier.weight(1f), "⚡", tr("Potassium"), "${r.potassium.toInt()} mg/kg", (r.potassium / 100).toFloat(), Orange)
                MetricCard(Modifier.weight(1f), "☀️", tr("Light Intensity"), "${r.lightIntensity.toInt()} lux", (r.lightIntensity / 1000).toFloat(), Yellow)
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable
private fun MonthlyReadingCard(summary: com.cropcast.app.data.model.MonthlySensorSummary) {
    val average = summary.average
    val monthLabel = runCatching {
        val month = YearMonth.parse(summary.monthKey)
        "${month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} ${month.year}"
    }.getOrDefault(tr("Current month"))

    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("📅  $monthLabel", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Text(tr("Monthly averages"), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                }
                Text(
                    "${summary.sampleCount} ${tr("readings")}",
                    color = CropGreen,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                "🌡️ %.1f°C   💧 %.0f%%   🌫️ %.0f%%   🧪 %.1f".format(
                    average.temperature,
                    average.soilMoisture,
                    average.humidity,
                    average.soilPh
                ),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "N ${average.nitrogen.toInt()} · P ${average.phosphorus.toInt()} · " +
                    "K ${average.potassium.toInt()} mg/kg · ☀️ ${average.lightIntensity.toInt()} lux",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun EmptyMonthlyCollectionCard() {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Text(
            tr("Waiting for monthly sensor samples"),
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun SensorStatusCard(state: CropCastUiState) {
    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("🔬  ${tr("Sensor Status")}", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                Text(
                    if (state.status.online) "● ${tr("Online")}" else if (state.isDemo) "● ${tr("Demo")}" else "● ${tr("Offline")}",
                    color = if (state.status.online || state.isDemo) CropGreen else Color(0xFFE45151),
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))
            val sync = if (state.reading.timestamp > 0) DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(state.reading.timestamp)) else tr("waiting")
            Text("${tr("Last sync")}: $sync · ESP32 · DHT11 · NPK · pH · LUX", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(12.dp))
            Text("ESP32 ✓    DHT11 ✓    NPK ✓    pH ✓    LUX ✓", color = CropGreen, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        }
    }
}

@Composable
private fun MetricRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), content = content)
}
