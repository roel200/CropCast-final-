"""Generate deterministic-looking Firebase test data that should recommend Potato."""

from __future__ import annotations

import argparse
import json
import math
import statistics
from datetime import datetime, timedelta, timezone
from pathlib import Path


POTATO_READINGS = (
    (14.5, 84.0, 60.0, 5.1, 105.0, 70.0, 145.0, 550.0),
    (15.0, 85.0, 61.0, 5.2, 110.0, 75.0, 150.0, 575.0),
    (15.5, 86.0, 62.0, 5.3, 115.0, 80.0, 155.0, 600.0),
    (16.0, 87.0, 63.0, 5.2, 120.0, 85.0, 160.0, 625.0),
    (15.5, 86.0, 64.0, 5.1, 115.0, 80.0, 155.0, 600.0),
    (15.0, 85.0, 63.0, 5.2, 110.0, 75.0, 150.0, 575.0),
    (14.5, 84.0, 62.0, 5.3, 105.0, 70.0, 145.0, 550.0),
    (15.0, 85.0, 61.0, 5.2, 110.0, 75.0, 150.0, 575.0),
)

SENSOR_TO_PROFILE_RANGE = {
    "temperature": "temperatureC",
    "humidity": "humidityPct",
    "soilMoisture": "soilMoisturePct",
    "soilPh": "soilPh",
    "nitrogen": "nitrogen",
    "phosphorus": "phosphorus",
    "potassium": "potassium",
}


def reading(values: tuple[float, ...], recorded_at: datetime) -> dict[str, float | int]:
    temperature, humidity, moisture, ph, nitrogen, phosphorus, potassium, lux = values
    return {
        "temperature": temperature,
        "humidity": humidity,
        "soilMoisture": moisture,
        "soilPh": ph,
        "nitrogen": nitrogen,
        "phosphorus": phosphorus,
        "potassium": potassium,
        "lightIntensity": lux,
        "timestamp": int(recorded_at.timestamp() * 1000),
    }


def build_fixture(device_id: str, end_time: datetime) -> dict:
    monthly: dict[str, dict[str, dict[str, float | int]]] = {}
    generated = []
    for index, values in enumerate(POTATO_READINGS):
        recorded_at = end_time - timedelta(hours=len(POTATO_READINGS) - index - 1)
        sample = reading(values, recorded_at)
        generated.append(sample)
        month_key = recorded_at.strftime("%Y-%m")
        sample_key = recorded_at.strftime("%Y-%m-%d_%H-%M-%S")
        monthly.setdefault(month_key, {})[sample_key] = sample

    latest = generated[-1]
    return {
        "devices": {
            device_id: {
                "readings": {"current": latest, "monthly": monthly},
                "status": {
                    "online": True,
                    "lastSeen": latest["timestamp"],
                    "firmware": "potato-test-fixture",
                    "sensors": ["ESP32", "DHT11", "NPK", "pH", "MOISTURE", "LUX"],
                },
                "settings": {
                    "enabled": True,
                    "lowMoistureAlertEnabled": True,
                    "soilMoistureThreshold": 40,
                    "highTemperatureThreshold": 35,
                    "minSoilPh": 4.8,
                    "maxSoilPh": 5.5,
                    "deviceOfflineMinutes": 2,
                    "repeatIntervalMinutes": 30,
                    "soundEnabled": True,
                    "vibrationEnabled": True,
                    "farmName": "Potato Test Farm",
                    "deviceName": "Potato Test Sensor",
                    "currentCrop": "Potato",
                    "cropVariety": "Common",
                    "plantingDate": "",
                    "language": "English",
                    "darkModeEnabled": False,
                    "cloudHistoryEnabled": True,
                    "diagnosticsEnabled": True,
                },
                "commands": {},
                "alerts": {},
            }
        }
    }


def range_score(value: float, limits: list[float]) -> float:
    lower, upper = limits
    if lower <= value <= upper:
        return 100.0
    distance = lower - value if value < lower else value - upper
    width = upper - lower
    scale = width if width > 0 else max(abs(lower) * 0.20, 1.0)
    return max(0.0, min(100.0, 100.0 - distance / scale * 70.0))


def verify_recommendation(fixture: dict, profiles_path: Path, device_id: str) -> tuple[str, int]:
    monthly = fixture["devices"][device_id]["readings"]["monthly"]
    samples = [sample for month in monthly.values() for sample in month.values()]
    averages = {
        sensor: statistics.mean(sample[sensor] for sample in samples)
        for sensor in SENSOR_TO_PROFILE_RANGE
    }
    crop_table = json.loads(profiles_path.read_text(encoding="utf-8"))["crops"]
    scored = []
    for crop in crop_table:
        scores = [
            range_score(averages[sensor], crop["ranges"][range_name])
            for sensor, range_name in SENSOR_TO_PROFILE_RANGE.items()
        ]
        kotlin_rounded_score = math.floor(statistics.mean(scores) + 0.5)
        scored.append((crop["name"], kotlin_rounded_score))
    return max(scored, key=lambda item: item[1])


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).replace(microsecond=0)


def main() -> None:
    project_root = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(
        description="Create local Firebase JSON readings that make CropCast recommend Potato."
    )
    parser.add_argument("--device-id", default="esp32-field-01")
    parser.add_argument(
        "--at",
        type=parse_utc,
        default=(datetime.now(timezone.utc) - timedelta(days=31)).replace(microsecond=0),
        help="UTC end time, for example 2026-09-02T08:00:00Z",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=project_root / "firebase/potato-test-data.json",
    )
    args = parser.parse_args()

    destination = args.output.resolve()
    destination.parent.mkdir(parents=True, exist_ok=True)
    fixture = build_fixture(args.device_id, args.at)
    destination.write_text(
        json.dumps(fixture, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )

    recommendation, suitability = verify_recommendation(
        fixture,
        project_root / "data/processed/crop_requirements_selected.json",
        args.device_id,
    )
    if recommendation != "Potato":
        raise SystemExit(f"Fixture verification failed: recommended {recommendation}")

    print(f"Created {destination}")
    print(f"Verified recommendation: {recommendation} ({suitability}% suitability)")
    print("Scored fields: N, P, K, pH, soil moisture, temperature, humidity")
    print("Light is stored in lux for display only and is not scored")


if __name__ == "__main__":
    main()
