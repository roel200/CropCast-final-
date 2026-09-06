#include <Arduino.h>
#include <time.h>
#include <WiFi.h>
#include <Wire.h>
#include <DHT.h>
#include <BH1750.h>
#include <ModbusMaster.h>
#include <Firebase_ESP_Client.h>
#include "secrets.h"

// CropCast sensor wiring. Change these values if your board uses other pins.
constexpr uint8_t DHT_PIN = 4;
constexpr uint8_t DHT_TYPE = DHT11;
constexpr uint8_t PH_PIN = 34;
constexpr uint8_t MOISTURE_PIN = 35;
constexpr uint8_t RS485_RX = 16;
constexpr uint8_t RS485_TX = 17;
constexpr uint8_t RS485_DE_RE = 5;
constexpr uint8_t NPK_SLAVE_ID = 1;

// Calibrate these four values using your actual soil probes.
constexpr int MOISTURE_DRY_RAW = 3200;
constexpr int MOISTURE_WET_RAW = 1250;
constexpr float PH_SLOPE = -5.70f;
constexpr float PH_OFFSET = 21.34f;

constexpr unsigned long LIVE_PUBLISH_INTERVAL_MS = 15UL * 1000UL;
constexpr unsigned long HISTORY_INTERVAL_MS = 60UL * 60UL * 1000UL;
constexpr unsigned long SETTINGS_INTERVAL_MS = 60UL * 1000UL;
constexpr unsigned long COMMAND_INTERVAL_MS = 5UL * 1000UL;
constexpr unsigned long WIFI_RETRY_INTERVAL_MS = 10UL * 1000UL;
constexpr char FIRMWARE_VERSION[] = "1.1.0";

struct SensorData {
  float temperature = 0.0f;
  float humidity = 0.0f;
  float soilMoisture = 0.0f;
  float soilPh = 0.0f;
  uint16_t nitrogen = 0;
  uint16_t phosphorus = 0;
  uint16_t potassium = 0;
  float lightIntensity = 0.0f;
  uint64_t timestamp = 0;
};

DHT dht(DHT_PIN, DHT_TYPE);
BH1750 lightMeter;
ModbusMaster npk;
FirebaseData firebaseData;
FirebaseAuth auth;
FirebaseConfig firebaseConfig;

unsigned long lastLivePublish = 0;
unsigned long lastHistoryPublish = 0;
unsigned long lastSettingsCheck = 0;
unsigned long lastCommandCheck = 0;
unsigned long lastWiFiAttempt = 0;
bool historyEnabled = true;
bool lightSensorReady = false;
uint16_t lastNitrogen = 0;
uint16_t lastPhosphorus = 0;
uint16_t lastPotassium = 0;

String deviceBasePath() {
  return String("/devices/") + DEVICE_ID;
}

uint64_t epochMillis() {
  time_t now;
  time(&now);
  return now > 1700000000 ? static_cast<uint64_t>(now) * 1000ULL : 0ULL;
}

String utcMonthKey(uint64_t timestamp) {
  time_t seconds = static_cast<time_t>(timestamp / 1000ULL);
  struct tm utcTime;
  gmtime_r(&seconds, &utcTime);
  char month[8];
  strftime(month, sizeof(month), "%Y-%m", &utcTime);
  return String(month);
}

String utcReadingKey(uint64_t timestamp) {
  time_t seconds = static_cast<time_t>(timestamp / 1000ULL);
  struct tm utcTime;
  gmtime_r(&seconds, &utcTime);
  char key[24];
  strftime(key, sizeof(key), "%Y-%m-%d_%H-%M-%S", &utcTime);
  return String(key);
}

void preTransmission() {
  digitalWrite(RS485_DE_RE, HIGH);
  delayMicroseconds(50);
}

void postTransmission() {
  delayMicroseconds(50);
  digitalWrite(RS485_DE_RE, LOW);
}

int readAveragedAnalog(uint8_t pin, uint8_t samples = 12) {
  uint32_t total = 0;
  for (uint8_t index = 0; index < samples; index++) {
    total += analogRead(pin);
    delay(8);
  }
  return static_cast<int>(total / samples);
}

float readSoilMoisture() {
  const int raw = readAveragedAnalog(MOISTURE_PIN);
  const float percent =
      (MOISTURE_DRY_RAW - raw) * 100.0f / (MOISTURE_DRY_RAW - MOISTURE_WET_RAW);
  return constrain(percent, 0.0f, 100.0f);
}

