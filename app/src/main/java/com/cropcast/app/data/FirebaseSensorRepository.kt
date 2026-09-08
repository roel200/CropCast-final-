package com.cropcast.app.data

import android.content.Context
import com.cropcast.app.data.model.AlertEvent
import com.cropcast.app.data.model.AlertSettings
import com.cropcast.app.data.model.AccountInfo
import com.cropcast.app.data.model.DeviceStatus
import com.cropcast.app.data.model.CropOutcomeFeedback
import com.cropcast.app.data.model.MonthlyCropRecommendation
import com.cropcast.app.data.model.SensorReading
import com.cropcast.app.data.model.isValidForRecommendation
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class FirebaseSensorRepository(
    context: Context,
    private val deviceId: String = "esp32-field-01",
    forceDemo: Boolean = false
) {
    private val credentialManager = androidx.credentials.CredentialManager.create(context.applicationContext)
    private val configured = !forceDemo && FirebaseApp.getApps(context).isNotEmpty()
    private val auth by lazy { FirebaseAuth.getInstance() }
    private val database by lazy { FirebaseDatabase.getInstance() }
    private val deviceRef by lazy { database.reference.child("devices").child(deviceId) }

    suspend fun connect(): Result<Unit> = runCatching {
        check(configured) { "Firebase is not configured. Add app/google-services.json." }
        check(auth.currentUser != null) { "Sign in before connecting to CropCast." }
    }

    fun isConfigured(): Boolean = configured

    fun hasAuthenticatedUser(): Boolean = configured && auth.currentUser != null

    suspend fun signIn(email: String, password: String): AccountInfo {
        check(configured) { "Firebase is not configured. Continue in demo mode instead." }
        auth.signInWithEmailAndPassword(email.trim(), password).await()
        return loadAccountInfo()
    }

    suspend fun signInWithGoogle(idToken: String): AccountInfo {
        check(configured) { "Firebase is not configured." }
        require(idToken.isNotBlank()) { "Google did not return an ID token." }
        auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
        return loadAccountInfo()
    }

    suspend fun continueAsGuest(): AccountInfo {
        check(configured) { "Firebase is not configured." }
        auth.signInAnonymously().await()
        return loadAccountInfo()
    }

    fun observeReading(): Flow<SensorReading> = valueFlow("readings/current", SensorReading()) {
        readingFrom(it) ?: SensorReading()
    }

    fun observeMonthlyReadings(): Flow<Map<String, List<SensorReading>>> =
        valueFlow("readings/monthly", emptyMap()) { snapshot ->
            snapshot.children.associate { month ->
                val monthKey = month.key.orEmpty()
                monthKey to month.children
                    .mapNotNull(::readingFrom)
                    .filter { reading -> monthKeyFor(reading.timestamp) == monthKey }
                    .sortedBy { it.timestamp }
            }.filterKeys { it.isNotBlank() }
        }

    fun observeMonthlyRecommendations(): Flow<Map<String, Map<String, MonthlyCropRecommendation>>> =
        valueFlow("recommendations/monthly", emptyMap()) { snapshot ->
            snapshot.children.mapNotNull { month ->
                val records = listOf("observed", "forecast").mapNotNull { recordType ->
                    month.child(recordType)
                        .getValue(MonthlyCropRecommendation::class.java)
                        ?.let { recordType to it }
                }.toMap()
                month.key.orEmpty().takeIf { it.isNotBlank() }?.let { it to records }
            }.toMap()
        }

    suspend fun saveMonthlyRecommendation(recommendation: MonthlyCropRecommendation) {
        connect().getOrThrow()
        val recordType = when (recommendation.recommendationType) {
            SeedRecommendationEngine.OBSERVED_MONTH -> "observed"
            SeedRecommendationEngine.NEXT_MONTH_FORECAST -> "forecast"
            else -> error("Unsupported recommendation type: ${recommendation.recommendationType}")
        }
        deviceRef.child("recommendations/monthly")
            .child(recommendation.monthKey)
            .child(recordType)
            .setValue(recommendation)
            .await()
    }

    fun observeOutcomeFeedback(): Flow<List<CropOutcomeFeedback>> =
        valueFlow("recommendations/feedback", emptyList()) { snapshot ->
            snapshot.children.mapNotNull { child ->
                child.getValue(CropOutcomeFeedback::class.java)?.copy(id = child.key.orEmpty())
            }.sortedByDescending { it.submittedAt }
        }

    suspend fun saveOutcomeFeedback(feedback: CropOutcomeFeedback) {
        connect().getOrThrow()
        require(feedback.plantedCrop.isNotBlank()) { "Enter the crop that was planted" }
        require(feedback.rating in 1..5) { "Outcome rating must be from 1 to 5" }
        require(feedback.harvestedKg >= 0.0) { "Harvest weight cannot be negative" }
        deviceRef.child("recommendations/feedback").push().setValue(feedback.copy(id = "")).await()
    }

    fun observeStatus(): Flow<DeviceStatus> = valueFlow("status", DeviceStatus()) { snapshot ->
        val lastSeen = snapshot.child("lastSeen").getValue(Long::class.java) ?: 0L
        val declaredOnline = snapshot.child("online").getValue(Boolean::class.java) ?: false
        DeviceStatus(
            online = declaredOnline,
            lastSeen = lastSeen,
            firmware = snapshot.child("firmware").getValue(String::class.java).orEmpty(),
            sensors = snapshot.child("sensors").children.mapNotNull { it.getValue(String::class.java) }
        )
    }

    fun observeSettings(): Flow<AlertSettings> = valueFlow("settings", AlertSettings()) {
        it.getValue(AlertSettings::class.java) ?: AlertSettings()
    }

    fun observeAlerts(): Flow<List<AlertEvent>> = valueFlow("alerts", emptyList()) { snapshot ->
        snapshot.children.mapNotNull { child ->
            child.getValue(AlertEvent::class.java)?.copy(id = child.key.orEmpty())
        }.sortedByDescending { it.timestamp }.take(20)
    }

    suspend fun updateAlertSettings(settings: AlertSettings) {
        deviceRef.child("settings").setValue(settings).await()
    }

    suspend fun testConnection(): Boolean {
        connect().getOrThrow()
        return deviceRef.child("status").get().await().exists()
    }

    suspend fun requestRestart() {
        connect().getOrThrow()
        deviceRef.child("commands/restart").setValue(
            mapOf(
                "status" to "pending",
                "requestedAt" to System.currentTimeMillis(),
                "requestedBy" to (auth.currentUser?.uid ?: "unknown")
            )
        ).await()
    }

    suspend fun loadAccountInfo(): AccountInfo {
        connect().getOrThrow()
        val user = requireNotNull(auth.currentUser)
        val profile = database.reference.child("users/${user.uid}/profile").get().await()
        return AccountInfo(
            displayName = profile.child("displayName").getValue(String::class.java) ?: user.displayName ?: "Guest Farmer",
            role = profile.child("role").getValue(String::class.java) ?: "Farmer",
            email = user.email ?: "Anonymous account",
            isAnonymous = user.isAnonymous
        )
    }

    suspend fun updateAccountInfo(displayName: String, role: String): AccountInfo {
        connect().getOrThrow()
        val user = requireNotNull(auth.currentUser)
        database.reference.child("users/${user.uid}/profile").setValue(
            mapOf("displayName" to displayName, "role" to role)
        ).await()
        return AccountInfo(displayName, role, user.email ?: "Anonymous account", user.isAnonymous)
    }

    suspend fun changePassword(password: String) {
        val user = requireNotNull(auth.currentUser)
        check(!user.isAnonymous && user.email != null) { "Sign in with an email account before changing a password." }
        user.updatePassword(password).await()
    }

    suspend fun signOut() {
        if (configured) {
            auth.signOut()
            try {
                credentialManager.clearCredentialState(androidx.credentials.ClearCredentialStateRequest())
            } catch (_: androidx.credentials.exceptions.ClearCredentialException) {
                // Firebase is signed out even when the credential provider is unavailable.
            }
        }
    }

    suspend fun acknowledgeAlert(id: String) {
        if (id.isNotBlank()) deviceRef.child("alerts/$id/acknowledged").setValue(true).await()
    }

    suspend fun simulateMoisture(value: Double) {
        val ref = deviceRef.child("readings/current")
        ref.child("soilMoisture").setValue(value).await()
        ref.child("timestamp").setValue(System.currentTimeMillis()).await()
    }

    suspend fun recordLowMoistureAlert(value: Double) {
        recordAlert("soil_moisture_low", "Moisture dropped to ${value.toInt()}%", value)
    }

    suspend fun recordAlert(type: String, message: String, value: Double) {
        deviceRef.child("alerts").push().setValue(
            AlertEvent(type = type, message = message, value = value, timestamp = System.currentTimeMillis())
        ).await()
    }

    private fun <T> valueFlow(path: String, fallback: T, mapper: (DataSnapshot) -> T): Flow<T> = callbackFlow {
        if (!configured) {
            trySend(fallback)
            close()
            return@callbackFlow
        }
        // Authenticate before attaching listeners so authenticated database rules do not
        // cancel the flow during the app's first launch.
        connect().getOrThrow()
        val reference = deviceRef.child(path)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { trySend(mapper(snapshot)) }
            override fun onCancelled(error: DatabaseError) { close(error.toException()) }
        }
        reference.addValueEventListener(listener)
        awaitClose { reference.removeEventListener(listener) }
    }

    private fun readingFrom(snapshot: DataSnapshot): SensorReading? {
        if (REQUIRED_READING_FIELDS.any { !snapshot.hasChild(it) }) return null
        return snapshot.getValue(SensorReading::class.java)?.takeIf { it.isValidForRecommendation() }
    }

    private fun monthKeyFor(timestamp: Long): String =
        YearMonth.from(Instant.ofEpochMilli(timestamp).atZone(ZoneOffset.UTC)).toString()

    private companion object {
        val REQUIRED_READING_FIELDS = listOf(
            "temperature",
            "humidity",
            "soilMoisture",
            "soilPh",
            "nitrogen",
            "phosphorus",
            "potassium",
            "lightIntensity",
            "timestamp"
        )
    }
}
