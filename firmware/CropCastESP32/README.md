# CropCast ESP32 firmware

This sketch reads the CropCast sensor set and writes data to the Firebase paths consumed by the Android dashboard.

## Wiring

| Module | ESP32 connection | Notes |
|---|---|---|
| DHT11 | DATA → GPIO 4 | Add a 10 kΩ pull-up from DATA to 3.3 V if your module does not include one. |
| Capacitive moisture sensor | AO → GPIO 35 | Power from 3.3 V; calibrate the dry and wet raw constants. |
| Analog pH interface | AO → GPIO 34 | Ensure its output never exceeds 3.3 V. Calibrate with pH 4 and pH 7 buffer solutions. |
| BH1750 | SDA → GPIO 21, SCL → GPIO 22 | Default ESP32 I²C pins. |
| MAX485 / NPK probe | RO → GPIO 16, DI → GPIO 17, DE+RE → GPIO 5 | Use a common ground and the power supply required by the probe. Do not power a 12 V probe from the ESP32. |

The NPK code expects Modbus slave ID `1`, 9600 baud, and holding registers `0x001E`–`0x0020`. Confirm these values in the sensor manual.

## Arduino libraries

- Firebase Arduino Client Library for ESP8266 and ESP32 (`Firebase_ESP_Client`)
- DHT sensor library by Adafruit
- Adafruit Unified Sensor
- BH1750 by Christopher Laws
- ModbusMaster by Doc Walker

## Setup

1. Copy `secrets.example.h` to `secrets.h`.
2. Enter the Wi-Fi, Firebase Realtime Database, and Firebase Authentication credentials.
3. Create the firmware email/password user in Firebase Authentication.
4. Deploy `../../firebase/database.rules.json`.
5. Select the correct ESP32 board and serial port, then upload `CropCastESP32.ino`.
6. Open Serial Monitor at 115200 baud and verify the upload messages.

Live readings are written every 15 seconds. One sample per hour is stored in `readings/monthly/yyyy-MM/yyyy-MM-dd_HH-mm-ss`, so the Android dashboard can display every available month and generate seed recommendations from monthly averages. The `cloudHistoryEnabled` setting controls whether those history samples are stored.

## Required calibration

Update `MOISTURE_DRY_RAW`, `MOISTURE_WET_RAW`, `PH_SLOPE`, and `PH_OFFSET` in the sketch before relying on field readings. The included values are placeholders and will not be accurate for every probe or soil type.