float readSoilPh() {
  const int raw = readAveragedAnalog(PH_PIN);
  const float voltage = raw * 3.3f / 4095.0f;
  return constrain(PH_SLOPE * voltage + PH_OFFSET, 0.0f, 14.0f);
}

bool updateNpkValues() {
  // Common 7-in-1 RS485 probes expose N/P/K at 0x001E..0x0020.
  // Confirm these addresses and the slave ID in your probe manual.
  const uint8_t result = npk.readHoldingRegisters(0x001E, 3);
  if (result != npk.ku8MBSuccess) {
    Serial.printf("NPK read failed, Modbus code: 0x%02X\n", result);
    return false;
  }

  lastNitrogen = npk.getResponseBuffer(0);
  lastPhosphorus = npk.getResponseBuffer(1);
  lastPotassium = npk.getResponseBuffer(2);
  return true;
}

bool readSensors(SensorData &reading) {
  const float temperature = dht.readTemperature();
  const float humidity = dht.readHumidity();
  if (isnan(temperature) || isnan(humidity)) {
    Serial.println("DHT11 read failed; skipping this publish cycle");
    return false;
  }

  updateNpkValues(); // Keep the last valid NPK values if one Modbus read fails.
  const float lux = lightSensorReady ? lightMeter.readLightLevel() : 0.0f;

  reading.temperature = temperature;
  reading.humidity = humidity;
  reading.soilMoisture = readSoilMoisture();
  reading.soilPh = readSoilPh();
  reading.nitrogen = lastNitrogen;
  reading.phosphorus = lastPhosphorus;
  reading.potassium = lastPotassium;
  reading.lightIntensity = max(0.0f, lux);
  reading.timestamp = epochMillis();
  return true;
}

void fillReadingJson(const SensorData &reading, FirebaseJson &json) {
  json.set("temperature", reading.temperature);
  json.set("humidity", reading.humidity);
  json.set("soilMoisture", reading.soilMoisture);
  json.set("soilPh", reading.soilPh);
  json.set("nitrogen", reading.nitrogen);
  json.set("phosphorus", reading.phosphorus);
  json.set("potassium", reading.potassium);
  json.set("lightIntensity", reading.lightIntensity);
  json.set("timestamp", static_cast<double>(reading.timestamp));
}

bool publishCurrentReading(const SensorData &reading) {
  FirebaseJson json;
  fillReadingJson(reading, json);
  const String path = deviceBasePath() + "/readings/current";
  if (!Firebase.RTDB.setJSON(&firebaseData, path, &json)) {
    Serial.println("Current reading upload failed: " + firebaseData.errorReason());
    return false;
  }
  return true;
}

void publishMonthlyHistory(const SensorData &reading) {
  if (!historyEnabled || reading.timestamp == 0) return;
  if (lastHistoryPublish != 0 && millis() - lastHistoryPublish < HISTORY_INTERVAL_MS) return;

  FirebaseJson json;
  fillReadingJson(reading, json);
  const String path = deviceBasePath() + "/readings/monthly/" +
                      utcMonthKey(reading.timestamp) + "/" + utcReadingKey(reading.timestamp);
  if (Firebase.RTDB.setJSON(&firebaseData, path, &json)) {
    lastHistoryPublish = millis();
    Serial.println("Monthly history sample saved to " + path);
  } else {
    Serial.println("Monthly history upload failed: " + firebaseData.errorReason());
  }
}

void publishDeviceStatus(uint64_t timestamp) {
  FirebaseJson status;
  status.set("online", true);
  status.set("lastSeen", static_cast<double>(timestamp));
  status.set("firmware", FIRMWARE_VERSION);
  status.set("sensors/0", "ESP32");
  status.set("sensors/1", "DHT11");
  status.set("sensors/2", "NPK");
  status.set("sensors/3", "pH");
  status.set("sensors/4", "LUX");
  status.set("wifiRssi", WiFi.RSSI());
  status.set("ipAddress", WiFi.localIP().toString());
  status.set("uptimeSeconds", millis() / 1000UL);

  if (!Firebase.RTDB.setJSON(&firebaseData, deviceBasePath() + "/status", &status)) {
    Serial.println("Status upload failed: " + firebaseData.errorReason());
  }
}

