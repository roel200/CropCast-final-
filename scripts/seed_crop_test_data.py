"""Generate and verify Firebase test readings for CropCast crop profiles."""

from __future__ import annotations

import argparse
import itertools
import json
import math
import statistics
from datetime import datetime, timedelta, timezone
from pathlib import Path


SENSOR_TO_PROFILE_RANGE = {
    "temperature": "temperatureC",
    "humidity": "humidityPct",
    "soilMoisture": "soilMoisturePct",
    "soilPh": "soilPh",
    "nitrogen": "nitrogen",
    "phosphorus": "phosphorus",
    "potassium": "potassium",
}

CROP_VARIETIES = {
    "Tomato": "Roma",
    "Okra": "Native",
    "Alugbati": "Green stem",
    "Potato": "Common",
    "Rice": "Lowland",
    "Corn": "Yellow",
    "Eggplant": "Native",
    "Cucumber": "Slicing",
    "Cabbage": "Green",
    "Sweet Potato": "Common",
    "Lettuce": "Leaf",
    "Spinach": "Leaf",
}


def range_score(value: float, limits: list[float]) -> float:
    lower, upper = limits
    if lower <= value <= upper:
        return 100.0
    distance = lower - value if value < lower else value - upper
    width = upper - lower
    scale = width if width > 0 else max(abs(lower) * 0.20, 1.0)
    return max(0.0, min(100.0, 100.0 - distance / scale * 70.0))


def profile_score(values: dict[str, float], profile: dict) -> int:
    component_scores = [
        range_score(values[sensor], profile["ranges"][range_name])
        for sensor, range_name in SENSOR_TO_PROFILE_RANGE.items()
    ]
    return math.floor(statistics.mean(component_scores) + 0.5)


def distinctive_values(target: dict, profiles: list[dict]) -> dict[str, float]:
    """Choose an in-range point that separates the target from other profiles."""
    fields = list(SENSOR_TO_PROFILE_RANGE.items())
    fractions = (0.10, 0.50, 0.90)
    best_values: dict[str, float] | None = None
    best_rank: tuple[float, float] | None = None

    for choices in itertools.product(fractions, repeat=len(fields)):
        values = {}
        for (sensor, range_name), fraction in zip(fields, choices):
            lower, upper = target["ranges"][range_name]
            values[sensor] = lower + (upper - lower) * fraction
        strongest_other = max(
            profile_score(values, profile)
            for profile in profiles
            if profile["name"] != target["name"]
        )
        center_distance = sum(abs(choice - 0.50) for choice in choices)
        rank = (100 - strongest_other, -center_distance)
        if best_rank is None or rank > best_rank:
            best_rank = rank
            best_values = values

    if best_values is None or best_rank is None or best_rank[0] <= 0:
        raise ValueError(f"Could not find uniquely identifying readings for {target['name']}")
    return best_values


def make_reading(
    base_values: dict[str, float],
    target: dict,
    recorded_at: datetime,
    sample_index: int,
) -> dict[str, float | int]:
    offsets = (-0.020, -0.015, -0.010, -0.005, 0.005, 0.010, 0.015, 0.020)
    reading: dict[str, float | int] = {}
    for sensor, range_name in SENSOR_TO_PROFILE_RANGE.items():
        lower, upper = target["ranges"][range_name]
        varied = base_values[sensor] + (upper - lower) * offsets[sample_index]
        reading[sensor] = round(max(lower, min(upper, varied)), 3)
    reading["lightIntensity"] = 550.0 + (sample_index % 4) * 25.0
    reading["timestamp"] = int(recorded_at.timestamp() * 1000)
    return reading


