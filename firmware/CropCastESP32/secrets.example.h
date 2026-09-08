#pragma once

#define WIFI_SSID "YOUR_WIFI_NAME"
#define WIFI_PASSWORD "YOUR_WIFI_PASSWORD"
#define FIREBASE_API_KEY "YOUR_FIREBASE_WEB_API_KEY"
#define FIREBASE_DATABASE_URL "https://YOUR_PROJECT-default-rtdb.asia-southeast1.firebasedatabase.app/"
#define FIREBASE_USER_EMAIL "esp32-device@example.com"
#define FIREBASE_USER_PASSWORD "CHANGE_ME"
#define DEVICE_ID "esp32-field-01"

// ---------------------------------------------------------------------------
// Optional per-device overrides. Everything below has a default in
// CropCastESP32.ino, so uncomment only what this particular node needs.
// secrets.h is gitignored, which keeps per-device values out of the repo and
// lets every node share one identical sketch.
// ---------------------------------------------------------------------------

// Bring-up. Set a channel false until it is physically wired. An unwired
// channel publishes 0 in readings/current (logged, never silent) and suspends
// monthly history entirely, so an absent probe can never feed zeros into the
// crop recommendation. You can also toggle these at runtime without
// reflashing: `sensors ph off` in the serial console.
// #define CROPCAST_SENSOR_DHT_ENABLED      false
// #define CROPCAST_SENSOR_MOISTURE_ENABLED false
// #define CROPCAST_SENSOR_PH_ENABLED       false
// #define CROPCAST_SENSOR_NPK_ENABLED      false
// #define CROPCAST_SENSOR_LIGHT_ENABLED    false

// Probe calibration. REQUIRED before field use -- the defaults in the sketch
// are placeholders and will not be accurate for your probes or soil.
// Run the `cal` serial command and paste the block its `show` step prints.
// See "Calibrating the probes" in README.md.
// #define CROPCAST_MOISTURE_DRY_RAW 3200
// #define CROPCAST_MOISTURE_WET_RAW 1250
// #define CROPCAST_PH_SLOPE        -5.70f
// #define CROPCAST_PH_OFFSET       21.34f

// Serial verbosity: 0=off 1=error 2=warn 3=info 4=debug 5=trace.
// This is a compile-time ceiling; `log <level>` lowers it at runtime.
// #define CROPCAST_LOG_LEVEL 4

// Set false to withhold readings/current entirely while any scored sensor is
// missing, instead of publishing a logged 0 placeholder for it.
// #define CROPCAST_PUBLISH_CURRENT_WHEN_INCOMPLETE false

// Set false to compile the serial console out of a production build.
// #define CROPCAST_CONSOLE_ENABLED false
