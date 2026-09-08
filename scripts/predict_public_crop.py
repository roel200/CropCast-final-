"""Run a prediction with the trained public crop recommendation model."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import joblib
import pandas as pd


PROJECT_ROOT = Path(__file__).resolve().parents[1]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Predict crops from seven measurements.")
    parser.add_argument(
        "--model",
        type=Path,
        default=PROJECT_ROOT / "models/public_crop/crop_recommendation.joblib",
    )
    parser.add_argument("--nitrogen", type=float, required=True)
    parser.add_argument("--phosphorus", type=float, required=True)
    parser.add_argument("--potassium", type=float, required=True)
    parser.add_argument("--temperature", type=float, required=True)
    parser.add_argument("--humidity", type=float, required=True)
    parser.add_argument("--ph", type=float, required=True)
    parser.add_argument("--rainfall", type=float, required=True)
    parser.add_argument("--top", type=int, default=3)
    return parser.parse_args()


def main() -> None:
    args = parse_args()
    artifact = joblib.load(args.model.resolve())
    model = artifact["model"]
    feature_names = artifact["features"]
    values = {
        "N": args.nitrogen,
        "P": args.phosphorus,
        "K": args.potassium,
        "temperature": args.temperature,
        "humidity": args.humidity,
        "ph": args.ph,
        "rainfall": args.rainfall,
    }
    row = pd.DataFrame(
        [[values[name] for name in feature_names]], columns=feature_names
    )
    probabilities = model.predict_proba(row)[0]
    ranked = sorted(
        zip(model.classes_, probabilities, strict=True),
        key=lambda item: item[1],
        reverse=True,
    )
    top = max(1, min(args.top, len(ranked)))
    result = {
        "model_version": artifact["model_version"],
        "predictions": [
            {"crop": crop, "model_score": round(float(score), 4)}
            for crop, score in ranked[:top]
        ],
        "limitations": artifact["limitations"],
    }
    print(json.dumps(result, indent=2))


if __name__ == "__main__":
    main()