def build_fixture(
    device_id: str,
    end_time: datetime,
    target: dict,
    profiles: list[dict],
) -> dict:
    base_values = distinctive_values(target, profiles)
    monthly: dict[str, dict[str, dict[str, float | int]]] = {}
    generated = []
    for index in range(8):
        recorded_at = end_time - timedelta(hours=7 - index)
        sample = make_reading(base_values, target, recorded_at, index)
        generated.append(sample)
        month_key = recorded_at.strftime("%Y-%m")
        sample_key = recorded_at.strftime("%Y-%m-%d_%H-%M-%S")
        monthly.setdefault(month_key, {})[sample_key] = sample

    latest = generated[-1]
    crop = target["name"]
    ph_limits = target["ranges"]["soilPh"]
    return {
        "devices": {
            device_id: {
                "readings": {"current": latest, "monthly": monthly},
                "status": {
                    "online": True,
                    "lastSeen": latest["timestamp"],
                    "firmware": f"{crop.lower().replace(' ', '-')}-test-fixture",
                    "sensors": ["ESP32", "DHT11", "NPK", "pH", "MOISTURE", "LUX"],
                },
                "settings": {
                    "enabled": True,
                    "lowMoistureAlertEnabled": True,
                    "soilMoistureThreshold": 40,
                    "highTemperatureThreshold": 35,
                    "minSoilPh": ph_limits[0],
                    "maxSoilPh": ph_limits[1],
                    "deviceOfflineMinutes": 2,
                    "repeatIntervalMinutes": 30,
                    "soundEnabled": True,
                    "vibrationEnabled": True,
                    "farmName": f"{crop} Test Farm",
                    "deviceName": f"{crop} Test Sensor",
                    "currentCrop": crop,
                    "cropVariety": CROP_VARIETIES.get(crop, "Common"),
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


def fixture_averages(fixture: dict, device_id: str) -> dict[str, float]:
    monthly = fixture["devices"][device_id]["readings"]["monthly"]
    samples = [sample for month in monthly.values() for sample in month.values()]
    return {
        sensor: statistics.mean(sample[sensor] for sample in samples)
        for sensor in SENSOR_TO_PROFILE_RANGE
    }


def verify_recommendation(
    fixture: dict,
    profiles: list[dict],
    device_id: str,
) -> tuple[str, int]:
    averages = fixture_averages(fixture, device_id)
    scores = [(profile["name"], profile_score(averages, profile)) for profile in profiles]
    return max(scores, key=lambda item: item[1])


def parse_utc(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc).replace(microsecond=0)


def safe_filename(crop: str) -> str:
    return crop.lower().replace(" ", "-") + "-test-data.json"


def main(default_crop: str = "Potato") -> None:
    project_root = Path(__file__).resolve().parents[1]
    profiles_path = project_root / "data/processed/crop_requirements_selected.json"
    profiles = json.loads(profiles_path.read_text(encoding="utf-8"))["crops"]
    profiles_by_name = {profile["name"]: profile for profile in profiles}

    parser = argparse.ArgumentParser(
        description="Create verified Firebase JSON readings for a CropCast crop."
    )
    parser.add_argument("--crop", choices=profiles_by_name, default=default_crop)
    parser.add_argument("--all", action="store_true", help="Generate one fixture per crop")
    parser.add_argument("--device-id", default="esp32-field-01")
    parser.add_argument(
        "--at",
        type=parse_utc,
        default=(datetime.now(timezone.utc) - timedelta(days=31)).replace(microsecond=0),
        help="UTC end time, for example 2026-09-02T08:00:00Z",
    )
    parser.add_argument("--output", type=Path, help="Single-crop output path")
    args = parser.parse_args()
    if args.all and args.output is not None:
        parser.error("--output cannot be combined with --all")

    crop_names = list(profiles_by_name) if args.all else [args.crop]
    for crop in crop_names:
        target = profiles_by_name[crop]
        fixture = build_fixture(args.device_id, args.at, target, profiles)
        destination = (
            args.output
            if args.output is not None
            else project_root / "firebase" / safe_filename(crop)
        ).resolve()
        destination.parent.mkdir(parents=True, exist_ok=True)
        destination.write_text(
            json.dumps(fixture, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
        )

        recommendation, suitability = verify_recommendation(fixture, profiles, args.device_id)
        if recommendation != crop:
            raise SystemExit(
                f"Fixture verification failed for {crop}: recommended {recommendation}"
            )
        print(f"{crop}: {suitability}% -> {destination}")

    print("Scored: N, P, K, pH, soil moisture, temperature, humidity")
    print("Light remains display-only and is not scored")


if __name__ == "__main__":
    main()
