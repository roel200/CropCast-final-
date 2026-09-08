# CropCast

CropCast is a Kotlin/Jetpack Compose Android dashboard for an ESP32 farm sensor node. It follows the supplied green mobile references and includes sensor monitoring, configurable soil-moisture alerts, alert history, monthly sensor summaries, observed monthly crop recommendations, and a next-month forecast based on past sensor history.

The Settings screen includes editable farm/device names, connection testing, ESP32 restart commands, notification thresholds and behavior, crop/planting information, language preference, offline-data status, farmer/administrator profile details, password/sign-out actions, and privacy controls.

## Included sensor fields

| Sensor | Firebase field | Unit |
|---|---|---|
| DHT11 | `temperature`, `humidity` | °C, % |
| Capacitive moisture (optional reference-screen feature) | `soilMoisture` | % |
| Soil pH probe | `soilPh` | pH |
| RS485 NPK sensor | `nitrogen`, `phosphorus`, `potassium` | mg/kg |
| BH1750 light sensor | `lightIntensity` | lux |

If Firebase has not been configured, the login form starts a local demo session so the UI can be previewed immediately. Credentials are validated only for basic form requirements in this mode; email verification, cloud authentication, and synchronization require `app/google-services.json`.

## Android setup

1. Open this folder in Android Studio.
2. Create a Firebase project and register Android package `com.cropcast.app`.
3. Download `google-services.json` into `app/google-services.json`.
4. Enable **Email/Password** and **Anonymous** sign-in under Firebase Authentication.
5. Create at least one email/password user for farm staff who should log in.
6. Create Realtime Database, then deploy `firebase/database.rules.json`.
7. Optionally import `firebase/sample-data.json`, or generate the Potato test
   fixture described below.
8. Build and run on an Android 8.0+ device or emulator.

The default device path is:

```text
/devices/esp32-field-01
  /readings/current
  /readings/monthly/{yyyy-MM}/{yyyy-MM-dd_HH-mm-ss}
  /status
  /settings
  /alerts
```

The included rules are appropriate for a prototype: any authenticated Firebase user can access device data. Before production, bind users/devices with custom claims or per-device ownership rules and disable client-side simulation.

## Google sign-in setup

The login screen supports Google through Android Credential Manager and Firebase
Authentication. Email/password and guest login remain available. Google sign-in
is disabled in local demo mode.

1. In Firebase Console, open **Authentication → Sign-in method**, enable
   **Google**, select a support email, and save.
2. Run `./gradlew :app:signingReport` (Windows: `gradlew.bat :app:signingReport`).
   In **Project settings → Your apps → com.cropcast.app**, add the debug
   certificate SHA-1 and SHA-256 fingerprints. Add release signing fingerprints
   as well when distributing a release build.
3. Download the updated `google-services.json` from that Android app's settings
   and replace `app/google-services.json`. It must contain a Web OAuth client
   (client type 3), used to generate `default_web_client_id`.
4. Sync Gradle and rebuild. Use a real device with Google Play services or an
   emulator with a **Google Play** system image, and add a Google account.
5. Tap **Sign in with Google**, choose an account, and verify that the dashboard
   opens. Sign out and try another account; canceling the chooser should leave
   the login screen usable. Also check the existing email and guest flows.

The initial checked-in Firebase configuration has no OAuth clients, so it must
be updated before Google sign-in can succeed. Never put a Google client secret
in the Android app.

## ESP32 setup

Install these Arduino libraries:

- Firebase Arduino Client Library for ESP8266 and ESP32 (`Firebase_ESP_Client`)
- DHT sensor library
- BH1750
- ModbusMaster

Copy `firmware/CropCastESP32/secrets.example.h` to `secrets.h`, fill in Wi-Fi/Firebase credentials, and create that email/password account in Firebase Authentication. Confirm your NPK probe register addresses and calibrate the pH and moisture constants before field use.

The sample firmware wiring defaults are DHT11 GPIO 4, pH ADC GPIO 34, moisture ADC GPIO 35, RS485 RX/TX GPIO 16/17, and MAX485 DE/RE GPIO 5.

## Notes

