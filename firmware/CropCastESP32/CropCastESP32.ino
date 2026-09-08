// CropCast ESP32 firmware.
//
// Reads the CropCast sensor set and writes to the Firebase Realtime Database
// paths consumed by the Android dashboard. See README.md for wiring, the serial
// console reference, and the probe calibration procedure.
//
// Data contract (do not change without changing the Android app AND
// firebase/database.rules.json): every reading record carries exactly these
// nine fields, and `timestamp` is Unix milliseconds UTC.
//
//   temperature humidity soilMoisture soilPh nitrogen phosphorus potassium
//   lightIntensity timestamp
//
// Untrusted values are never written into readings/monthly, because the app
// averages that path unweighted to pick a crop. See publishMonthlyHistory().

// ---------------------------------------------------------------------------
// A. Includes
// ---------------------------------------------------------------------------
#include <Arduino.h>
#include <esp_system.h>
#include <math.h>
#include <stdarg.h>
#include <string.h>
#include <strings.h>  // strcasecmp / strncasecmp, used by the serial console
#include <time.h>
#include <WiFi.h>
#include <Wire.h>
#include <DHT.h>
#include <BH1750.h>
#include <ModbusMaster.h>
#include <Firebase_ESP_Client.h>
#include <addons/TokenHelper.h>
#include "secrets.h"

// ---------------------------------------------------------------------------
// B. Build-time configuration. Every value here can be overridden from
//    secrets.h, which is gitignored, so per-device settings never touch this
//    tracked sketch. See secrets.example.h.
// ---------------------------------------------------------------------------
#ifndef CROPCAST_LOG_LEVEL
#define CROPCAST_LOG_LEVEL 4  // 0=off 1=error 2=warn 3=info 4=debug 5=trace
#endif
#ifndef CROPCAST_CONSOLE_ENABLED
#define CROPCAST_CONSOLE_ENABLED true
#endif

// Mark a channel false until it is physically wired. An unwired channel never
// contributes a value, so it cannot corrupt the monthly crop averages.
#ifndef CROPCAST_SENSOR_DHT_ENABLED
#define CROPCAST_SENSOR_DHT_ENABLED true
#endif
#ifndef CROPCAST_SENSOR_MOISTURE_ENABLED
#define CROPCAST_SENSOR_MOISTURE_ENABLED true
#endif
#ifndef CROPCAST_SENSOR_PH_ENABLED
#define CROPCAST_SENSOR_PH_ENABLED true
#endif
#ifndef CROPCAST_SENSOR_NPK_ENABLED
#define CROPCAST_SENSOR_NPK_ENABLED true
#endif
#ifndef CROPCAST_SENSOR_LIGHT_ENABLED
#define CROPCAST_SENSOR_LIGHT_ENABLED true
#endif

// When false, readings/current is withheld entirely while any scored sensor is
// missing, instead of publishing a logged 0 placeholder for it.
#ifndef CROPCAST_PUBLISH_CURRENT_WHEN_INCOMPLETE
#define CROPCAST_PUBLISH_CURRENT_WHEN_INCOMPLETE true
#endif

// ---------------------------------------------------------------------------
// C. CropCast sensor wiring. Change these values if your board uses other pins.
// ---------------------------------------------------------------------------
constexpr uint8_t DHT_PIN = 4;
constexpr uint8_t DHT_TYPE = DHT11;
constexpr uint8_t PH_PIN = 34;
constexpr uint8_t MOISTURE_PIN = 35;
constexpr uint8_t RS485_RX = 16;
constexpr uint8_t RS485_TX = 17;
constexpr uint8_t RS485_DE_RE = 5;
constexpr uint8_t NPK_SLAVE_ID = 1;
constexpr uint16_t NPK_BASE_REGISTER = 0x001E;

// ---------------------------------------------------------------------------
// D. Calibrate these four values using your actual soil probes.
//    Run the serial `cal` command, then paste its output into secrets.h.
// ---------------------------------------------------------------------------
#ifndef CROPCAST_MOISTURE_DRY_RAW
#define CROPCAST_MOISTURE_DRY_RAW 3200
#endif
#ifndef CROPCAST_MOISTURE_WET_RAW
#define CROPCAST_MOISTURE_WET_RAW 1250
#endif
#ifndef CROPCAST_PH_SLOPE
#define CROPCAST_PH_SLOPE -5.70f
#endif
#ifndef CROPCAST_PH_OFFSET
#define CROPCAST_PH_OFFSET 21.34f
#endif

constexpr int MOISTURE_DRY_RAW = CROPCAST_MOISTURE_DRY_RAW;
constexpr int MOISTURE_WET_RAW = CROPCAST_MOISTURE_WET_RAW;
constexpr float PH_SLOPE = CROPCAST_PH_SLOPE;
constexpr float PH_OFFSET = CROPCAST_PH_OFFSET;

// ---------------------------------------------------------------------------
// E. Timing
// ---------------------------------------------------------------------------
constexpr unsigned long LIVE_PUBLISH_INTERVAL_MS = 15UL * 1000UL;
// History has no interval of its own: the key is the UTC hour, so the first
// publish cycle of each new hour stores that hour's sample exactly once.
constexpr unsigned long SETTINGS_INTERVAL_MS = 60UL * 1000UL;
constexpr unsigned long COMMAND_INTERVAL_MS = 5UL * 1000UL;
constexpr unsigned long WIFI_RETRY_INTERVAL_MS = 10UL * 1000UL;
constexpr unsigned long HEARTBEAT_INTERVAL_MS = 60UL * 1000UL;
constexpr unsigned long SENSOR_STALE_AFTER_MS = 5UL * 60UL * 1000UL;
constexpr unsigned long HISTORY_LOG_INTERVAL_MS = 60UL * 1000UL;
constexpr unsigned long NTP_WARN_INTERVAL_MS = 60UL * 1000UL;
constexpr unsigned long DHT_MIN_INTERVAL_MS = 2UL * 1000UL;
constexpr unsigned long CAL_STREAM_INTERVAL_MS = 500UL;
constexpr time_t MIN_VALID_EPOCH_SECONDS = 1700000000;
constexpr char FIRMWARE_VERSION[] = "2.0.0";

// ---------------------------------------------------------------------------
// F. Plausibility limits. These mirror firebase/database.rules.json exactly.
//    A value outside them makes the server reject the WHOLE write, so a field
//    that fails here is marked untrusted and published as a placeholder rather
//    than taking the other eight fields down with it.
// ---------------------------------------------------------------------------
constexpr float LIMIT_TEMPERATURE_MIN = -20.0f;
constexpr float LIMIT_TEMPERATURE_MAX = 80.0f;
constexpr float LIMIT_HUMIDITY_MIN = 0.0f;
constexpr float LIMIT_HUMIDITY_MAX = 100.0f;
constexpr float LIMIT_MOISTURE_MIN = 0.0f;
constexpr float LIMIT_MOISTURE_MAX = 100.0f;
constexpr float LIMIT_PH_MIN = 0.0f;
constexpr float LIMIT_PH_MAX = 14.0f;
// nitrogen, phosphorus, potassium and lightIntensity only need to be >= 0.

// Advisory only: a floating ADC pin usually pins to a rail or drifts wildly.
constexpr int ANALOG_FLOOR_RAW = 30;
constexpr int ANALOG_CEILING_RAW = 4065;
constexpr float ANALOG_NOISE_SD = 400.0f;

// ---------------------------------------------------------------------------
// G. Types
// ---------------------------------------------------------------------------
constexpr uint8_t LOG_OFF = 0;
constexpr uint8_t LOG_ERROR = 1;
constexpr uint8_t LOG_WARN = 2;
constexpr uint8_t LOG_INFO = 3;
constexpr uint8_t LOG_DEBUG = 4;
constexpr uint8_t LOG_TRACE = 5;

constexpr char TAG_BOOT[] = "BOOT";
constexpr char TAG_WIFI[] = "WIFI";
constexpr char TAG_NTP[] = "NTP";
constexpr char TAG_FB[] = "FB";
constexpr char TAG_DHT[] = "DHT";
constexpr char TAG_MOIST[] = "MOIST";
constexpr char TAG_PH[] = "PH";
constexpr char TAG_NPK[] = "NPK";
constexpr char TAG_LUX[] = "LUX";
constexpr char TAG_PUB[] = "PUB";
constexpr char TAG_HIST[] = "HIST";
constexpr char TAG_CMD[] = "CMD";
constexpr char TAG_CAL[] = "CAL";
constexpr char TAG_CLI[] = "CLI";
constexpr char TAG_HEALTH[] = "HEALTH";

enum SensorId : uint8_t {
  SENSOR_DHT = 0,
  SENSOR_MOISTURE,
  SENSOR_PH,
  SENSOR_NPK,
  SENSOR_LIGHT,
  SENSOR_COUNT
};

// Two orthogonal facts per channel: whether it is supposed to be wired, and
// whether we currently hold a fresh trustworthy value from it.
struct SensorChannel {
  const char *name;
  bool expected;
  bool detected;
  bool hasValue;
  unsigned long lastGoodMs;
  uint32_t okCount;
  uint32_t failCount;
  uint8_t consecutiveFails;
  const char *lastError;  // static string only; never heap allocated
};

enum FieldBit : uint16_t {
  FIELD_TEMPERATURE = 1 << 0,
  FIELD_HUMIDITY = 1 << 1,
  FIELD_MOISTURE = 1 << 2,
  FIELD_PH = 1 << 3,
  FIELD_NITROGEN = 1 << 4,
  FIELD_PHOSPHORUS = 1 << 5,
  FIELD_POTASSIUM = 1 << 6,
  FIELD_LIGHT = 1 << 7,
  FIELD_TIMESTAMP = 1 << 8
};

