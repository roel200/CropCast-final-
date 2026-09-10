# CropCast ESP32 firmware

This sketch reads the CropCast sensor set and writes data to the Firebase paths consumed by the Android dashboard.

It is built for staged bring-up: sensors can be wired one at a time, and the firmware refuses to feed values it does not trust into the crop-recommendation pipeline. A serial console (115200 baud) exposes live diagnostics and a guided probe calibration.

## Wiring

| Module | ESP32 connection | Notes |
|---|---|---|
| DHT11 | DATA → GPIO 4 | Add a 10 kΩ pull-up from DATA to 3.3 V if your module does not include one. |
| Capacitive moisture sensor | AO → GPIO 35 | Power from 3.3 V; calibrate the dry and wet raw constants. |
| Analog pH interface | AO → GPIO 34 | Ensure its output never exceeds 3.3 V. Calibrate with buffer solutions. |
| BH1750 | SDA → GPIO 21, SCL → GPIO 22 | Default ESP32 I²C pins. |
| MAX485 / NPK probe | RO → GPIO 16, DI → GPIO 17, DE+RE → GPIO 5 | Use a common ground and the power supply required by the probe. Do not power a 12 V probe from the ESP32. |

GPIO 34 and 35 are ADC1 pins, so they keep working while Wi-Fi is active. GPIO 5 is a strapping pin but is only driven after boot, so no external pull-down is needed.

The NPK code expects Modbus slave ID `1`, 9600 baud, and holding registers `0x001E`–`0x0020`. Confirm these values in the sensor manual — the serial `npk` command reads any register you name, which is the quickest way to check.

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
6. Open Serial Monitor at 115200 baud, **set the line ending to Newline**, and type `help`.

Live readings are written every 15 seconds. One sample per hour is stored in `readings/monthly/yyyy-MM/yyyy-MM-dd_HH-00-00`, so the Android dashboard can display every available month and generate crop recommendations from monthly averages. The `cloudHistoryEnabled` setting controls whether those history samples are stored.

## Serial console

Set the Serial Monitor line ending to **Newline** or **Both NL & CR**. With "No line ending" the console never receives a complete command and appears dead.

The console is serviced before the network check, so it keeps working with Wi-Fi or Firebase down. It is briefly unresponsive during an upload, because the Firebase client is synchronous.

| Command | What it does |
|---|---|
| `help`, `?` | List commands |
| `status` | Uptime, heap, Wi-Fi, clock, Firebase, history state, timers, last published values, health table |
| `health` | Per-sensor health table |
| `read` | Read every sensor now — raw counts, converted values, and which fields are trusted. Uploads nothing. |
| `publish [current\|history\|status\|all]` | Force a publish and print the gate decision, including why something was withheld |
| `scan` | I²C bus scan, annotating BH1750 at `0x23` / `0x5C` |
| `npk [reg] [count]` | One Modbus transaction with a hex dump and a decoded error name. The fastest way to debug RS485. |
| `time [sync]` | Clock state and the current hour slot; `time sync` re-arms NTP |
| `wifi [reconnect\|scan]` | Network state; force a reconnect; list nearby 2.4 GHz SSIDs, channels, and signal strengths |
| `fb` | Firebase ready state, UID, base path, last error and HTTP code |
| `sensors` | List channels; `sensors ph off` marks one not wired for this session |
| `log [level]` | Show or set verbosity: `off error warn info debug trace` |
| `history [on\|off]` | Local override of `cloudHistoryEnabled`, so a bench test never lands in the month |
| `cal` | Enter probe calibration mode |
| `reboot` | Mark the device offline, then restart |

Log lines are fixed-width and greppable:

```
[    12.345] I/PUB   : current uploaded (7/7 scored fields trusted)
[    12.401] W/NPK   : modbus 0xE2 (response timed out), 3 consecutive, using cached value
```

Passwords and auth tokens are never printed. The API key is shown masked.

## Calibrating the probes

**Required before field use.** The `MOISTURE_DRY_RAW`, `MOISTURE_WET_RAW`, `PH_SLOPE`, and `PH_OFFSET` values shipped in the sketch are placeholders and will not be accurate for your probes or soil.

