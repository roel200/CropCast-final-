package com.cropcast.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cropcast.app.ui.screens.HomeScreen
import com.cropcast.app.ui.screens.SeedsScreen
import com.cropcast.app.ui.screens.SensorDashboardScreen
import com.cropcast.app.ui.screens.SettingsScreen
import com.cropcast.app.ui.screens.LoginScreen
import com.cropcast.app.R
import com.cropcast.app.ui.theme.CropGreen
import com.cropcast.app.ui.theme.CropCastTheme
import com.cropcast.app.ui.localization.LocalCropCastLanguage
import com.cropcast.app.ui.localization.tr

private data class NavItem(val label: String, val icon: ImageVector)

@Composable
fun CropCastApp(viewModel: CropCastViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var selected by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    CropCastTheme(darkTheme = state.settings.darkModeEnabled) {
    CompositionLocalProvider(LocalCropCastLanguage provides state.settings.language) {
    if (!state.isAuthenticated) {
        LoginScreen(
            isLoading = state.isAuthLoading,
            isDemo = state.isDemo,
            message = state.message,
            onLogin = viewModel::login,
            onContinueAsGuest = viewModel::continueAsGuest,
            onMessageShown = viewModel::clearMessage
        )
        return@CompositionLocalProvider
    }
    val items = listOf(
        NavItem(tr("Home"), Icons.Default.Home),
        NavItem(tr("Crop Recommendation"), Icons.Default.AutoAwesome),
        NavItem(tr("Sensor"), Icons.Default.Sensors)
    )
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (!showSettings) {
                CropCastHeader(isDemo = state.isDemo, onSettingsClick = { showSettings = true })
            }
        },
        bottomBar = {
            if (!showSettings) {
                NavigationBar(
                    modifier = Modifier.navigationBarsPadding(),
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    items.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selected == index,
                            onClick = { selected = index },
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label, fontSize = 9.sp, maxLines = 2) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = CropGreen,
                                selectedTextColor = CropGreen,
                                indicatorColor = Color.Transparent,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (showSettings) {
                SettingsScreen(
                    state = state,
                    onBack = { showSettings = false },
                    onSaveSettings = viewModel::saveSettings,
                    onTestConnection = viewModel::testConnection,
                    onRestartEsp32 = viewModel::restartEsp32,
                    onUpdateAccount = viewModel::updateAccount,
                    onChangePassword = viewModel::changePassword,
                    onSignOut = viewModel::signOut,
                    onMessageShown = viewModel::clearMessage
                )
            } else {
                when (selected) {
                    0 -> HomeScreen(
                        state = state,
                        onOpenRecommendations = { selected = 1 },
                        onOpenSensors = { selected = 2 }
                    )
                    1 -> SeedsScreen(
                        nextMonthRecommendation = state.nextMonthRecommendation,
                        monthlySummary = state.monthlySummary,
                        monthlyRecommendations = state.monthlyRecommendations,
                        forecastHistory = state.forecastHistory
                    )
                    else -> SensorDashboardScreen(state)
                }
            }
        }
    }
    }
    }
}

@Composable
private fun CropCastHeader(isDemo: Boolean, onSettingsClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(R.drawable.cropcast_logo),
                contentDescription = "CropCast logo",
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("CropCast", color = CropGreen, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Text(
                    tr("Smart Farming Assistant") + if (isDemo) " · ${tr("Demo")}" else "",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }
            HeaderButton(Icons.Default.Notifications, "Notifications", onClick = {})
            Spacer(Modifier.size(8.dp))
            HeaderButton(Icons.Default.Settings, "Settings", onClick = onSettingsClick)
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        IconButton(onClick = onClick) { Icon(icon, label, tint = MaterialTheme.colorScheme.secondary) }
    }
}