// Mirrors SeedRecommendationEngine.scoreAll(): seven scored fields.
// lightIntensity is deliberately excluded because the app does not score it.
constexpr uint16_t SCORED_FIELDS = FIELD_TEMPERATURE | FIELD_HUMIDITY | FIELD_MOISTURE |
                                   FIELD_PH | FIELD_NITROGEN | FIELD_PHOSPHORUS |
                                   FIELD_POTASSIUM;
constexpr uint16_t HISTORY_REQUIRED_FIELDS = SCORED_FIELDS | FIELD_TIMESTAMP;

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
  uint16_t trusted = 0;  // bitwise OR of FieldBit
};

struct AnalogStats {
  int mean;
  int minimum;
  int maximum;
  float stdDev;
};

struct CalPoint {
  float ph;
  float volts;
  float stdDev;
};

struct ConsoleCommand {
  const char *name;
  void (*handler)(const char *args);
  const char *usage;
  const char *help;
};

// ---------------------------------------------------------------------------
// H. Globals
// ---------------------------------------------------------------------------
DHT dht(DHT_PIN, DHT_TYPE);
BH1750 lightMeter;
ModbusMaster npk;
FirebaseData firebaseData;
FirebaseAuth auth;
FirebaseConfig firebaseConfig;

String basePath;  // computed once in setup(); avoids rebuilding it every cycle

unsigned long lastLivePublish = 0;
unsigned long lastSettingsCheck = 0;
unsigned long lastCommandCheck = 0;
unsigned long lastWiFiAttempt = 0;
unsigned long lastHeartbeat = 0;
unsigned long lastHistorySkipLog = 0;
unsigned long lastNtpWarn = 0;
unsigned long lastCalStream = 0;

// Bug 3: history is keyed by the UTC hour actually published, not by uptime,
// so rebooting inside one hour cannot append extra samples to the month.
char lastHistorySlot[24] = "";
bool historySlotProbed = false;
const char *historyState = "withheld";

bool historyEnabled = true;
bool historyLocalOverride = false;  // set by the console `history` command
bool lightSensorReady = false;
bool firebaseAnnounced = false;
bool timeAnnounced = false;
uint8_t runtimeLogLevel = CROPCAST_LOG_LEVEL;

// Bug 4: these start untrusted. channels[SENSOR_NPK].hasValue stays false until
// a real Modbus read succeeds, so N/P/K can never publish as a meaningful 0.
uint16_t lastNitrogen = 0;
uint16_t lastPhosphorus = 0;
uint16_t lastPotassium = 0;
uint8_t lastModbusResult = 0;

float cachedTemperature = NAN;
float cachedHumidity = NAN;
unsigned long lastDhtReadMs = 0;

SensorData lastPublished;

SensorChannel channels[SENSOR_COUNT] = {
    {"DHT11", CROPCAST_SENSOR_DHT_ENABLED, false, false, 0, 0, 0, 0, "not read yet"},
    {"MOISTURE", CROPCAST_SENSOR_MOISTURE_ENABLED, false, false, 0, 0, 0, 0, "not read yet"},
    {"PH", CROPCAST_SENSOR_PH_ENABLED, false, false, 0, 0, 0, 0, "not read yet"},
    {"NPK", CROPCAST_SENSOR_NPK_ENABLED, false, false, 0, 0, 0, 0, "not read yet"},
    {"BH1750", CROPCAST_SENSOR_LIGHT_ENABLED, false, false, 0, 0, 0, 0, "not read yet"}};

bool calibrationMode = false;
bool calDryCaptured = false;
bool calWetCaptured = false;
AnalogStats calDry = {0, 0, 0, 0.0f};
AnalogStats calWet = {0, 0, 0, 0.0f};
uint8_t calPhCount = 0;
CalPoint calPhPoints[2];

// ---------------------------------------------------------------------------
// I. Logging
// ---------------------------------------------------------------------------
void logPrintf(uint8_t level, const char *tag, const char *fmt, ...);

// Two-tier macros: a level above the compiled ceiling expands to nothing, so
// its format strings are not kept in flash at all.
#if CROPCAST_LOG_LEVEL >= 1
#define LOG_E(tag, ...) logPrintf(LOG_ERROR, tag, __VA_ARGS__)
#else
#define LOG_E(tag, ...) do {} while (0)
#endif
#if CROPCAST_LOG_LEVEL >= 2
#define LOG_W(tag, ...) logPrintf(LOG_WARN, tag, __VA_ARGS__)
#else
#define LOG_W(tag, ...) do {} while (0)
#endif
#if CROPCAST_LOG_LEVEL >= 3
#define LOG_I(tag, ...) logPrintf(LOG_INFO, tag, __VA_ARGS__)
#else
#define LOG_I(tag, ...) do {} while (0)
#endif
#if CROPCAST_LOG_LEVEL >= 4
#define LOG_D(tag, ...) logPrintf(LOG_DEBUG, tag, __VA_ARGS__)
#else
#define LOG_D(tag, ...) do {} while (0)
#endif
#if CROPCAST_LOG_LEVEL >= 5
#define LOG_T(tag, ...) logPrintf(LOG_TRACE, tag, __VA_ARGS__)
#else
#define LOG_T(tag, ...) do {} while (0)
#endif

void logPrintf(uint8_t level, const char *tag, const char *fmt, ...) {
  if (level > runtimeLogLevel || level == LOG_OFF) return;
  static const char LEVEL_LETTER[] = {'-', 'E', 'W', 'I', 'D', 'T'};

  char body[200];
  va_list args;
  va_start(args, fmt);
  vsnprintf(body, sizeof(body), fmt, args);
  va_end(args);

  const unsigned long ms = millis();
  char line[264];
  snprintf(line, sizeof(line), "[%6lu.%03lu] %c/%-6s: %s", ms / 1000UL, ms % 1000UL,
           LEVEL_LETTER[level <= LOG_TRACE ? level : 0], tag, body);
  Serial.println(line);
}

const char *logLevelName(uint8_t level) {
  switch (level) {
    case LOG_OFF: return "off";
    case LOG_ERROR: return "error";
    case LOG_WARN: return "warn";
    case LOG_INFO: return "info";
    case LOG_DEBUG: return "debug";
    case LOG_TRACE: return "trace";
    default: return "unknown";
  }
}

// Never print a secret in full. Used for the API key only; passwords and auth
// tokens are never printed at all.
void maskSecret(const char *secret, char *out, size_t outSize) {
  const size_t length = strlen(secret);
  if (length <= 8) {
    snprintf(out, outSize, "****");
    return;
  }
  snprintf(out, outSize, "%.4s%s%s", secret, "...", secret + length - 3);
}

const char *resetReasonName() {
  switch (esp_reset_reason()) {
    case ESP_RST_POWERON: return "ESP_RST_POWERON";
    case ESP_RST_EXT: return "ESP_RST_EXT";
    case ESP_RST_SW: return "ESP_RST_SW (ESP.restart)";
    case ESP_RST_PANIC: return "ESP_RST_PANIC (crash)";
    case ESP_RST_INT_WDT: return "ESP_RST_INT_WDT";
    case ESP_RST_TASK_WDT: return "ESP_RST_TASK_WDT";
    case ESP_RST_WDT: return "ESP_RST_WDT";
    case ESP_RST_BROWNOUT: return "ESP_RST_BROWNOUT (power supply)";
    case ESP_RST_DEEPSLEEP: return "ESP_RST_DEEPSLEEP";
    default: return "ESP_RST_UNKNOWN";
  }
}

// ---------------------------------------------------------------------------
// J. Time and path utilities
// ---------------------------------------------------------------------------
String deviceBasePath() { return basePath; }

bool timeSynced() { return time(nullptr) > MIN_VALID_EPOCH_SECONDS; }

uint64_t epochMillis() {
  const time_t now = time(nullptr);
  return now > MIN_VALID_EPOCH_SECONDS ? static_cast<uint64_t>(now) * 1000ULL : 0ULL;
}

void formatUtc(uint64_t timestamp, const char *pattern, char *out, size_t outSize) {
  const time_t seconds = static_cast<time_t>(timestamp / 1000ULL);
  struct tm utcTime;
  gmtime_r(&seconds, &utcTime);
  strftime(out, outSize, pattern, &utcTime);
}

String utcMonthKey(uint64_t timestamp) {
  char month[8];
  formatUtc(timestamp, "%Y-%m", month, sizeof(month));
  return String(month);
}

String utcReadingKey(uint64_t timestamp) {
  char key[24];
  formatUtc(timestamp, "%Y-%m-%d_%H-%M-%S", key, sizeof(key));
  return String(key);
}

// Truncating the history key to the hour makes the write idempotent: N reboots
// inside one hour target one node instead of appending N samples.
void utcHourSlotKey(uint64_t timestamp, char *out, size_t outSize) {
  formatUtc(timestamp, "%Y-%m-%d_%H-00-00", out, outSize);
}

// Milliseconds until a timer next fires. Saturates at 0 instead of wrapping,
// which a plain interval - (millis() - last) would do before the first run.
unsigned long msUntil(unsigned long last, unsigned long interval) {
  const unsigned long elapsed = millis() - last;
  return elapsed >= interval ? 0UL : interval - elapsed;
}

