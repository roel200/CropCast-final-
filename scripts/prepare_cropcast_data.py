"""Prepare auditable CropCast reference data without modifying raw downloads."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import pandas as pd


REQUIRED_TOMATO_SENSOR_COLUMNS = [
    "7in1_Nitrogen[mg/kg]",
    "7in1_Phosphorus[mg/kg]",
    "7in1_Potasium[mg/kg]",
    "7in1_Ph[pH]",
    "7in1_Moisture[%RH]",
    "7in1_S_Temperature[C]",
    "7in1_EC[uS/cm]",
    "Pynamometer_Radiation[W/m2]",
    "Air_sensor_Temperature[C]",
    "Air_sensor_Humidity[%RH]",
]

OUTPUT_COLUMN_MAP = {
    "Date": "date",
    "Crop": "crop",
    "Plant": "plant_id",
    "Treatment": "treatment",
    "7in1_Nitrogen[mg/kg]": "nitrogen_mg_kg",
    "7in1_Phosphorus[mg/kg]": "phosphorus_mg_kg",
    "7in1_Potasium[mg/kg]": "potassium_mg_kg",
    "7in1_Ph[pH]": "soil_ph",
    "7in1_Moisture[%RH]": "soil_moisture_pct",
    "7in1_S_Temperature[C]": "soil_temperature_c",
    "7in1_EC[uS/cm]": "soil_ec_us_cm",
    "Pynamometer_Radiation[W/m2]": "solar_radiation_w_m2",
    "Air_sensor_Temperature[C]": "air_temperature_c",
    "Air_sensor_Humidity[%RH]": "relative_humidity_pct",
    "Number of Flowers": "flower_count",
    "Number of Fruits": "fruit_count",
    "Numer of Harvested Fruits": "harvested_fruit_count",
    "Total Weight of Harvest Fruits": "total_harvest_weight_source_unit",
}

PROVISIONAL_MOISTURE_RANGES = {
    "Tomato": [40.0, 70.0],
    "Okra": [45.0, 75.0],
    "Basella alba": [55.0, 85.0],
    "Potato": [50.0, 75.0],
    "Rice": [70.0, 95.0],
    "Corn": [45.0, 70.0],
    "Eggplant": [50.0, 75.0],
    "Cucumber": [60.0, 85.0],
    "Cabbage": [60.0, 85.0],
    "Sweet Potato": [45.0, 70.0],
    "Lettuce": [60.0, 85.0],
    "Spinach": [60.0, 85.0],
}


def clean_mobile_tomato(source: Path, destination: Path) -> int:
    raw = pd.read_csv(source, sep=";", low_memory=False)
    missing_columns = [
        column
        for column in [*OUTPUT_COLUMN_MAP, *REQUIRED_TOMATO_SENSOR_COLUMNS]
        if column not in raw.columns
    ]
    if missing_columns:
        raise ValueError(f"Missing expected tomato columns: {missing_columns}")

    complete = raw.dropna(subset=REQUIRED_TOMATO_SENSOR_COLUMNS).copy()
    complete = complete[list(OUTPUT_COLUMN_MAP)].rename(columns=OUTPUT_COLUMN_MAP)
    complete["date"] = pd.to_datetime(
        complete["date"], dayfirst=True, errors="raise"
    ).dt.strftime("%Y-%m-%d")
    complete["crop"] = complete["crop"].replace({"Tomate": "Tomato"})

    numeric_columns = [
        column
        for column in complete.columns
        if column not in {"date", "crop", "treatment"}
    ]
    for column in numeric_columns:
        complete[column] = pd.to_numeric(complete[column], errors="coerce")

    sensor_zero_columns = [
        "nitrogen_mg_kg",
        "phosphorus_mg_kg",
        "potassium_mg_kg",
        "soil_moisture_pct",
        "soil_ec_us_cm",
    ]

    def quality_flag(row: pd.Series) -> str:
        flags: list[str] = []
        if any(row[column] == 0 for column in sensor_zero_columns):
            flags.append("zero_sensor_value")
        if row["relative_humidity_pct"] < 10:
            flags.append("very_low_air_humidity")
        return "|".join(flags) if flags else "ok"

    complete["quality_flag"] = complete.apply(quality_flag, axis=1)
    complete = complete.sort_values(["date", "plant_id"], kind="stable")

    # The published file contains exactly 693 rows where every listed sensor field
    # is present. Fail loudly if a different source version changes that contract.
    if len(complete) != 693:
        raise ValueError(f"Expected 693 complete sensor rows, found {len(complete)}")

    destination.parent.mkdir(parents=True, exist_ok=True)
    complete.to_csv(destination, index=False, encoding="utf-8", lineterminator="\n")
    return len(complete)


def _parse_requirement(value: str) -> list[float]:
    parts = [float(part.strip()) for part in str(value).split("-")]
    return [parts[0], parts[-1]]


def select_crop_requirements(source: Path, destination: Path) -> int:
    raw_profiles = json.loads(source.read_text(encoding="utf-8"))
    wanted = {
        "Tomato", "Okra", "Basella alba", "Potato", "Rice", "Corn",
        "Eggplant", "Cucumber", "Cabbage", "Sweet Potato", "Lettuce", "Spinach",
    }
    selected = []

    for profile in raw_profiles:
        crop = profile.get("Crop")
        if crop not in wanted:
            continue
        npk = profile["NPK Requirements"]
        additional = profile["Additional Requirements"]
        temperature_key = next(key for key in additional if key.startswith("Temperature"))
        selected.append(
            {
                "name": "Alugbati" if crop == "Basella alba" else crop,
                "sourceCropName": crop,
                "scientificName": profile["Scientific Name"],
                "ranges": {
                    "nitrogen": _parse_requirement(npk["Nitrogen"]),
                    "phosphorus": _parse_requirement(npk["Phosphorus"]),
                    "potassium": _parse_requirement(npk["Potassium"]),
                    "temperatureC": _parse_requirement(additional[temperature_key]),
                    "humidityPct": _parse_requirement(additional["Humidity (%)"]),
                    "soilPh": _parse_requirement(additional["pH"]),
                    "rainfallMm": _parse_requirement(additional["Rainfall (mm)"]),
                    "soilMoisturePct": PROVISIONAL_MOISTURE_RANGES[crop],
                },
            }
        )

    if {item["sourceCropName"] for item in selected} != wanted:
        raise ValueError("The requirements source does not contain all target crops")

    selected.sort(
        key=lambda item: [
            "Tomato", "Okra", "Alugbati", "Potato", "Rice", "Corn",
            "Eggplant", "Cucumber", "Cabbage", "Sweet Potato", "Lettuce", "Spinach",
        ].index(item["name"])
    )
    output = {
        "source": "data/raw/Crop_Requirements.txt",
        "npkUnit": "not specified by source; verify compatibility with mg/kg sensor readings",
        "soilMoistureNote": (
            "Provisional CropCast sensor-calibration ranges; do not treat rainfall as "
            "a conversion to soil-moisture percentage. Replace after local calibration."
        ),
        "lightNote": (
            "Light is intentionally excluded from recommendation scoring because the "
            "tomato dataset reports W/m2 while CropCast reports lux."
        ),
        "crops": selected,
    }
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_text(json.dumps(output, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return len(selected)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--project-root", type=Path, default=Path(__file__).resolve().parents[1])
    args = parser.parse_args()
    root = args.project_root.resolve()

    tomato_rows = clean_mobile_tomato(
        root / "data/raw/DB_Mobile_Manual_Tomato.csv",
        root / "data/processed/mobile_tomato_complete.csv",
    )
    crop_count = select_crop_requirements(
        root / "data/raw/Crop_Requirements.txt",
        root / "data/processed/crop_requirements_selected.json",
    )
    print(f"Prepared {tomato_rows} complete tomato rows and {crop_count} crop profiles")


if __name__ == "__main__":
    main()
