package com.cropcast.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cropcast.app.data.model.AlertEvent
import com.cropcast.app.ui.CropCastUiState
import com.cropcast.app.ui.components.RoundedCard
import com.cropcast.app.ui.components.StatusPill
import com.cropcast.app.ui.theme.CropGreen
import com.cropcast.app.ui.theme.DeepGreen
import com.cropcast.app.ui.theme.Orange
import java.text.DateFormat
import java.util.Date

@Composable
fun WaterScreen(
    state: CropCastUiState,
    onThresholdChange: (Double) -> Unit,
    onAlertsEnabled: (Boolean) -> Unit,
    onSimulate: (Double) -> Unit,
    onAcknowledge: (String) -> Unit
) {
    val moisture = state.reading.soilMoisture
    val healthy = moisture >= state.settings.soilMoistureThreshold
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item { MoistureBadge(moisture, healthy) }
        item {
            RoundedCard(color = if (healthy) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer) {
                Column {
                    Text(if (healthy) "✅  Soil Moisture OK" else "⚠️  Soil Moisture Low", color = if (healthy) CropGreen else Color(0xFFE45151), fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(if (healthy) "Soil moisture is at a healthy level. No watering needed yet." else "Watering is recommended. Moisture is below your alert threshold.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            RoundedCard {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("💧  Current Moisture", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        StatusPill(if (healthy) "Good" else "Low", if (healthy) CropGreen else Color(0xFFE45151))
                    }
                    Spacer(Modifier.height(16.dp))
                    androidx.compose.material3.LinearProgressIndicator(
                        progress = { (moisture / 100).toFloat().coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(12.dp),
                        color = if (healthy) CropGreen else Orange,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("0%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text("Threshold: ${state.settings.soilMoistureThreshold.toInt()}%", color = Orange, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        Text("100%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
            }
        }
        item {
            RoundedCard {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("⚙️  Set Alert Threshold", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text("${state.settings.soilMoistureThreshold.toInt()}%", color = Orange, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                    }
                    Slider(
                        value = state.settings.soilMoistureThreshold.toFloat(),
                        onValueChange = { onThresholdChange(it.toDouble()) },
                        valueRange = 10f..80f,
                        steps = 69,
                        colors = SliderDefaults.colors(thumbColor = CropGreen, activeTrackColor = Orange, inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant)
                    )
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("10%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                        Text("80%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Text("Alert fires when soil moisture drops below ${state.settings.soilMoistureThreshold.toInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                }
            }
        }
        item {
            RoundedCard {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🔔  Alerts Enabled", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                        Text(if (state.settings.enabled) "Monitoring active" else "Monitoring paused", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Switch(
                        checked = state.settings.enabled,
                        onCheckedChange = onAlertsEnabled,
                        colors = SwitchDefaults.colors(checkedTrackColor = CropGreen)
                    )
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { onSimulate(28.0) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFE6E6), contentColor = Color(0xFFD34A4A)),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("🪫 Dry Soil", fontWeight = FontWeight.Bold) }
                Button(
                    onClick = { onSimulate(72.0) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE0FAE9), contentColor = DeepGreen),
                    shape = RoundedCornerShape(14.dp)
                ) { Text("💦 Wet Soil", fontWeight = FontWeight.Bold) }
            }
        }
        item { AlertHistory(state.alerts, onAcknowledge) }
        item { Spacer(Modifier.height(10.dp)) }
    }
}

@Composable
private fun MoistureBadge(moisture: Double, healthy: Boolean) {
    Box(
        Modifier.size(176.dp).background(if (healthy) DeepGreen else Color(0xFF843939), CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (healthy) "✅" else "⚠️", fontSize = 36.sp)
            Text(if (healthy) "Soil Moisture OK" else "Moisture Low", color = Color.White, fontWeight = FontWeight.Bold)
            Text("${moisture.toInt()}%", color = CropGreen, fontWeight = FontWeight.ExtraBold, fontSize = 31.sp)
        }
    }
}

@Composable
private fun AlertHistory(alerts: List<AlertEvent>, onAcknowledge: (String) -> Unit) {
    RoundedCard {
        Column {
            Text("📋  Alert History", fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
            Spacer(Modifier.height(10.dp))
            if (alerts.isEmpty()) Text("No moisture alerts yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            alerts.forEachIndexed { index, alert ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                Row(
                    Modifier.fillMaxWidth().clickable { onAcknowledge(alert.id) }.padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⚠️", fontSize = 22.sp)
                    Spacer(Modifier.size(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(alert.message.ifBlank { "Moisture dropped to ${alert.value.toInt()}%" }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold)
                        Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(alert.timestamp)), color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Text(if (alert.acknowledged) "✓" else "○", color = CropGreen, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                }
            }
        }
    }
}