void publishSensorCycle() {
  SensorData reading;
  if (!readSensors(reading)) return;
  if (!publishCurrentReading(reading)) return;

  publishMonthlyHistory(reading);
  publishDeviceStatus(reading.timestamp);
  Serial.printf(
      "Uploaded: %.1f C, %.0f%% RH, %.0f%% moisture, pH %.2f, "
      "N/P/K %u/%u/%u, %.0f lux\n",
      reading.temperature, reading.humidity, reading.soilMoisture, reading.soilPh,
      reading.nitrogen, reading.phosphorus, reading.potassium,
      reading.lightIntensity);
}

void refreshRemoteSettings() {
  const String path = deviceBasePath() + "/settings/cloudHistoryEnabled";
  if (Firebase.RTDB.getBool(&firebaseData, path)) {
    historyEnabled = firebaseData.boolData();
  } else {
    Serial.println("Settings read failed; retaining current history setting: " +
                   firebaseData.errorReason());
  }
}

void checkRestartCommand() {
  const String commandPath = deviceBasePath() + "/commands/restart";
  if (!Firebase.RTDB.getJSON(&firebaseData, commandPath)) return;

  FirebaseJsonData statusValue;
  firebaseData.jsonObject().get(statusValue, "status");
  if (!statusValue.success || statusValue.to<String>() != "pending") return;

  Firebase.RTDB.setString(&firebaseData, commandPath + "/status", "restarting");
  Firebase.RTDB.setDouble(&firebaseData, commandPath + "/acknowledgedAt",
                          static_cast<double>(epochMillis()));
  Serial.println("Restart command acknowledged");
  delay(500);
  ESP.restart();
}

void connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (lastWiFiAttempt != 0 && millis() - lastWiFiAttempt < WIFI_RETRY_INTERVAL_MS) return;

  lastWiFiAttempt = millis();
  Serial.printf("Connecting to Wi-Fi: %s\n", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

void setup() {
  Serial.begin(115200);
  delay(300);
  Serial.println("\nCropCast ESP32 starting");

  pinMode(RS485_DE_RE, OUTPUT);
  digitalWrite(RS485_DE_RE, LOW);
  analogReadResolution(12);
  analogSetPinAttenuation(PH_PIN, ADC_11db);
  analogSetPinAttenuation(MOISTURE_PIN, ADC_11db);

  dht.begin();
  Wire.begin();
  lightSensorReady = lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);
  if (!lightSensorReady) Serial.println("BH1750 was not detected");

  Serial2.begin(9600, SERIAL_8N1, RS485_RX, RS485_TX);
  npk.begin(NPK_SLAVE_ID, Serial2);
  npk.preTransmission(preTransmission);
  npk.postTransmission(postTransmission);

  connectWiFi();
  const unsigned long connectionStarted = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - connectionStarted < 20000UL) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    Serial.println("Wi-Fi connected: " + WiFi.localIP().toString());
  } else {
    Serial.println("Wi-Fi unavailable; background retries will continue");
  }

  configTime(0, 0, "pool.ntp.org", "time.google.com");
  firebaseConfig.api_key = FIREBASE_API_KEY;
  firebaseConfig.database_url = FIREBASE_DATABASE_URL;
  auth.user.email = FIREBASE_USER_EMAIL;
  auth.user.password = FIREBASE_USER_PASSWORD;
  Firebase.reconnectWiFi(true);
  Firebase.begin(&firebaseConfig, &auth);
}

void loop() {
  connectWiFi();
  if (WiFi.status() != WL_CONNECTED || !Firebase.ready()) {
    delay(20);
    return;
  }

  const unsigned long now = millis();
  if (lastSettingsCheck == 0 || now - lastSettingsCheck >= SETTINGS_INTERVAL_MS) {
    lastSettingsCheck = now;
    refreshRemoteSettings();
  }
  if (lastLivePublish == 0 || now - lastLivePublish >= LIVE_PUBLISH_INTERVAL_MS) {
    lastLivePublish = now;
    publishSensorCycle();
  }
  if (lastCommandCheck == 0 || now - lastCommandCheck >= COMMAND_INTERVAL_MS) {
    lastCommandCheck = now;
    checkRestartCommand();
  }

  delay(20);
}