void formatUptime(unsigned long ms, char *out, size_t outSize) {
  const unsigned long totalSeconds = ms / 1000UL;
  const unsigned long days = totalSeconds / 86400UL;
  const unsigned long hours = (totalSeconds % 86400UL) / 3600UL;
  const unsigned long minutes = (totalSeconds % 3600UL) / 60UL;
  const unsigned long seconds = totalSeconds % 60UL;
  if (days > 0) {
    snprintf(out, outSize, "%lud%luh%lum", days, hours, minutes);
  } else if (hours > 0) {
    snprintf(out, outSize, "%luh%lum%lus", hours, minutes, seconds);
  } else {
    snprintf(out, outSize, "%lum%lus", minutes, seconds);
  }
}

// ---------------------------------------------------------------------------
// K. Sensor health
// ---------------------------------------------------------------------------
void markSensorGood(SensorId id) {
  SensorChannel &channel = channels[id];
  channel.detected = true;
  channel.hasValue = true;
  channel.lastGoodMs = millis();
  channel.consecutiveFails = 0;
  channel.okCount++;
  channel.lastError = "";
}

void markSensorFail(SensorId id, const char *reason) {
  SensorChannel &channel = channels[id];
  channel.failCount++;
  if (channel.consecutiveFails < 255) channel.consecutiveFails++;
  channel.lastError = reason;
}

bool isTrusted(SensorId id) {
  const SensorChannel &channel = channels[id];
  if (!channel.expected || !channel.hasValue) return false;
  return (millis() - channel.lastGoodMs) <= SENSOR_STALE_AFTER_MS;
}

const char *healthLabel(SensorId id) {
  const SensorChannel &channel = channels[id];
  if (!channel.expected) return "off";
  if (!channel.hasValue) {
    return (channel.detected && channel.consecutiveFails >= 5) ? "failed" : "never_read";
  }
  if ((millis() - channel.lastGoodMs) > SENSOR_STALE_AFTER_MS) return "stale";
  return channel.consecutiveFails > 0 ? "degraded" : "ok";
}

int findSensorByName(const char *name) {
  if (name == nullptr || *name == '\0') return -1;
  for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
    if (strcasecmp(name, channels[index].name) == 0) return index;
  }
  // Convenience aliases so the console accepts what people actually type.
  if (strcasecmp(name, "dht") == 0 || strcasecmp(name, "temp") == 0) return SENSOR_DHT;
  if (strcasecmp(name, "moist") == 0) return SENSOR_MOISTURE;
  if (strcasecmp(name, "lux") == 0 || strcasecmp(name, "light") == 0) return SENSOR_LIGHT;
  return -1;
}

// Appends the human names of every field bit set in `mask` to `out`.
void describeFields(uint16_t mask, char *out, size_t outSize) {
  struct FieldName {
    uint16_t bit;
    const char *name;
  };
  static const FieldName NAMES[] = {
      {FIELD_TEMPERATURE, "temperature"}, {FIELD_HUMIDITY, "humidity"},
      {FIELD_MOISTURE, "soilMoisture"},   {FIELD_PH, "soilPh"},
      {FIELD_NITROGEN, "nitrogen"},       {FIELD_PHOSPHORUS, "phosphorus"},
      {FIELD_POTASSIUM, "potassium"},     {FIELD_LIGHT, "lightIntensity"},
      {FIELD_TIMESTAMP, "timestamp"}};

  out[0] = '\0';
  for (const FieldName &entry : NAMES) {
    if ((mask & entry.bit) == 0) continue;
    const size_t used = strlen(out);
    if (outSize <= used + 2) break;  // no room left; never let the size math wrap
    if (used > 0) strncat(out, " ", outSize - used - 1);
    strncat(out, entry.name, outSize - strlen(out) - 1);
  }
  if (out[0] == '\0') snprintf(out, outSize, "none");
}

// ---------------------------------------------------------------------------
// L. Sensor drivers
// ---------------------------------------------------------------------------
void preTransmission() {
  digitalWrite(RS485_DE_RE, HIGH);
  delayMicroseconds(50);
}

void postTransmission() {
  delayMicroseconds(50);
  digitalWrite(RS485_DE_RE, LOW);
}

AnalogStats sampleAnalog(uint8_t pin, uint8_t samples, uint8_t settleMs) {
  if (samples == 0) samples = 1;
  uint32_t total = 0;
  int minimum = 4095;
  int maximum = 0;
  int values[32];
  const uint8_t kept = samples > 32 ? 32 : samples;

  for (uint8_t index = 0; index < samples; index++) {
    const int raw = analogRead(pin);
    total += raw;
    if (raw < minimum) minimum = raw;
    if (raw > maximum) maximum = raw;
    if (index < kept) values[index] = raw;
    if (settleMs > 0) delay(settleMs);
  }

  AnalogStats stats;
  stats.mean = static_cast<int>(total / samples);
  stats.minimum = minimum;
  stats.maximum = maximum;

  float variance = 0.0f;
  for (uint8_t index = 0; index < kept; index++) {
    const float delta = static_cast<float>(values[index] - stats.mean);
    variance += delta * delta;
  }
  stats.stdDev = kept > 1 ? sqrtf(variance / kept) : 0.0f;
  return stats;
}

// analogRead() always returns something, so a rail-pinned or wildly noisy pin
// is the only signal that an analog probe is not really there. A failure here
// marks that individual READ untrusted; it never flips the channel's `expected`
// flag, so the channel keeps being polled and recovers on its own once the pin
// reads sanely again. Whether a channel is wired at all is answered only by
// CROPCAST_SENSOR_*_ENABLED or the `sensors` console command.
bool analogLooksConnected(const AnalogStats &stats, const char **reason) {
  if (stats.mean <= ANALOG_FLOOR_RAW) {
    *reason = "raw pinned near 0 - pin floating or shorted to GND?";
    return false;
  }
  if (stats.mean >= ANALOG_CEILING_RAW) {
    *reason = "raw pinned near 4095 - pin floating or shorted to 3.3 V?";
    return false;
  }
  if (stats.stdDev >= ANALOG_NOISE_SD) {
    *reason = "reading is very noisy - check wiring and ground";
    return false;
  }
  *reason = "";
  return true;
}

float moisturePercent(int raw) {
  const float percent =
      (MOISTURE_DRY_RAW - raw) * 100.0f / static_cast<float>(MOISTURE_DRY_RAW - MOISTURE_WET_RAW);
  return constrain(percent, 0.0f, 100.0f);
}

// Single place where an ADC count becomes a voltage. Changing this to
// analogReadMilliVolts() would invalidate any previously derived PH_SLOPE and
// PH_OFFSET, so keep it in one function.
float phVolts(int raw) { return raw * 3.3f / 4095.0f; }

float phFromVolts(float volts) { return constrain(PH_SLOPE * volts + PH_OFFSET, 0.0f, 14.0f); }

const char *modbusErrorName(uint8_t code) {
  switch (code) {
    case ModbusMaster::ku8MBSuccess: return "success";
    case ModbusMaster::ku8MBIllegalFunction: return "illegal function";
    case ModbusMaster::ku8MBIllegalDataAddress: return "illegal data address";
    case ModbusMaster::ku8MBIllegalDataValue: return "illegal data value";
    case ModbusMaster::ku8MBSlaveDeviceFailure: return "slave device failure";
    case ModbusMaster::ku8MBInvalidSlaveID: return "invalid slave id";
    case ModbusMaster::ku8MBInvalidFunction: return "invalid function";
    case ModbusMaster::ku8MBResponseTimedOut: return "response timed out";
    case ModbusMaster::ku8MBInvalidCRC: return "invalid CRC";
    default: return "unknown";
  }
}

// DHT11 needs at least 2 s between reads, so a faster caller gets the cache.
bool readDht(float &temperature, float &humidity) {
  const unsigned long now = millis();
  if (lastDhtReadMs != 0 && (now - lastDhtReadMs) < DHT_MIN_INTERVAL_MS) {
    if (isnan(cachedTemperature) || isnan(cachedHumidity)) return false;
    temperature = cachedTemperature;
    humidity = cachedHumidity;
    return true;
  }

  lastDhtReadMs = now;
  const float readTemperature = dht.readTemperature();
  const float readHumidity = dht.readHumidity();
  if (isnan(readTemperature) || isnan(readHumidity)) return false;

  cachedTemperature = readTemperature;
  cachedHumidity = readHumidity;
  temperature = readTemperature;
  humidity = readHumidity;
  return true;
}

bool updateNpkValues() {
  npk.clearResponseBuffer();
  lastModbusResult = npk.readHoldingRegisters(NPK_BASE_REGISTER, 3);
  if (lastModbusResult != npk.ku8MBSuccess) {
    markSensorFail(SENSOR_NPK, modbusErrorName(lastModbusResult));
    return false;
  }

  lastNitrogen = npk.getResponseBuffer(0);
  lastPhosphorus = npk.getResponseBuffer(1);
  lastPotassium = npk.getResponseBuffer(2);
  markSensorGood(SENSOR_NPK);
  return true;
}

void scanI2CBus() {
  Serial.println("I2C scan (SDA=21 SCL=22):");
  uint8_t found = 0;
  for (uint8_t address = 1; address < 127; address++) {
    Wire.beginTransmission(address);
    if (Wire.endTransmission() != 0) continue;
    found++;
    const char *note = "";
    if (address == 0x23) note = "  <- BH1750 (ADDR low)";
    if (address == 0x5C) note = "  <- BH1750 (ADDR high)";
    Serial.printf("  0x%02X%s\n", address, note);
  }
  if (found == 0) Serial.println("  no devices responded");
}

