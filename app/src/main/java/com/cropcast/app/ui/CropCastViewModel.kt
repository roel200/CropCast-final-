package com.cropcast.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.cropcast.app.data.FirebaseSensorRepository
import com.cropcast.app.data.MonthlySensorAggregator
import com.cropcast.app.data.SeedRecommendationEngine
import com.cropcast.app.data.model.AlertEvent
import com.cropcast.app.data.model.AlertSettings
import com.cropcast.app.data.model.AccountInfo
import com.cropcast.app.data.model.DeviceStatus
import com.cropcast.app.data.model.CropOutcomeFeedback
import com.cropcast.app.data.model.MonthlyCropRecommendation
import com.cropcast.app.data.model.MonthlySensorSummary
import com.cropcast.app.data.model.SeedRecommendation
import com.cropcast.app.data.model.SensorReading
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.time.YearMonth
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class CropCastUiState(
    val reading: SensorReading = SensorReading(
        temperature = 28.4, humidity = 74.0, soilMoisture = 62.0, soilPh = 6.2,
        nitrogen = 45.0, phosphorus = 38.0, potassium = 52.0, lightIntensity = 680.0,
        timestamp = System.currentTimeMillis()
    ),
    val status: DeviceStatus = DeviceStatus(
        online = false,
        sensors = listOf("ESP32", "DHT11", "NPK", "pH", "LUX")
    ),
    val settings: AlertSettings = AlertSettings(),
    val alerts: List<AlertEvent> = emptyList(),
    val monthlySummaries: List<MonthlySensorSummary> = emptyList(),
    val monthlyRecommendations: List<MonthlyCropRecommendation> = emptyList(),
    val nextMonthRecommendation: MonthlyCropRecommendation? = null,
    val forecastHistory: List<MonthlyCropRecommendation> = emptyList(),
    val outcomeFeedback: List<CropOutcomeFeedback> = emptyList(),
    val recommendation: SeedRecommendation? = null,
    val account: AccountInfo = AccountInfo(),
    val isDemo: Boolean = false,
    val isAuthenticated: Boolean = false,
    val isAuthLoading: Boolean = true,
    val error: String? = null,
    val message: String? = null
) {
    val monthlySummary: MonthlySensorSummary
        get() = monthlySummaries.firstOrNull() ?: MonthlySensorSummary()
}