Type `cal` to enter calibration mode. It streams both analog channels twice a second with min/max/standard deviation, so you can see whether a reading has **settled** before capturing it:

```
[CAL] MOIST raw=2871 min=2864 max=2879 sd=4.1 -> 18.4 %  |  PH raw=1783 v=1.4364 sd=2.7 -> pH 13.15
```

While calibration mode is active, moisture and pH are marked untrusted. That keeps a probe sitting in a buffer solution out of `readings/monthly` entirely — the path that feeds crop recommendations — and publishes them as `0` placeholders in `readings/current`, exactly as an unwired sensor would.

| Sub-command | Effect |
|---|---|
| `dry` | Capture the moisture point in air (0 %) |
| `wet` | Capture the moisture point in water (100 %) |
| `ph <value>` | Capture a pH buffer, e.g. `ph 4.00` then `ph 6.86` |
| `show` | Solve, validate, and print a block to paste into `secrets.h` |
| `verify` | Re-measure and report the error against the captured points |
| `reset` | Discard captured points |
| `q` | Leave calibration mode |

### Moisture — two-point linear

Wipe and air-dry the probe, run `dry`. Then submerge it **only to the marked line** — going past it destroys a capacitive probe — and run `wet`. Those two means are the two constants:

```
percent = (DRY_RAW - raw) * 100 / (DRY_RAW - WET_RAW), clamped to 0..100
```

`show` refuses a result where dry does not read higher than wet (capacitive probes read higher when dry — reversed values mean the captures were swapped, or the probe is resistive). It warns when the span is under 300 counts, because then 1 % is under 3 ADC counts and noise dominates, and when a capture had a standard deviation above 40, meaning it had not settled.

Calibrate in the actual field soil where possible. 0–100 % here means "air-dry to saturated", not volumetric water content.

### pH — two-point from voltage

Rinse the electrode in distilled water between buffers and blot it dry (do not wipe the bulb). Let each reading settle for 30–60 s, watching `sd` in the stream, then capture. Buffer sachets sold locally are 4.00 / 6.86 / 9.18, so `ph` takes any nominal value rather than assuming 4 and 7.

```
v = raw * 3.3 / 4095

PH_SLOPE  = (phB - phA) / (vB - vA)
PH_OFFSET = phA - PH_SLOPE * vA
```

Worked example — 4.00 reading 1.410 V and 6.86 reading 0.909 V:

```
PH_SLOPE  = (6.86 - 4.00) / (0.909 - 1.410) = 2.86 / -0.501 = -5.7086
PH_OFFSET = 4.00 - (-5.7086 * 1.410)        = 4.00 + 8.049  = 12.0491
```

A negative slope is normal for the common analog pH boards. `show` prints the residual at each captured point and warns when the two buffers differ by less than 0.10 V (electrode not responding, loose BNC, or unsettled buffers) or when `|slope|` falls outside 2–10 pH/V.

`show` ends with a paste-ready block:

```
--- copy into secrets.h ---
// calibrated 2026-09-07T05:12:44Z, firmware 2.0.0, device esp32-field-01
#define CROPCAST_MOISTURE_DRY_RAW 3187   // air, sd 3.2
#define CROPCAST_MOISTURE_WET_RAW 1402   // water, sd 5.8, span 1785
#define CROPCAST_PH_SLOPE  -5.7086f // from pH 4.00 @ 1.4100 V and pH 6.86 @ 0.9090 V
#define CROPCAST_PH_OFFSET 12.0491f
---------------------------
```

Paste it into `secrets.h` and reflash. Calibration lives there, not in the sketch, so per-device values stay out of git and every node can share one identical `.ino`.

## Bringing sensors online one at a time

Mark a channel off in `secrets.h` (`CROPCAST_SENSOR_PH_ENABLED false`) or at runtime with `sensors ph off`. What gets published in each state:

| State | `readings/current` | `readings/monthly` | `status` |
|---|---|---|---|
| All scored sensors trusted | full reading | one sample per hour | online |
| Any scored sensor missing | published, `0` placeholder for it, logged at WARN | **nothing** | online, `degraded:true` |
| Clock not synced | **nothing** | **nothing** | online, `lastSeen:0` |