// ---------------------------------------------------------------------------
// M. Reading assembly
// ---------------------------------------------------------------------------
// Every field without a trust bit becomes 0, the sentinel this codebase already
// uses for "no data" (SensorReading() defaults to zeros on the app side).
// This is not cosmetic: an out-of-range value left in place -- a DHT returning
// 200 C, say -- would make the server reject the ENTIRE write under
// database.rules.json, taking the six healthy fields down with it.
void zeroUntrustedFields(SensorData &reading) {
  if ((reading.trusted & FIELD_TEMPERATURE) == 0) reading.temperature = 0.0f;
  if ((reading.trusted & FIELD_HUMIDITY) == 0) reading.humidity = 0.0f;
  if ((reading.trusted & FIELD_MOISTURE) == 0) reading.soilMoisture = 0.0f;
  if ((reading.trusted & FIELD_PH) == 0) reading.soilPh = 0.0f;
  if ((reading.trusted & FIELD_NITROGEN) == 0) reading.nitrogen = 0;
  if ((reading.trusted & FIELD_PHOSPHORUS) == 0) reading.phosphorus = 0;
  if ((reading.trusted & FIELD_POTASSIUM) == 0) reading.potassium = 0;
  if ((reading.trusted & FIELD_LIGHT) == 0) reading.lightIntensity = 0.0f;
  if ((reading.trusted & FIELD_TIMESTAMP) == 0) reading.timestamp = 0;
}

// Cannot fail. Every channel is attempted, health is updated, and a trust bit
// is set only for a value that is both fresh and inside the rules' range.
// Bug 2: because this never returns early, a bad DHT no longer prevents the
// status publish that keeps the device looking online.
void buildReading(SensorData &reading) {
  reading = SensorData();

  if (channels[SENSOR_DHT].expected) {
    float temperature = 0.0f;
    float humidity = 0.0f;
    if (readDht(temperature, humidity)) {
      markSensorGood(SENSOR_DHT);
      reading.temperature = temperature;
      reading.humidity = humidity;
      if (isfinite(temperature) && temperature >= LIMIT_TEMPERATURE_MIN &&
          temperature <= LIMIT_TEMPERATURE_MAX) {
        reading.trusted |= FIELD_TEMPERATURE;
      } else {
        LOG_W(TAG_DHT, "temperature %.1f C outside %.0f..%.0f, field withheld", temperature,
              LIMIT_TEMPERATURE_MIN, LIMIT_TEMPERATURE_MAX);
      }
      if (isfinite(humidity) && humidity >= LIMIT_HUMIDITY_MIN && humidity <= LIMIT_HUMIDITY_MAX) {
        reading.trusted |= FIELD_HUMIDITY;
      } else {
        LOG_W(TAG_DHT, "humidity %.1f %% outside 0..100, field withheld", humidity);
      }
    } else {
      markSensorFail(SENSOR_DHT, "read returned NaN");
      LOG_W(TAG_DHT, "read failed (%u consecutive)", channels[SENSOR_DHT].consecutiveFails);
    }
  }

  if (channels[SENSOR_MOISTURE].expected) {
    const AnalogStats stats = sampleAnalog(MOISTURE_PIN, 12, 8);
    const char *reason = "";
    if (analogLooksConnected(stats, &reason)) {
      markSensorGood(SENSOR_MOISTURE);
    } else {
      markSensorFail(SENSOR_MOISTURE, reason);
      LOG_W(TAG_MOIST, "raw %d: %s", stats.mean, reason);
    }
    reading.soilMoisture = moisturePercent(stats.mean);
    if (isTrusted(SENSOR_MOISTURE) && !calibrationMode && isfinite(reading.soilMoisture) &&
        reading.soilMoisture >= LIMIT_MOISTURE_MIN && reading.soilMoisture <= LIMIT_MOISTURE_MAX) {
      reading.trusted |= FIELD_MOISTURE;
    }
    LOG_T(TAG_MOIST, "raw %d (sd %.1f) -> %.1f %%", stats.mean, stats.stdDev, reading.soilMoisture);
  }

  if (channels[SENSOR_PH].expected) {
    const AnalogStats stats = sampleAnalog(PH_PIN, 12, 8);
    const char *reason = "";
    if (analogLooksConnected(stats, &reason)) {
      markSensorGood(SENSOR_PH);
    } else {
      markSensorFail(SENSOR_PH, reason);
      LOG_W(TAG_PH, "raw %d: %s", stats.mean, reason);
    }
    reading.soilPh = phFromVolts(phVolts(stats.mean));
    if (isTrusted(SENSOR_PH) && !calibrationMode && isfinite(reading.soilPh) &&
        reading.soilPh >= LIMIT_PH_MIN && reading.soilPh <= LIMIT_PH_MAX) {
      reading.trusted |= FIELD_PH;
    }
    LOG_T(TAG_PH, "raw %d (sd %.1f) -> %.4f V -> pH %.2f", stats.mean, stats.stdDev,
          phVolts(stats.mean), reading.soilPh);
  }

  if (channels[SENSOR_NPK].expected) {
    if (!updateNpkValues()) {
      LOG_W(TAG_NPK, "modbus 0x%02X (%s), %u consecutive%s", lastModbusResult,
            modbusErrorName(lastModbusResult), channels[SENSOR_NPK].consecutiveFails,
            channels[SENSOR_NPK].hasValue ? ", using cached value" : ", no value yet");
    }
    // Bug 4: these stay 0 and untrusted until a Modbus read has actually
    // succeeded, so a missing probe cannot feed zeros into the crop score.
    if (isTrusted(SENSOR_NPK)) {
      reading.nitrogen = lastNitrogen;
      reading.phosphorus = lastPhosphorus;
      reading.potassium = lastPotassium;
      reading.trusted |= FIELD_NITROGEN | FIELD_PHOSPHORUS | FIELD_POTASSIUM;
    }
  }

  if (channels[SENSOR_LIGHT].expected) {
    const float lux = lightSensorReady ? lightMeter.readLightLevel() : NAN;
    if (isfinite(lux) && lux >= 0.0f) {
      markSensorGood(SENSOR_LIGHT);
      reading.lightIntensity = lux;
      reading.trusted |= FIELD_LIGHT;
    } else {
      markSensorFail(SENSOR_LIGHT, lightSensorReady ? "read failed" : "not detected on I2C");
    }
  }

  reading.timestamp = epochMillis();
  if (reading.timestamp > 0) reading.trusted |= FIELD_TIMESTAMP;

  zeroUntrustedFields(reading);
}

// ---------------------------------------------------------------------------
// N. Firebase publishing
// ---------------------------------------------------------------------------
void logFirebaseError(const char *tag, const char *what) {
  LOG_E(tag, "%s failed: %s (http %d)", what, firebaseData.errorReason().c_str(),
        firebaseData.httpCode());
}

// Exactly the nine contract fields. Adding a tenth would make the Android app
// log an unknown-property warning on every snapshot; diagnostics belong under
// /status instead.
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
  // Bug 1: the app requires timestamp > 0, so publishing before NTP syncs makes
  // the dashboard render zeros. Withhold instead, matching the history path.
  if ((reading.trusted & FIELD_TIMESTAMP) == 0) {
    LOG_W(TAG_PUB, "clock not synced, current reading withheld");
    return false;
  }

  const uint16_t missing = SCORED_FIELDS & ~reading.trusted;
  if (missing != 0) {
    char names[160];
    describeFields(missing, names, sizeof(names));
    if (!CROPCAST_PUBLISH_CURRENT_WHEN_INCOMPLETE) {
      LOG_W(TAG_PUB, "current withheld, untrusted: %s", names);
      return false;
    }
    LOG_W(TAG_PUB, "current published with 0 placeholders for: %s", names);
  }

  FirebaseJson json;
  fillReadingJson(reading, json);
  if (!Firebase.RTDB.setJSON(&firebaseData, deviceBasePath() + "/readings/current", &json)) {
    logFirebaseError(TAG_PUB, "current reading upload");
    return false;
  }

  lastPublished = reading;
  const uint8_t trustedCount = __builtin_popcount(reading.trusted & SCORED_FIELDS);
  LOG_I(TAG_PUB, "current uploaded (%u/7 scored fields trusted)", trustedCount);
  return true;
}

// Strict by design: readings/monthly is the only path feeding
// MonthlySensorAggregator, and the app takes an unweighted mean over it, so one
// placeholder sample measurably reorders the crop ranking. Skipping instead
// leaves the month below MIN_MONTHLY_SAMPLES and the app honestly reports that
// there is not enough data.
bool publishMonthlyHistory(const SensorData &reading) {
  if (!historyEnabled) {
    historyState = "disabled";
    return false;
  }
  if ((reading.trusted & HISTORY_REQUIRED_FIELDS) != HISTORY_REQUIRED_FIELDS) {
    historyState = "withheld";
    const unsigned long now = millis();
    if (lastHistorySkipLog == 0 || now - lastHistorySkipLog >= HISTORY_LOG_INTERVAL_MS) {
      lastHistorySkipLog = now;
      char names[160];
      describeFields(HISTORY_REQUIRED_FIELDS & ~reading.trusted, names, sizeof(names));
      LOG_W(TAG_HIST, "sample withheld, untrusted: %s", names);
    }
    return false;
  }

  char slot[24];
  utcHourSlotKey(reading.timestamp, slot, sizeof(slot));
  historyState = "publishing";
  if (strcmp(slot, lastHistorySlot) == 0) return false;

  const String path =
      deviceBasePath() + "/readings/monthly/" + utcMonthKey(reading.timestamp) + "/" + slot;

  // Bug 3, second defence: after a reboot, do not even spend a write on an hour
  // slot that already exists.
  if (!historySlotProbed) {
    historySlotProbed = true;
    if (Firebase.RTDB.pathExisted(&firebaseData, path)) {
      strncpy(lastHistorySlot, slot, sizeof(lastHistorySlot) - 1);
      lastHistorySlot[sizeof(lastHistorySlot) - 1] = '\0';
      LOG_I(TAG_HIST, "slot %s already stored, skipping after reboot", slot);
      return false;
    }
  }

  FirebaseJson json;
  fillReadingJson(reading, json);
  if (!Firebase.RTDB.setJSON(&firebaseData, path, &json)) {
    logFirebaseError(TAG_HIST, "monthly history upload");
    return false;
  }

  strncpy(lastHistorySlot, slot, sizeof(lastHistorySlot) - 1);
  lastHistorySlot[sizeof(lastHistorySlot) - 1] = '\0';
  LOG_I(TAG_HIST, "sample saved to %s", path.c_str());
  return true;
}