- The current pH equation and moisture raw limits are placeholders that require calibration with the real probes.
- NPK register addresses vary by manufacturer; the firmware uses common registers `0x001E`–`0x0020` and slave ID `1`.
- Firebase timestamps use NTP-derived Unix milliseconds, which the app uses to determine whether the ESP32 was seen within the last two minutes.
- The firmware stores one history sample per hour under a UTC `yyyy-MM` bucket. The dashboard validates readings, tracks monthly averages/ranges/variability, creates an observed recommendation for every eligible closed month, and forecasts the next month from the latest month, same-calendar-month history when available, and the three preceding complete months. The rule-based engine recommends the best-matching crop from Tomato, Okra, Alugbati, Potato, Rice, Corn, Eggplant, Cucumber, Cabbage, Sweet Potato, Lettuce, and Spinach using N, P, K, pH, soil moisture, temperature, and humidity.
- The app's primary recommendation uses an experimental on-device Random Forest ranking from the public 22-crop dataset. The model uses N, P, K, temperature, humidity, and pH. Rainfall was excluded because the device has no verified rainfall input. Its saved metrics describe only a held-out portion of the public dataset and are not evidence of Philippine field accuracy; see `models/public_crop/README.md`.
- Recommendations show the runner-up, confidence, unstable fields, month-to-month trends, and general Philippine wet/dry-season guidance. This seasonal note is advisory and explicitly asks the farmer to confirm a local weather forecast.
- Farmers can save planting outcomes under `devices/{deviceId}/recommendations/feedback`. A crop's score receives a conservative local adjustment only after at least three valid 1–5 outcome ratings; harvest weight and notes are stored for later evaluation but do not change the score because field area and yield units are not yet normalized.
- Light remains visible in the dashboard but is intentionally excluded from crop scoring until crop-specific lux thresholds are validated. The tomato reference dataset's solar radiation in `W/m2` is not converted to lux.
- Dataset preparation is reproducible with `scripts/prepare_cropcast_data.py`; raw downloads stay unchanged under `data/raw`, and cleaned/selected outputs are written under `data/processed`.
- Restart requests are written to `/devices/esp32-field-01/commands/restart`; the included firmware checks this command every five seconds and acknowledges it before restarting.

## Crop recommendation test data

Generate a Firebase-ready fixture containing eight simulated Potato-compatible
sensor readings:

```powershell
python scripts/seed_potato_test_data.py
```

The fixture defaults to the previous UTC month so it can be used as a completed
month for the next-month forecast. Use `--at` to choose a different UTC month.

Generate and verify a Rice fixture with:

```powershell
python scripts/seed_crop_test_data.py --crop Rice
```

Generate verified fixtures for every supported crop with:

```powershell
python scripts/seed_crop_test_data.py --all
```

The commands write `firebase/potato-test-data.json` or
`firebase/rice-test-data.json` and verify that their monthly
averages score the selected crop highest. Import it only into a test
Firebase Realtime Database because a root-level JSON import can replace existing
test data. After signing in and opening the Seeds screen, CropCast should show
the selected crop as the next-month forecast when the fixture is in a closed
month, with its observed recommendation available in the monthly history. The
`W/m2` versus lux limitation still applies: the generated lux values are
displayed but are not part of the recommendation score.

You can verify the recommendation engine without Firebase by running:

```powershell
.\gradlew.bat testDebugUnitTest --tests "com.cropcast.app.data.SeedRecommendationEngineTest.recommendsPotatoForSeededPotatoReadings"
```

## Live Firebase sensor simulator

When physical sensors are not available, send changing readings to Firebase
with the included simulator. It uses anonymous Firebase authentication by
default, writes only to the selected device paths, and never performs a
root-level JSON import:

```powershell
python scripts/simulate_firebase_sensor.py --crop Alugbati --interval 5
```

Keep the app open on the Home or Sensor screen; its Firebase listeners should
update the current values and online status every five seconds. Stop the
simulator with `Ctrl+C`; it marks the simulated device offline.

To create eight valid readings in a completed month and test the next-month
recommendation, use a past UTC month:

```powershell
python scripts/simulate_firebase_sensor.py --crop Alugbati --backfill-month 2026-08 --count 8 --interval 1
```

Use `--dry-run` to check a reading without changing Firebase. Use
`--email EMAIL --password PASSWORD` when anonymous sign-in is disabled, or
pass an existing Firebase `--id-token`. This simulator is for app and Firebase
testing only; its readings are not real sensor evidence.

For a visual preview in a debug build, launch the app with its local demo data:

```powershell
adb shell am start -S -n com.cropcast.app/.MainActivity --ez forceDemo true
```

Continue as guest, then open **Crop Recommendation**. The `forceDemo` extra is
ignored by release builds.

To preview any supported crop instead of Potato, include the debug-only crop selector:

```powershell
adb shell am start -S -n com.cropcast.app/.MainActivity --ez forceDemo true --es demoCrop Rice
```