**Why history is all-or-nothing.** `readings/monthly` is the only path the app aggregates. `MonthlySensorAggregator` takes an unweighted mean over every valid sample in the month, and `SeedRecommendationEngine` scores seven fields. With N/P/K at zero, three of those seven collapse — against the Tomato profile (N 80–150, P 50–70, K 100–150) they score 20/0/0 instead of up to 100 — and the ranking between crops ends up decided by residual noise instead of by soil. Withholding costs nothing: the month simply stays below the 8-sample minimum and the app honestly reports that there is not enough data, which is far better than a confidently wrong crop.

Nothing is lost by waiting. The gate is re-checked every 15 s, so a probe that comes online at 14:37 still fills the 14:00 slot.

**During bring-up, placeholder zeros will trip the app's low-moisture and pH-out-of-range alerts**, repeating on the interval in Settings. This is pre-existing app behaviour for any missing reading, not something the firmware introduced. Turn alerts off in the app's Settings screen, or lower the thresholds, until the probes are wired.

## Data contract

Every reading record carries **exactly** these nine fields, and `timestamp` is Unix milliseconds UTC:

```
temperature humidity soilMoisture soilPh nitrogen phosphorus potassium lightIntensity timestamp
```

Three gates a reading must survive:

- **Server** — `firebase/database.rules.json` requires all nine children and validates temperature −20..80, humidity 0..100, soilMoisture 0..100, soilPh 0..14, and N/P/K/light ≥ 0. One out-of-range field rejects the **entire** write, which is why the firmware zeroes an implausible value rather than letting it take the other eight fields down.
- **App** — `isValidForRecommendation()` repeats those ranges and adds `timestamp > 0`. A reading that fails is dropped, and the dashboard then renders zeros.
- **Scoring** — seven fields are scored. `lightIntensity` is deliberately excluded until lux thresholds are validated, so it is the one field where a placeholder is harmless.

Do not add a tenth field to a reading record. The rules would accept it, but the Android app logs an unknown-property warning on every snapshot and ignores the value. Diagnostics belong under `/status`, which the app reads with explicit lookups and which is safe to extend. The firmware only ever **reads** `/settings`; it must never write there.

### Status payload

Alongside `online`, `lastSeen`, `firmware`, and `sensors[]`, the firmware publishes `wifiRssi`, `ipAddress`, `uptimeSeconds`, and three diagnostic keys the app ignores: `sensorHealth` (per-channel state), `degraded`, and `historyState` (`publishing` / `withheld` / `disabled`). `sensors[]` now lists only channels that are currently healthy, always including `ESP32`.

`status` is published on every cycle no matter what fails, so a single bad sensor can never make the device look offline.

## Troubleshooting

| Symptom | Check |
|---|---|
| Console appears dead | Serial Monitor line ending must be Newline, baud 115200 |
| `BH1750 not on the bus` | Run `scan`. Expect `0x23` (ADDR low) or `0x5C` (ADDR high). Check SDA 21 / SCL 22 and 3.3 V. |
| `modbus 0xE2 (response timed out)` | Run `npk`. Check RO→16, DI→17, DE+RE→5, common ground, probe power, slave ID and baud. |
| `modbus 0x02 (illegal data address)` | Wrong register. Try `npk 0x0000 4` and consult the probe manual. |
| `raw pinned near 0` / `near 4095` | Analog pin is floating or shorted. The channel keeps polling and recovers on its own once it reads sanely. |
| `clock not synced` | NTP is unreachable. Readings are withheld on purpose until it succeeds; `time sync` retries. |
| Firebase error with `http 400` | The database rules rejected the write — usually a value outside the validated range. |
| Firebase error with `http 401` | Authentication failed. Check the API key and that the firmware user exists in Firebase Authentication. |
| `ESP_RST_BROWNOUT` in the boot banner | Power supply cannot hold up under load, commonly an under-powered RS485 probe. |
| History never publishes | `status` → `historyState`. `withheld` means a sensor is untrusted (`health` says which); `disabled` means `cloudHistoryEnabled` is off. |