// /status has no validation rule and the app reads it with explicit child()
// calls, so extra diagnostic keys here are safe.
bool publishStatus(bool online) {
  FirebaseJson status;
  status.set("online", online);
  status.set("lastSeen", static_cast<double>(epochMillis()));
  status.set("firmware", FIRMWARE_VERSION);

  FirebaseJsonArray sensors;
  sensors.add("ESP32");
  FirebaseJson health;
  bool degraded = false;
  for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
    const char *label = healthLabel(static_cast<SensorId>(index));
    health.set(channels[index].name, label);
    if (isTrusted(static_cast<SensorId>(index))) {
      sensors.add(channels[index].name);
    } else if (channels[index].expected) {
      degraded = true;
    }
  }

  status.set("sensors", sensors);
  status.set("sensorHealth", health);
  status.set("degraded", degraded);
  status.set("historyState", historyState);
  status.set("wifiRssi", WiFi.RSSI());
  status.set("ipAddress", WiFi.localIP().toString());
  status.set("uptimeSeconds", millis() / 1000UL);

  if (!Firebase.RTDB.setJSON(&firebaseData, deviceBasePath() + "/status", &status)) {
    logFirebaseError(TAG_PUB, "status upload");
    return false;
  }
  return true;
}

void publishSensorCycle() {
  SensorData reading;
  buildReading(reading);

  publishCurrentReading(reading);
  publishMonthlyHistory(reading);
  // Always last and always unconditional: status must stay fresh even when
  // every sensor has failed, or the app marks the device offline.
  publishStatus(true);

  LOG_D(TAG_PUB, "%.1f C, %.0f %%RH, %.0f %% moisture, pH %.2f, N/P/K %u/%u/%u, %.0f lux",
        reading.temperature, reading.humidity, reading.soilMoisture, reading.soilPh,
        reading.nitrogen, reading.phosphorus, reading.potassium, reading.lightIntensity);
}

void refreshRemoteSettings() {
  if (historyLocalOverride) return;  // console `history` command wins until reset
  if (Firebase.RTDB.getBool(&firebaseData, deviceBasePath() + "/settings/cloudHistoryEnabled")) {
    const bool enabled = firebaseData.boolData();
    if (enabled != historyEnabled) LOG_I(TAG_FB, "cloudHistoryEnabled -> %s", enabled ? "true" : "false");
    historyEnabled = enabled;
  } else {
    LOG_W(TAG_FB, "settings read failed, keeping cloudHistoryEnabled=%s: %s",
          historyEnabled ? "true" : "false", firebaseData.errorReason().c_str());
  }
}

// Bug 5: mark the device offline before any deliberate reboot, matching what
// scripts/simulate_firebase_sensor.py does when it exits.
void gracefulRestart(const char *reason) {
  LOG_W(TAG_CMD, "restarting: %s", reason);
  if (WiFi.status() == WL_CONNECTED && Firebase.ready()) publishStatus(false);
  Serial.flush();
  delay(300);
  ESP.restart();
}

void checkRestartCommand() {
  const String commandPath = deviceBasePath() + "/commands/restart";
  if (!Firebase.RTDB.getJSON(&firebaseData, commandPath)) return;

  FirebaseJsonData statusValue;
  firebaseData.jsonObject().get(statusValue, "status");
  if (!statusValue.success || statusValue.to<String>() != "pending") return;

  // Acknowledge first so the app sees it even if the offline write then fails.
  Firebase.RTDB.setString(&firebaseData, commandPath + "/status", "restarting");
  Firebase.RTDB.setDouble(&firebaseData, commandPath + "/acknowledgedAt",
                          static_cast<double>(epochMillis()));
  LOG_I(TAG_CMD, "restart command acknowledged");
  gracefulRestart("restart requested from the app");
}

// ---------------------------------------------------------------------------
// O. Serial console
// ---------------------------------------------------------------------------
void cmdHelp(const char *args);
void cmdStatus(const char *args);
void cmdHealth(const char *args);
void cmdRead(const char *args);
void cmdPublish(const char *args);
void cmdScan(const char *args);
void cmdNpk(const char *args);
void cmdTime(const char *args);
void cmdWifi(const char *args);
void cmdFirebase(const char *args);
void cmdSensors(const char *args);
void cmdLog(const char *args);
void cmdHistory(const char *args);
void cmdCal(const char *args);
void cmdReboot(const char *args);

const ConsoleCommand CONSOLE_COMMANDS[] = {
    {"help", cmdHelp, "help", "list commands"},
    {"status", cmdStatus, "status", "uptime, network, clock, timers, last publish"},
    {"health", cmdHealth, "health", "per-sensor health table"},
    {"read", cmdRead, "read", "read every sensor now, raw and converted, no upload"},
    {"publish", cmdPublish, "publish [current|history|status|all]", "force a publish now"},
    {"scan", cmdScan, "scan", "I2C bus scan"},
    {"npk", cmdNpk, "npk [reg] [count]", "one Modbus read, hex dump and decoded error"},
    {"time", cmdTime, "time [sync]", "clock state; 'sync' re-arms NTP"},
    {"wifi", cmdWifi, "wifi [reconnect]", "network state"},
    {"fb", cmdFirebase, "fb", "Firebase ready state, uid, base path, last error"},
    {"sensors", cmdSensors, "sensors [<name> on|off]", "list or toggle expected channels"},
    {"log", cmdLog, "log [level]", "show or set verbosity (off..trace)"},
    {"history", cmdHistory, "history [on|off]", "local override of cloudHistoryEnabled"},
    {"cal", cmdCal, "cal", "enter probe calibration mode"},
    {"reboot", cmdReboot, "reboot", "mark offline, then restart"}};
constexpr size_t CONSOLE_COMMAND_COUNT = sizeof(CONSOLE_COMMANDS) / sizeof(CONSOLE_COMMANDS[0]);

constexpr size_t CONSOLE_LINE_MAX = 80;
char consoleLine[CONSOLE_LINE_MAX];
size_t consoleLength = 0;

void handleCalibrationCommand(const char *line);

void dispatchConsole(char *line) {
  while (*line == ' ') line++;
  if (*line == '\0') return;

  if (calibrationMode) {
    handleCalibrationCommand(line);
    return;
  }

  char *args = strchr(line, ' ');
  if (args != nullptr) {
    *args = '\0';
    args++;
    while (*args == ' ') args++;
  } else {
    args = line + strlen(line);  // empty string
  }

  if (strcmp(line, "?") == 0) {
    cmdHelp(args);
    return;
  }
  for (size_t index = 0; index < CONSOLE_COMMAND_COUNT; index++) {
    if (strcasecmp(line, CONSOLE_COMMANDS[index].name) == 0) {
      CONSOLE_COMMANDS[index].handler(args);
      return;
    }
  }
  Serial.printf("unknown command '%s' - type 'help'\n", line);
}

// Non-blocking. Never uses Serial.readStringUntil (blocks) or String
// accumulation (fragments the heap over weeks of uptime).
void serviceConsole() {
  if (!CROPCAST_CONSOLE_ENABLED) return;
  uint8_t budget = 64;
  while (Serial.available() > 0 && budget-- > 0) {
    const int character = Serial.read();
    if (character == '\r') continue;
    if (character == '\n') {
      consoleLine[consoleLength] = '\0';
      if (consoleLength > 0) dispatchConsole(consoleLine);
      consoleLength = 0;
      continue;
    }
    if (character == 0x08 || character == 0x7F) {
      if (consoleLength > 0) consoleLength--;
      continue;
    }
    if (consoleLength + 1 < CONSOLE_LINE_MAX) {
      consoleLine[consoleLength++] = static_cast<char>(character);
    } else {
      consoleLength = 0;
      LOG_W(TAG_CLI, "input line too long, discarded");
    }
  }
}

void cmdHelp(const char *args) {
  (void)args;
  Serial.println("CropCast serial console. Set the Serial Monitor line ending to Newline.");
  for (size_t index = 0; index < CONSOLE_COMMAND_COUNT; index++) {
    Serial.printf("  %-30s %s\n", CONSOLE_COMMANDS[index].usage, CONSOLE_COMMANDS[index].help);
  }
}

void printHealthTable() {
  Serial.printf("  %-9s %-9s %-9s %-8s %s\n", "channel", "expected", "state", "ok/fail", "detail");
  for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
    const SensorChannel &channel = channels[index];
    char age[24] = "-";
    if (channel.hasValue) {
      snprintf(age, sizeof(age), "%lus ago", (millis() - channel.lastGoodMs) / 1000UL);
    }
    Serial.printf("  %-9s %-9s %-9s %lu/%-6lu %s%s%s\n", channel.name,
                  channel.expected ? "yes" : "no", healthLabel(static_cast<SensorId>(index)),
                  static_cast<unsigned long>(channel.okCount),
                  static_cast<unsigned long>(channel.failCount), age,
                  (channel.lastError != nullptr && channel.lastError[0] != '\0') ? "  " : "",
                  channel.lastError != nullptr ? channel.lastError : "");
  }
}