@OptIn(ExperimentalCoroutinesApi::class)
class CropCastViewModel(
    private val repository: FirebaseSensorRepository,
    demoCrop: String = "Potato"
) : ViewModel() {
    private val demoMonthlySummaries = demoMonthlyReadings(previewCrop = demoCrop)
        .map { (month, readings) -> MonthlySensorAggregator.summarize(month, readings) }
        .sortedByDescending { it.monthKey }
    private val demoMonthlyRecommendations = SeedRecommendationEngine.recommendHistory(demoMonthlySummaries)
    private val demoNextMonthRecommendation = SeedRecommendationEngine.recommendNextMonth(demoMonthlySummaries)
    private val seedState = MutableStateFlow(
        CropCastUiState(
            monthlySummaries = demoMonthlySummaries,
            monthlyRecommendations = demoMonthlyRecommendations,
            nextMonthRecommendation = demoNextMonthRecommendation,
            recommendation = demoNextMonthRecommendation?.recommendedCrop
                ?: demoMonthlyRecommendations.firstOrNull()?.recommendedCrop,
            isDemo = !repository.isConfigured(),
            isAuthLoading = repository.isConfigured()
        )
    )

    private data class RemoteState(
        val reading: SensorReading = SensorReading(),
        val status: DeviceStatus = DeviceStatus(),
        val settings: AlertSettings = AlertSettings(),
        val alerts: List<AlertEvent> = emptyList(),
        val monthlyReadings: Map<String, List<SensorReading>> = emptyMap(),
        val monthlyRecommendations: Map<String, Map<String, MonthlyCropRecommendation>> = emptyMap(),
        val outcomeFeedback: List<CropOutcomeFeedback> = emptyList()
    )

    private val remoteState = seedState
        .map { it.isAuthenticated to it.isDemo }
        .distinctUntilChanged()
        .flatMapLatest { (isAuthenticated, isDemo) ->
        if (!isAuthenticated || isDemo) {
            flowOf(RemoteState())
        } else {
            val baseRemoteState = combine(
                repository.observeReading(),
                repository.observeStatus(),
                repository.observeSettings(),
                repository.observeAlerts(),
                repository.observeMonthlyReadings()
            ) { reading, status, settings, alerts, monthlyReadings ->
                RemoteState(reading, status, settings, alerts, monthlyReadings)
            }
            combine(
                baseRemoteState,
                repository.observeMonthlyRecommendations(),
                repository.observeOutcomeFeedback()
            ) { remote, recommendations, feedback ->
                remote.copy(monthlyRecommendations = recommendations, outcomeFeedback = feedback)
            }
        }
    }

    val uiState: StateFlow<CropCastUiState> = combine(remoteState, seedState) { remote, local ->
        if (!local.isAuthenticated || local.isDemo) {
            val monthlyRecommendations = SeedRecommendationEngine.recommendHistory(local.monthlySummaries)
            val nextMonthRecommendation = SeedRecommendationEngine.recommendNextMonth(
                local.monthlySummaries,
                feedback = local.outcomeFeedback
            )
            local.copy(
                monthlyRecommendations = monthlyRecommendations,
                nextMonthRecommendation = nextMonthRecommendation,
                recommendation = nextMonthRecommendation?.recommendedCrop
                    ?: monthlyRecommendations.firstOrNull()?.recommendedCrop
            )
        } else {
            val onlineWindow = remote.settings.deviceOfflineMinutes * 60_000L
            val effectiveStatus = remote.status.copy(
                online = remote.status.online && remote.status.lastSeen > 0 &&
                    System.currentTimeMillis() - remote.status.lastSeen < onlineWindow
            )
            val monthlySummaries = remote.monthlyReadings
                .map { (month, readings) -> MonthlySensorAggregator.summarize(month, readings) }
                .filter { it.hasData }
                .sortedByDescending { it.monthKey }
            val monthlyRecommendations = SeedRecommendationEngine.recommendHistory(monthlySummaries)
            val nextMonthRecommendation = SeedRecommendationEngine.recommendNextMonth(
                monthlySummaries,
                feedback = remote.outcomeFeedback
            )
            val forecastHistory = remote.monthlyRecommendations.values
                .flatMap { it.values }
                .filter { it.recommendationType == SeedRecommendationEngine.NEXT_MONTH_FORECAST }
                .distinctBy { it.monthKey }
                .sortedByDescending { it.monthKey }
            local.copy(
                reading = remote.reading,
                status = effectiveStatus,
                settings = remote.settings,
                alerts = remote.alerts,
                monthlySummaries = monthlySummaries,
                monthlyRecommendations = monthlyRecommendations,
                nextMonthRecommendation = nextMonthRecommendation ?: forecastHistory.firstOrNull(),
                forecastHistory = forecastHistory,
                outcomeFeedback = remote.outcomeFeedback,
                recommendation = nextMonthRecommendation?.recommendedCrop
                    ?: monthlyRecommendations.firstOrNull()?.recommendedCrop
            )
        }
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        seedState.value
    )

    private val lastAlertAt = mutableMapOf<String, Long>()
    private val persistedRecommendationSignatures = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            if (!repository.isConfigured()) {
                seedState.value = seedState.value.copy(isAuthLoading = false)
            } else if (repository.hasAuthenticatedUser()) {
                runCatching { repository.loadAccountInfo() }
                    .onSuccess { account ->
                        seedState.value = seedState.value.copy(
                            account = account,
                            isAuthenticated = true,
                            isAuthLoading = false,
                            error = null
                        )
                    }
                    .onFailure { error ->
                        repository.signOut()
                        seedState.value = seedState.value.copy(isAuthLoading = false, error = error.message)
                    }
            } else {
                seedState.value = seedState.value.copy(isAuthLoading = false)
            }
        }
        viewModelScope.launch {
            uiState.collect { state ->
                if (!state.isAuthenticated || state.isDemo || !state.settings.enabled) return@collect
                val r = state.reading
                val s = state.settings
                if (s.lowMoistureAlertEnabled && r.soilMoisture < s.soilMoistureThreshold) {
                    sendRepeatedAlert("soil_moisture_low", "Moisture dropped to ${r.soilMoisture.toInt()}%", r.soilMoisture, s.repeatIntervalMinutes)
                }
                if (r.temperature > s.highTemperatureThreshold) {
                    sendRepeatedAlert("temperature_high", "Temperature reached ${"%.1f".format(r.temperature)}°C", r.temperature, s.repeatIntervalMinutes)
                }
                if (r.soilPh < s.minSoilPh || r.soilPh > s.maxSoilPh) {
                    sendRepeatedAlert("soil_ph_out_of_range", "Soil pH is ${"%.1f".format(r.soilPh)}", r.soilPh, s.repeatIntervalMinutes)
                }
            }
        }
        viewModelScope.launch {
            remoteState
                .map { it.monthlyReadings to it.outcomeFeedback }
                .distinctUntilChanged()
                .collect { (monthlyReadings, feedback) ->
                    if (!repository.hasAuthenticatedUser() || monthlyReadings.isEmpty()) return@collect
                    val summaries = monthlyReadings
                        .map { (month, readings) -> MonthlySensorAggregator.summarize(month, readings) }
                        .filter { it.hasData }
                    val recommendations = buildList {
                        addAll(SeedRecommendationEngine.recommendHistory(summaries))
                        SeedRecommendationEngine.recommendNextMonth(summaries, feedback = feedback)?.let(::add)
                    }
                    recommendations.forEach { recommendation ->
                        val signature = listOf(
                            recommendation.recommendationType,
                            recommendation.monthKey,
                            recommendation.cropName,
                            recommendation.score,
                            recommendation.sampleCount,
                            recommendation.basedOnMonths.joinToString(","),
                            recommendation.scoreByCrop.entries.joinToString(",")
                        ).joinToString("|")
                        if (signature !in persistedRecommendationSignatures) {
                            runCatching { repository.saveMonthlyRecommendation(recommendation) }
                                .onSuccess { persistedRecommendationSignatures += signature }
                        }
                    }
                }
        }
    }

    fun login(email: String, password: String) = viewModelScope.launch {
        val normalizedEmail = email.trim()
        when {
            normalizedEmail.isBlank() -> return@launch showMessage("Enter your email address")
            !android.util.Patterns.EMAIL_ADDRESS.matcher(normalizedEmail).matches() ->
                return@launch showMessage("Enter a valid email address")
            password.length < 6 -> return@launch showMessage("Password must contain at least 6 characters")
        }
        seedState.value = seedState.value.copy(isAuthLoading = true, message = null)
        if (uiState.value.isDemo) {
            val displayName = normalizedEmail.substringBefore('@')
                .replace('.', ' ')
                .replace('_', ' ')
                .trim()
                .replaceFirstChar { it.uppercase() }
                .ifBlank { "Demo Farmer" }
            seedState.value = seedState.value.copy(
                account = AccountInfo(
                    displayName = displayName,
                    role = "Farmer",
                    email = normalizedEmail,
                    isAnonymous = false
                ),
                isAuthenticated = true,
                isAuthLoading = false,
                error = null,
                message = null
            )
            return@launch
        }
        runCatching { repository.signIn(normalizedEmail, password) }
            .onSuccess { account ->
                seedState.value = seedState.value.copy(
                    account = account,
                    isAuthenticated = true,
                    isAuthLoading = false,
                    error = null,
                    message = null
                )
            }
            .onFailure { error ->
                seedState.value = seedState.value.copy(
                    isAuthLoading = false,
                    message = error.message ?: "Login failed"
                )
            }
    }

    fun continueAsGuest() = viewModelScope.launch {
        seedState.value = seedState.value.copy(isAuthLoading = true, message = null)
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(
                account = AccountInfo(),
                isAuthenticated = true,
                isAuthLoading = false,
                message = null
            )
        } else {
            runCatching { repository.continueAsGuest() }
                .onSuccess { account ->
                    seedState.value = seedState.value.copy(
                        account = account,
                        isAuthenticated = true,
                        isAuthLoading = false,
                        error = null
                    )
                }
                .onFailure { error ->
                    seedState.value = seedState.value.copy(
                        isAuthLoading = false,
                        message = error.message ?: "Could not continue as guest"
                    )
                }
        }
    }

    fun setThreshold(value: Double) {
        val settings = uiState.value.settings.copy(soilMoistureThreshold = value)
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(settings = settings)
        } else viewModelScope.launch { repository.updateAlertSettings(settings) }
    }

    fun setAlertsEnabled(enabled: Boolean) {
        val settings = uiState.value.settings.copy(enabled = enabled)
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(settings = settings)
        } else viewModelScope.launch { repository.updateAlertSettings(settings) }
    }

    fun saveSettings(settings: AlertSettings) {
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(settings = settings, message = "Settings saved in demo mode")
        } else viewModelScope.launch {
            runCatching { repository.updateAlertSettings(settings) }
                .onSuccess { showMessage("Settings synchronized") }
                .onFailure { showMessage(it.message ?: "Could not save settings") }
        }
    }

    fun testConnection() = viewModelScope.launch {
        if (uiState.value.isDemo) return@launch showMessage("Demo mode is working; Firebase is not configured")
        runCatching { repository.testConnection() }
            .onSuccess { showMessage(if (it) "ESP32 connection verified" else "Firebase connected, but the ESP32 status is missing") }
            .onFailure { showMessage(it.message ?: "Connection test failed") }
    }

    fun restartEsp32() = viewModelScope.launch {
        if (uiState.value.isDemo) return@launch showMessage("Restart command simulated in demo mode")
        runCatching { repository.requestRestart() }
            .onSuccess { showMessage("Restart command sent to the ESP32") }
            .onFailure { showMessage(it.message ?: "Restart command failed") }
    }

    fun updateAccount(displayName: String, role: String) = viewModelScope.launch {
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(
                account = uiState.value.account.copy(displayName = displayName, role = role),
                message = "Profile saved in demo mode"
            )
        } else runCatching { repository.updateAccountInfo(displayName, role) }
            .onSuccess { seedState.value = seedState.value.copy(account = it, message = "Profile updated") }
            .onFailure { showMessage(it.message ?: "Could not update profile") }
    }

    fun changePassword(password: String) = viewModelScope.launch {
        if (password.length < 6) return@launch showMessage("Password must contain at least 6 characters")
        if (uiState.value.isDemo) return@launch showMessage("Password changes require an email account")
        runCatching { repository.changePassword(password) }
            .onSuccess { showMessage("Password changed") }
            .onFailure { showMessage(it.message ?: "Password change failed") }
    }

    fun signOut() = viewModelScope.launch {
        runCatching { repository.signOut() }
            .onSuccess {
                seedState.value = CropCastUiState(
                    isDemo = !repository.isConfigured(),
                    isAuthLoading = false,
                    message = "Signed out"
                )
            }
            .onFailure { showMessage(it.message ?: "Sign out failed") }
    }

    fun clearMessage() { seedState.value = seedState.value.copy(message = null) }

    private fun showMessage(message: String) {
        seedState.value = seedState.value.copy(message = message)
    }

    private suspend fun sendRepeatedAlert(type: String, message: String, value: Double, repeatMinutes: Int) {
        val now = System.currentTimeMillis()
        val previous = lastAlertAt[type] ?: 0L
        if (now - previous >= repeatMinutes * 60_000L) {
            runCatching { repository.recordAlert(type, message, value) }
                .onSuccess { lastAlertAt[type] = now }
        }
    }

    fun simulateMoisture(value: Double) {
        if (uiState.value.isDemo) {
            val event = if (value < uiState.value.settings.soilMoistureThreshold) {
                AlertEvent(
                    id = "demo-${System.currentTimeMillis()}",
                    message = "Moisture dropped to ${value.toInt()}%",
                    value = value,
                    timestamp = System.currentTimeMillis()
                )
            } else null
            seedState.value = seedState.value.copy(
                reading = uiState.value.reading.copy(soilMoisture = value, timestamp = System.currentTimeMillis()),
                alerts = listOfNotNull(event) + seedState.value.alerts
            )
        } else viewModelScope.launch { repository.simulateMoisture(value) }
    }

    fun acknowledge(id: String) {
        if (uiState.value.isDemo) {
            seedState.value = seedState.value.copy(
                alerts = seedState.value.alerts.map { if (it.id == id) it.copy(acknowledged = true) else it }
            )
        } else viewModelScope.launch { repository.acknowledgeAlert(id) }
    }
}