void cmdHealth(const char *args) {
  (void)args;
  printHealthTable();
}

void cmdStatus(const char *args) {
  (void)args;
  char uptime[24];
  formatUptime(millis(), uptime, sizeof(uptime));
  Serial.printf("firmware   : %s, device %s\n", FIRMWARE_VERSION, DEVICE_ID);
  Serial.printf("uptime     : %s\n", uptime);
  Serial.printf("heap       : %u free, %u largest block\n", ESP.getFreeHeap(),
                ESP.getMaxAllocHeap());
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("wifi       : %s, %d dBm, %s\n", WiFi.SSID().c_str(), WiFi.RSSI(),
                  WiFi.localIP().toString().c_str());
  } else {
    Serial.println("wifi       : disconnected");
  }
  if (timeSynced()) {
    char now[32];
    formatUtc(epochMillis(), "%Y-%m-%dT%H:%M:%SZ", now, sizeof(now));
    Serial.printf("clock      : synced, %s\n", now);
  } else {
    Serial.println("clock      : NOT synced - readings are withheld until NTP succeeds");
  }
  Serial.printf("firebase   : %s\n", Firebase.ready() ? "ready" : "not ready");
  Serial.printf("history    : %s (%s)%s\n", historyEnabled ? "enabled" : "disabled", historyState,
                historyLocalOverride ? " [local override]" : "");
  Serial.printf("last slot  : %s\n", lastHistorySlot[0] != '\0' ? lastHistorySlot : "(none yet)");
  Serial.printf("log level  : %s\n", logLevelName(runtimeLogLevel));
  Serial.printf("next publish in %lu ms, settings %lu ms, command %lu ms\n",
                msUntil(lastLivePublish, LIVE_PUBLISH_INTERVAL_MS),
                msUntil(lastSettingsCheck, SETTINGS_INTERVAL_MS),
                msUntil(lastCommandCheck, COMMAND_INTERVAL_MS));
  if (lastPublished.timestamp > 0) {
    Serial.printf("last sent  : %.1f C, %.0f %%RH, %.0f %% moisture, pH %.2f, N/P/K %u/%u/%u\n",
                  lastPublished.temperature, lastPublished.humidity, lastPublished.soilMoisture,
                  lastPublished.soilPh, lastPublished.nitrogen, lastPublished.phosphorus,
                  lastPublished.potassium);
  }
  printHealthTable();
}

void cmdRead(const char *args) {
  (void)args;
  SensorData reading;
  buildReading(reading);

  char trustedNames[160];
  char missingNames[160];
  describeFields(reading.trusted, trustedNames, sizeof(trustedNames));
  describeFields(HISTORY_REQUIRED_FIELDS & ~reading.trusted, missingNames, sizeof(missingNames));

  Serial.printf("temperature   : %.1f C\n", reading.temperature);
  Serial.printf("humidity      : %.1f %%\n", reading.humidity);
  if (channels[SENSOR_MOISTURE].expected) {
    const AnalogStats stats = sampleAnalog(MOISTURE_PIN, 12, 4);
    Serial.printf("soilMoisture  : %.1f %%  (raw %d, sd %.1f)\n", moisturePercent(stats.mean),
                  stats.mean, stats.stdDev);
  }
  if (channels[SENSOR_PH].expected) {
    const AnalogStats stats = sampleAnalog(PH_PIN, 12, 4);
    Serial.printf("soilPh        : %.2f  (raw %d, %.4f V, sd %.1f)\n",
                  phFromVolts(phVolts(stats.mean)), stats.mean, phVolts(stats.mean), stats.stdDev);
  }
  Serial.printf("N/P/K         : %u / %u / %u  (modbus 0x%02X %s)\n", reading.nitrogen,
                reading.phosphorus, reading.potassium, lastModbusResult,
                modbusErrorName(lastModbusResult));
  Serial.printf("lightIntensity: %.0f lux\n", reading.lightIntensity);
  Serial.printf("timestamp     : %llu\n", (unsigned long long)reading.timestamp);
  Serial.printf("trusted       : %s\n", trustedNames);
  Serial.printf("history needs : %s\n", missingNames);
  Serial.println("(nothing uploaded)");
}

void cmdPublish(const char *args) {
  if (!Firebase.ready()) {
    Serial.println("firebase not ready");
    return;
  }
  const bool all = (*args == '\0') || strcasecmp(args, "all") == 0;
  SensorData reading;
  buildReading(reading);

  if (all || strcasecmp(args, "current") == 0) {
    Serial.printf("current : %s\n", publishCurrentReading(reading) ? "uploaded" : "withheld/failed");
  }
  if (all || strcasecmp(args, "history") == 0) {
    Serial.printf("history : %s\n", publishMonthlyHistory(reading) ? "uploaded" : "withheld/failed");
  }
  if (all || strcasecmp(args, "status") == 0) {
    Serial.printf("status  : %s\n", publishStatus(true) ? "uploaded" : "failed");
  }
}

void cmdScan(const char *args) {
  (void)args;
  scanI2CBus();
}

void cmdNpk(const char *args) {
  uint16_t reg = NPK_BASE_REGISTER;
  uint8_t count = 3;
  if (*args != '\0') {
    char *end = nullptr;
    const long parsed = strtol(args, &end, 0);
    if (end != args) reg = static_cast<uint16_t>(parsed);
    if (end != nullptr && *end != '\0') {
      const long parsedCount = strtol(end, nullptr, 0);
      if (parsedCount > 0 && parsedCount <= 8) count = static_cast<uint8_t>(parsedCount);
    }
  }

  Serial.printf("reading %u holding registers from 0x%04X, slave %u\n", count, reg, NPK_SLAVE_ID);
  npk.clearResponseBuffer();
  const uint8_t result = npk.readHoldingRegisters(reg, count);
  Serial.printf("result: 0x%02X (%s)\n", result, modbusErrorName(result));
  if (result != npk.ku8MBSuccess) {
    Serial.println("check: RO->GPIO16, DI->GPIO17, DE+RE->GPIO5, common ground, probe power");
    return;
  }
  for (uint8_t index = 0; index < count; index++) {
    const uint16_t value = npk.getResponseBuffer(index);
    Serial.printf("  [0x%04X] 0x%04X  %u\n", reg + index, value, value);
  }
}

void cmdTime(const char *args) {
  if (strcasecmp(args, "sync") == 0) {
    configTime(0, 0, "pool.ntp.org", "time.google.com");
    Serial.println("NTP re-armed");
  }
  if (timeSynced()) {
    char now[32];
    formatUtc(epochMillis(), "%Y-%m-%dT%H:%M:%SZ", now, sizeof(now));
    char slot[24];
    utcHourSlotKey(epochMillis(), slot, sizeof(slot));
    Serial.printf("epoch ms  : %llu\n", (unsigned long long)epochMillis());
    Serial.printf("utc       : %s\n", now);
    Serial.printf("hour slot : %s\n", slot);
  } else {
    Serial.println("clock not synced yet");
  }
}

void cmdWifi(const char *args) {
  if (strcasecmp(args, "reconnect") == 0) {
    WiFi.disconnect();
    lastWiFiAttempt = 0;
    Serial.println("reconnecting");
    return;
  }
  Serial.printf("status : %d (%s)\n", WiFi.status(),
                WiFi.status() == WL_CONNECTED ? "connected" : "not connected");
  if (WiFi.status() == WL_CONNECTED) {
    Serial.printf("ssid   : %s\n", WiFi.SSID().c_str());
    Serial.printf("rssi   : %d dBm\n", WiFi.RSSI());
    Serial.printf("ip     : %s\n", WiFi.localIP().toString().c_str());
    Serial.printf("bssid  : %s\n", WiFi.BSSIDstr().c_str());
  }
}

void cmdFirebase(const char *args) {
  (void)args;
  Serial.printf("ready     : %s\n", Firebase.ready() ? "yes" : "no");
  Serial.printf("uid       : %s\n", auth.token.uid.c_str());
  Serial.printf("base path : %s\n", deviceBasePath().c_str());
  Serial.printf("last error: %s (http %d)\n", firebaseData.errorReason().c_str(),
                firebaseData.httpCode());
}

void cmdSensors(const char *args) {
  if (*args == '\0') {
    for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
      Serial.printf("  %-9s %s (%s)\n", channels[index].name,
                    channels[index].expected ? "on" : "off",
                    healthLabel(static_cast<SensorId>(index)));
    }
    Serial.println("usage: sensors <name> on|off");
    return;
  }

  char name[24];
  char state[8];
  if (sscanf(args, "%23s %7s", name, state) != 2) {
    Serial.println("usage: sensors <name> on|off");
    return;
  }
  const int id = findSensorByName(name);
  if (id < 0) {
    Serial.printf("unknown sensor '%s'\n", name);
    return;
  }
  const bool on = strcasecmp(state, "on") == 0;
  if (!on && strcasecmp(state, "off") != 0) {
    Serial.println("usage: sensors <name> on|off");
    return;
  }
  channels[id].expected = on;
  if (!on) {
    channels[id].hasValue = false;
    channels[id].consecutiveFails = 0;
    channels[id].lastError = "marked not wired";
  }
  Serial.printf("%s is now %s\n", channels[id].name, on ? "expected" : "off");
}

void cmdLog(const char *args) {
  if (*args == '\0') {
    Serial.printf("log level %s (compiled ceiling %s)\n", logLevelName(runtimeLogLevel),
                  logLevelName(CROPCAST_LOG_LEVEL));
    return;
  }
  static const char *NAMES[] = {"off", "error", "warn", "info", "debug", "trace"};
  for (uint8_t level = 0; level <= LOG_TRACE; level++) {
    if (strcasecmp(args, NAMES[level]) != 0) continue;
    if (level > CROPCAST_LOG_LEVEL) {
      Serial.printf("compiled ceiling is %s; raise CROPCAST_LOG_LEVEL to go higher\n",
                    logLevelName(CROPCAST_LOG_LEVEL));
      return;
    }
    runtimeLogLevel = level;
    Serial.printf("log level %s\n", logLevelName(level));
    return;
  }
  Serial.println("usage: log [off|error|warn|info|debug|trace]");
}

void cmdHistory(const char *args) {
  if (*args == '\0') {
    Serial.printf("history %s (%s)%s\n", historyEnabled ? "on" : "off", historyState,
                  historyLocalOverride ? " [local override]" : "");
    return;
  }
  if (strcasecmp(args, "on") == 0 || strcasecmp(args, "off") == 0) {
    historyEnabled = strcasecmp(args, "on") == 0;
    historyLocalOverride = true;
    Serial.printf("history %s locally; the app setting is ignored until reboot\n",
                  historyEnabled ? "on" : "off");
    return;
  }
  Serial.println("usage: history [on|off]");
}

void cmdReboot(const char *args) {
  (void)args;
  gracefulRestart("reboot requested from the serial console");
}

// ---------------------------------------------------------------------------
// P. Calibration mode
// ---------------------------------------------------------------------------
void printCalibrationHelp() {
  Serial.println("Calibration mode. Moisture and pH are withheld from Firebase while active.");
  Serial.println("  dry           capture the moisture point in air        (0 %)");
  Serial.println("  wet           capture the moisture point in water      (100 %)");
  Serial.println("  ph <value>    capture a pH buffer, e.g. 'ph 4.00' then 'ph 6.86'");
  Serial.println("  show          solve, validate, print the secrets.h block");
  Serial.println("  verify        re-measure and report error against the captured points");
  Serial.println("  reset         discard captured points");
  Serial.println("  q             leave calibration mode");
}

void cmdCal(const char *args) {
  (void)args;
  calibrationMode = true;
  lastCalStream = 0;
  printCalibrationHelp();
}

void serviceCalibrationStream() {
  if (!calibrationMode) return;
  const unsigned long now = millis();
  if (lastCalStream != 0 && now - lastCalStream < CAL_STREAM_INTERVAL_MS) return;
  lastCalStream = now;

  const AnalogStats moisture = sampleAnalog(MOISTURE_PIN, 8, 2);
  const AnalogStats ph = sampleAnalog(PH_PIN, 8, 2);
  Serial.printf("[CAL] MOIST raw=%d min=%d max=%d sd=%.1f -> %.1f %%  |  "
                "PH raw=%d v=%.4f sd=%.1f -> pH %.2f\n",
                moisture.mean, moisture.minimum, moisture.maximum, moisture.stdDev,
                moisturePercent(moisture.mean), ph.mean, phVolts(ph.mean), ph.stdDev,
                phFromVolts(phVolts(ph.mean)));
}

void calibrationShow() {
  Serial.println("--- copy into secrets.h ---");
  char stamp[32] = "clock not synced";
  if (timeSynced()) formatUtc(epochMillis(), "%Y-%m-%dT%H:%M:%SZ", stamp, sizeof(stamp));
  Serial.printf("// calibrated %s, firmware %s, device %s\n", stamp, FIRMWARE_VERSION, DEVICE_ID);

  if (calDryCaptured && calWetCaptured) {
    const int span = calDry.mean - calWet.mean;
    if (span <= 0) {
      Serial.println("// ERROR: dry must read HIGHER than wet on a capacitive probe.");
      Serial.println("//        Recapture, or check that this is not a resistive probe.");
    } else {
      Serial.printf("#define CROPCAST_MOISTURE_DRY_RAW %d   // air, sd %.1f\n", calDry.mean,
                    calDry.stdDev);
      Serial.printf("#define CROPCAST_MOISTURE_WET_RAW %d   // water, sd %.1f, span %d\n",
                    calWet.mean, calWet.stdDev, span);
      if (span < 300) Serial.println("// WARNING: span < 300 counts; 1 % is under 3 counts, noise will dominate.");
      if (calDry.stdDev > 40.0f || calWet.stdDev > 40.0f) {
        Serial.println("// WARNING: a capture had sd > 40; the reading had not settled.");
      }
    }
  } else {
    Serial.println("// moisture: capture both 'dry' and 'wet' first");
  }

  if (calPhCount >= 2) {
    const CalPoint &a = calPhPoints[0];
    const CalPoint &b = calPhPoints[1];
    const float deltaVolts = b.volts - a.volts;
    if (fabsf(deltaVolts) < 0.10f) {
      Serial.println("// ERROR: the two buffers differ by < 0.10 V.");
      Serial.println("//        Electrode not responding, BNC loose, or buffers not settled.");
    } else {
      const float slope = (b.ph - a.ph) / deltaVolts;
      const float offset = a.ph - slope * a.volts;
      Serial.printf("#define CROPCAST_PH_SLOPE  %.4ff // from pH %.2f @ %.4f V and pH %.2f @ %.4f V\n",
                    slope, a.ph, a.volts, b.ph, b.volts);
      Serial.printf("#define CROPCAST_PH_OFFSET %.4ff\n", offset);
      Serial.printf("// residuals: %.3f pH and %.3f pH\n", slope * a.volts + offset - a.ph,
                    slope * b.volts + offset - b.ph);
      if (fabsf(slope) < 2.0f || fabsf(slope) > 10.0f) {
        Serial.printf("// WARNING: |slope| %.2f pH/V is outside the usual 2..10 range.\n",
                      fabsf(slope));
      }
    }
  } else {
    Serial.printf("// pH: %u of 2 buffer points captured\n", calPhCount);
  }
  Serial.println("---------------------------");
}

void calibrationVerify() {
  if (calPhCount < 2) {
    Serial.println("capture two pH points first");
    return;
  }
  const AnalogStats stats = sampleAnalog(PH_PIN, 24, 4);
  const float volts = phVolts(stats.mean);
  Serial.printf("now reading %.4f V -> pH %.2f with the CURRENTLY COMPILED constants\n", volts,
                phFromVolts(volts));
  Serial.println("Flash the values from 'show' before trusting this number.");
  for (uint8_t index = 0; index < calPhCount; index++) {
    Serial.printf("  captured point %u: pH %.2f @ %.4f V (sd %.1f)\n", index + 1,
                  calPhPoints[index].ph, calPhPoints[index].volts, calPhPoints[index].stdDev);
  }
}

void handleCalibrationCommand(const char *line) {
  if (strcasecmp(line, "q") == 0 || strcasecmp(line, "exit") == 0) {
    calibrationMode = false;
    Serial.println("left calibration mode; moisture and pH resume publishing");
    return;
  }
  if (strcasecmp(line, "help") == 0 || strcmp(line, "?") == 0) {
    printCalibrationHelp();
    return;
  }
  if (strcasecmp(line, "dry") == 0) {
    calDry = sampleAnalog(MOISTURE_PIN, 24, 8);
    calDryCaptured = true;
    Serial.printf("captured dry: raw %d (sd %.1f)\n", calDry.mean, calDry.stdDev);
    return;
  }
  if (strcasecmp(line, "wet") == 0) {
    calWet = sampleAnalog(MOISTURE_PIN, 24, 8);
    calWetCaptured = true;
    Serial.printf("captured wet: raw %d (sd %.1f)\n", calWet.mean, calWet.stdDev);
    return;
  }
  if (strncasecmp(line, "ph", 2) == 0 && (line[2] == ' ' || line[2] == '\0')) {
    const float nominal = atof(line + 2);
    if (nominal <= 0.0f || nominal > 14.0f) {
      Serial.println("usage: ph <value>, e.g. 'ph 4.00', 'ph 6.86', 'ph 9.18'");
      return;
    }
    const AnalogStats stats = sampleAnalog(PH_PIN, 24, 8);
    if (calPhCount >= 2) {
      calPhPoints[0] = calPhPoints[1];  // keep the two most recent
      calPhCount = 1;
    }
    calPhPoints[calPhCount].ph = nominal;
    calPhPoints[calPhCount].volts = phVolts(stats.mean);
    calPhPoints[calPhCount].stdDev = stats.stdDev;
    calPhCount++;
    Serial.printf("captured pH %.2f at %.4f V (raw %d, sd %.1f) [%u/2]\n", nominal,
                  phVolts(stats.mean), stats.mean, stats.stdDev, calPhCount);
    return;
  }
  if (strcasecmp(line, "show") == 0) {
    calibrationShow();
    return;
  }
  if (strcasecmp(line, "verify") == 0) {
    calibrationVerify();
    return;
  }
  if (strcasecmp(line, "reset") == 0) {
    calDryCaptured = false;
    calWetCaptured = false;
    calPhCount = 0;
    Serial.println("captured points discarded");
    return;
  }
  Serial.println("unknown calibration command - type 'help' or 'q' to leave");
}

// ---------------------------------------------------------------------------
// Q. Network
// ---------------------------------------------------------------------------
void connectWiFi() {
  if (WiFi.status() == WL_CONNECTED) return;
  if (lastWiFiAttempt != 0 && millis() - lastWiFiAttempt < WIFI_RETRY_INTERVAL_MS) return;

  lastWiFiAttempt = millis();
  LOG_I(TAG_WIFI, "connecting to %s", WIFI_SSID);
  WiFi.mode(WIFI_STA);
  WiFi.begin(WIFI_SSID, WIFI_PASSWORD);
}