private fun currentMonthKey(timestamp: Long = System.currentTimeMillis()): String =
    SimpleDateFormat("yyyy-MM", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        .format(Date(timestamp))

private val demoCropReadings = mapOf(
    "tomato" to SensorReading(21.5, 74.0, 43.0, 6.4, 87.0, 60.0, 125.0),
    "okra" to SensorReading(33.7, 51.0, 48.0, 6.72, 100.0, 76.0, 66.0),
    "alugbati" to SensorReading(34.0, 52.0, 58.0, 7.4, 180.0, 90.0, 120.0),
    "potato" to SensorReading(12.6, 89.0, 52.5, 4.87, 87.0, 95.0, 110.0),
    "rice" to SensorReading(26.3, 82.7, 92.5, 5.63, 64.0, 37.3, 36.8),
    "corn" to SensorReading(22.5, 74.0, 47.5, 6.85, 195.0, 34.0, 50.0),
    "eggplant" to SensorReading(29.4, 65.0, 52.5, 5.6, 100.0, 77.0, 145.0),
    "cucumber" to SensorReading(31.2, 78.0, 82.5, 6.15, 175.0, 95.0, 195.0),
    "cabbage" to SensorReading(17.5, 65.0, 72.5, 6.9, 123.0, 90.0, 175.0),
    "sweet potato" to SensorReading(25.0, 78.0, 47.5, 5.6, 100.0, 42.0, 125.0),
    "lettuce" to SensorReading(8.7, 65.0, 72.5, 6.9, 86.0, 67.0, 190.0),
    "spinach" to SensorReading(15.0, 65.0, 72.5, 6.5, 100.0, 50.0, 94.0)
)

private fun demoMonthlyReadings(
    now: Long = System.currentTimeMillis(),
    previewCrop: String = "Potato"
): Map<String, List<SensorReading>> {
    val currentMonth = YearMonth.parse(currentMonthKey(now))
    return (1L..12L).associate { monthsAgo ->
        val month = currentMonth.minusMonths(monthsAgo)
        val readings = (1..8).map { sample ->
            val day = sample.coerceAtMost(month.lengthOfMonth())
            val preview = if (monthsAgo == 1L) demoCropReadings[previewCrop.lowercase()] else null
            SensorReading(
                temperature = preview?.temperature ?: 26.5 + monthsAgo * 0.25 + (sample % 5) * 0.45,
                humidity = preview?.humidity ?: 68.0 + (sample % 6),
                soilMoisture = preview?.soilMoisture ?: 56.0 + (sample % 7),
                soilPh = preview?.soilPh ?: 6.0 + (sample % 4) * 0.1,
                nitrogen = preview?.nitrogen ?: 42.0 + (sample % 5),
                phosphorus = preview?.phosphorus ?: 35.0 + (sample % 4),
                potassium = preview?.potassium ?: 49.0 + (sample % 6),
                lightIntensity = 610.0 + (sample % 8) * 18,
                timestamp = month.atDay(day).atTime(12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
            )
        }
        month.toString() to readings
    }
}

class CropCastViewModelFactory(
    private val repository: FirebaseSensorRepository,
    private val demoCrop: String = "Potato"
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = CropCastViewModel(repository, demoCrop) as T
}