// Bug 1, supporting half: keep NTP nagging until it succeeds, and say so once.
void serviceTimeSync() {
  if (timeSynced()) {
    if (!timeAnnounced) {
      timeAnnounced = true;
      char now[32];
      formatUtc(epochMillis(), "%Y-%m-%dT%H:%M:%SZ", now, sizeof(now));
      LOG_I(TAG_NTP, "clock synced after %lu ms: %s", millis(), now);
    }
    return;
  }
  if (WiFi.status() != WL_CONNECTED) return;
  const unsigned long now = millis();
  if (lastNtpWarn != 0 && now - lastNtpWarn < NTP_WARN_INTERVAL_MS) return;
  lastNtpWarn = now;
  LOG_W(TAG_NTP, "clock not synced; readings are withheld until it is");
  configTime(0, 0, "pool.ntp.org", "time.google.com");
}

void logHeartbeat() {
  char uptime[24];
  formatUptime(millis(), uptime, sizeof(uptime));
  LOG_I(TAG_BOOT, "up %s | heap %u/%u | wifi %d dBm %s | fb %s", uptime, ESP.getFreeHeap(),
        ESP.getMaxAllocHeap(), WiFi.RSSI(), WiFi.localIP().toString().c_str(),
        Firebase.ready() ? "ready" : "not ready");

  char health[160] = "";
  for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
    char entry[40];
    snprintf(entry, sizeof(entry), "%s%s=%s", index == 0 ? "" : " ", channels[index].name,
             healthLabel(static_cast<SensorId>(index)));
    strncat(health, entry, sizeof(health) - strlen(health) - 1);
  }
  LOG_I(TAG_HEALTH, "%s | history %s (%s)", health, historyState,
        lastHistorySlot[0] != '\0' ? lastHistorySlot : "no slot yet");
}

// ---------------------------------------------------------------------------
// R. setup
// ---------------------------------------------------------------------------
void printBootBanner() {
  char maskedKey[32];
  maskSecret(FIREBASE_API_KEY, maskedKey, sizeof(maskedKey));
  Serial.println();
  Serial.println("=======================================================");
  Serial.printf(" CropCast ESP32 firmware %s\n", FIRMWARE_VERSION);
  Serial.printf(" build      : %s %s\n", __DATE__, __TIME__);
  Serial.printf(" chip       : %s rev %d, %d cores @ %lu MHz\n", ESP.getChipModel(),
                ESP.getChipRevision(), ESP.getChipCores(),
                static_cast<unsigned long>(ESP.getCpuFreqMHz()));
  Serial.printf(" flash      : %u MB, free heap %u, largest block %u\n",
                ESP.getFlashChipSize() / (1024 * 1024), ESP.getFreeHeap(), ESP.getMaxAllocHeap());
  Serial.printf(" mac        : %s\n", WiFi.macAddress().c_str());
  Serial.printf(" reset      : %s\n", resetReasonName());
  Serial.printf(" device id  : %s\n", DEVICE_ID);
  Serial.printf(" database   : %s\n", FIREBASE_DATABASE_URL);
  Serial.printf(" api key    : %s\n", maskedKey);
  Serial.printf(" log level  : %s (compiled ceiling %s)\n", logLevelName(runtimeLogLevel),
                logLevelName(CROPCAST_LOG_LEVEL));
  Serial.println(" console    : type 'help' + Enter (Serial Monitor line ending = Newline)");
  Serial.println("=======================================================");
}

void probeSensorsAtBoot() {
  LOG_I(TAG_HEALTH, "probing sensors");

  if (channels[SENSOR_DHT].expected) {
    float temperature = 0.0f;
    float humidity = 0.0f;
    lastDhtReadMs = 0;
    if (readDht(temperature, humidity)) {
      markSensorGood(SENSOR_DHT);
      LOG_I(TAG_DHT, "detected: %.1f C / %.0f %%RH", temperature, humidity);
    } else {
      markSensorFail(SENSOR_DHT, "read returned NaN");
      LOG_E(TAG_DHT, "not responding on GPIO %u - check wiring and the 10k pull-up", DHT_PIN);
    }
  }

  if (channels[SENSOR_MOISTURE].expected) {
    const AnalogStats stats = sampleAnalog(MOISTURE_PIN, 16, 4);
    const char *reason = "";
    if (analogLooksConnected(stats, &reason)) {
      markSensorGood(SENSOR_MOISTURE);
      LOG_I(TAG_MOIST, "detected: raw %d (sd %.1f)", stats.mean, stats.stdDev);
    } else {
      markSensorFail(SENSOR_MOISTURE, reason);
      LOG_W(TAG_MOIST, "raw %d: %s", stats.mean, reason);
    }
  }

  if (channels[SENSOR_PH].expected) {
    const AnalogStats stats = sampleAnalog(PH_PIN, 16, 4);
    const char *reason = "";
    if (analogLooksConnected(stats, &reason)) {
      markSensorGood(SENSOR_PH);
      LOG_I(TAG_PH, "detected: raw %d (sd %.1f)", stats.mean, stats.stdDev);
    } else {
      markSensorFail(SENSOR_PH, reason);
      LOG_W(TAG_PH, "raw %d: %s", stats.mean, reason);
    }
  }

  if (channels[SENSOR_NPK].expected) {
    if (updateNpkValues()) {
      LOG_I(TAG_NPK, "detected: N %u, P %u, K %u", lastNitrogen, lastPhosphorus, lastPotassium);
    } else {
      LOG_E(TAG_NPK, "no response: modbus 0x%02X (%s)", lastModbusResult,
            modbusErrorName(lastModbusResult));
    }
  }

  if (channels[SENSOR_LIGHT].expected) {
    if (lightSensorReady) {
      const float lux = lightMeter.readLightLevel();
      if (isfinite(lux) && lux >= 0.0f) {
        markSensorGood(SENSOR_LIGHT);
        LOG_I(TAG_LUX, "detected: %.0f lux", lux);
      } else {
        markSensorFail(SENSOR_LIGHT, "read failed");
      }
    } else {
      markSensorFail(SENSOR_LIGHT, "not detected on I2C");
      LOG_E(TAG_LUX, "BH1750 not on the bus - check SDA=21, SCL=22 and 3.3 V");
    }
  }

  printHealthTable();
  uint8_t unavailable = 0;
  for (uint8_t index = 0; index < SENSOR_COUNT; index++) {
    if (channels[index].expected && !isTrusted(static_cast<SensorId>(index))) unavailable++;
  }
  if (unavailable > 0) {
    LOG_W(TAG_HEALTH, "%u expected channel(s) unavailable; monthly history is suspended",
          unavailable);
  }
}

void setup() {
  Serial.begin(115200);
  delay(300);
  printBootBanner();

  basePath = String("/devices/") + DEVICE_ID;

  pinMode(RS485_DE_RE, OUTPUT);
  digitalWrite(RS485_DE_RE, LOW);
  analogReadResolution(12);
  analogSetPinAttenuation(PH_PIN, ADC_11db);
  analogSetPinAttenuation(MOISTURE_PIN, ADC_11db);

  dht.begin();
  Wire.begin();
  scanI2CBus();
  lightSensorReady = lightMeter.begin(BH1750::CONTINUOUS_HIGH_RES_MODE);

  Serial2.begin(9600, SERIAL_8N1, RS485_RX, RS485_TX);
  npk.begin(NPK_SLAVE_ID, Serial2);
  npk.preTransmission(preTransmission);
  npk.postTransmission(postTransmission);

  probeSensorsAtBoot();

  connectWiFi();
  const unsigned long connectionStarted = millis();
  while (WiFi.status() != WL_CONNECTED && millis() - connectionStarted < 20000UL) {
    delay(250);
    Serial.print('.');
  }
  Serial.println();
  if (WiFi.status() == WL_CONNECTED) {
    LOG_I(TAG_WIFI, "connected: %s, %d dBm", WiFi.localIP().toString().c_str(), WiFi.RSSI());
  } else {
    LOG_W(TAG_WIFI, "unavailable; background retries continue (console still works)");
  }

  configTime(0, 0, "pool.ntp.org", "time.google.com");

  firebaseConfig.api_key = FIREBASE_API_KEY;
  firebaseConfig.database_url = FIREBASE_DATABASE_URL;
  firebaseConfig.token_status_callback = tokenStatusCallback;
  // Bound a hung HTTPS call so it cannot stall past the app's offline window.
  firebaseConfig.timeout.serverResponse = 10 * 1000;
  auth.user.email = FIREBASE_USER_EMAIL;
  auth.user.password = FIREBASE_USER_PASSWORD;

  firebaseData.setBSSLBufferSize(2048, 1024);
  firebaseData.setResponseSize(1024);
  Firebase.reconnectWiFi(true);
  Firebase.begin(&firebaseConfig, &auth);

  LOG_I(TAG_FB, "base path %s", deviceBasePath().c_str());
}

// ---------------------------------------------------------------------------
// S. loop
// ---------------------------------------------------------------------------
void loop() {
  // First, so the console keeps working with Wi-Fi or Firebase down.
  serviceConsole();
  serviceCalibrationStream();

  connectWiFi();
  serviceTimeSync();

  const unsigned long now = millis();
  if (lastHeartbeat == 0 || now - lastHeartbeat >= HEARTBEAT_INTERVAL_MS) {
    lastHeartbeat = now;
    logHeartbeat();
  }

  if (WiFi.status() != WL_CONNECTED || !Firebase.ready()) {
    delay(20);
    return;
  }

  if (!firebaseAnnounced) {
    firebaseAnnounced = true;
    LOG_I(TAG_FB, "authenticated, uid %s", auth.token.uid.c_str());
  }

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
