"""Train and evaluate the public 22-crop recommendation experiment."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path

import joblib
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import (
    accuracy_score,
    classification_report,
    confusion_matrix,
    f1_score,
    top_k_accuracy_score,
)
from sklearn.model_selection import train_test_split


PROJECT_ROOT = Path(__file__).resolve().parents[1]
FEATURES = ["N", "P", "K", "temperature", "humidity", "ph", "rainfall"]
TARGET = "label"
MODEL_VERSION = "public-crop-random-forest-v1"


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Train a Random Forest on the public crop recommendation CSV."
    )
    parser.add_argument(
        "--dataset",
        type=Path,
        default=PROJECT_ROOT / "data/raw/Crop_recommendation.csv",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=PROJECT_ROOT / "models/public_crop",
    )
    parser.add_argument("--test-size", type=float, default=0.20)
    parser.add_argument("--random-state", type=int, default=42)
    return parser.parse_args()


def load_dataset(path: Path) -> tuple[pd.DataFrame, pd.Series]:
    data = pd.read_csv(path)
    missing_columns = [name for name in [*FEATURES, TARGET] if name not in data]
    if missing_columns:
        raise ValueError(f"Dataset is missing required columns: {missing_columns}")

    features = data[FEATURES].apply(pd.to_numeric, errors="coerce")
    labels = data[TARGET].astype("string").str.strip().str.lower()
    invalid_rows = features.isna().any(axis=1) | labels.isna() | labels.eq("")
    if invalid_rows.any():
        row_numbers = (invalid_rows[invalid_rows].index + 2).tolist()[:10]
        raise ValueError(f"Invalid or missing data at CSV rows: {row_numbers}")

    class_counts = labels.value_counts()
    if len(class_counts) < 2 or class_counts.min() < 2:
        raise ValueError("Training requires at least two labels and two rows per label")
    return features, labels


def main() -> None:
    args = parse_args()
    if not 0.05 <= args.test_size <= 0.50:
        raise ValueError("--test-size must be between 0.05 and 0.50")

    dataset_path = args.dataset.resolve()
    output_dir = args.output_dir.resolve()
    features, labels = load_dataset(dataset_path)

    x_train, x_test, y_train, y_test = train_test_split(
        features,
        labels,
        test_size=args.test_size,
        random_state=args.random_state,
        stratify=labels,
    )
    model = RandomForestClassifier(
        n_estimators=400,
        random_state=args.random_state,
        class_weight="balanced_subsample",
        n_jobs=-1,
    )
    model.fit(x_train, y_train)

    predictions = model.predict(x_test)
    probabilities = model.predict_proba(x_test)
    accuracy = accuracy_score(y_test, predictions)
    macro_f1 = f1_score(y_test, predictions, average="macro")
    weighted_f1 = f1_score(y_test, predictions, average="weighted")
    top3_accuracy = top_k_accuracy_score(
        y_test,
        probabilities,
        k=3,
        labels=model.classes_,
    )
    dataset_sha256 = hashlib.sha256(dataset_path.read_bytes()).hexdigest()

    output_dir.mkdir(parents=True, exist_ok=True)
    model_path = output_dir / "crop_recommendation.joblib"
    metrics_path = output_dir / "metrics.json"

    artifact = {
        "artifact_version": 1,
        "model_version": MODEL_VERSION,
        "features": FEATURES,
        "target": TARGET,
        "classes": model.classes_.tolist(),
        "dataset_sha256": dataset_sha256,
        "model": model,
        "limitations": (
            "Prototype trained on an India-derived public dataset. It excludes "
            "soil moisture and most crops currently supported by CropCast."
        ),
    }
    joblib.dump(artifact, model_path)

    report = classification_report(
        y_test,
        predictions,
        labels=model.classes_,
        output_dict=True,
        zero_division=0,
    )
    metrics = {
        "model_version": MODEL_VERSION,
        "trained_at_utc": datetime.now(timezone.utc).isoformat(),
        "dataset": str(dataset_path.relative_to(PROJECT_ROOT)),
        "dataset_sha256": dataset_sha256,
        "features": FEATURES,
        "row_count": len(features),
        "class_count": len(model.classes_),
        "classes": model.classes_.tolist(),
        "train_rows": len(x_train),
        "test_rows": len(x_test),
        "test_size": args.test_size,
        "random_state": args.random_state,
        "held_out_accuracy": accuracy,
        "held_out_macro_f1": macro_f1,
        "held_out_weighted_f1": weighted_f1,
        "held_out_top3_accuracy": top3_accuracy,
        "feature_importance": dict(
            sorted(
                zip(FEATURES, model.feature_importances_, strict=True),
                key=lambda item: item[1],
                reverse=True,
            )
        ),
        "classification_report": report,
        "confusion_matrix": confusion_matrix(
            y_test, predictions, labels=model.classes_
        ).tolist(),
        "limitations": [
            "Metrics measure performance only on a held-out portion of this public dataset.",
            "The data is described as augmented from Indian climate and fertilizer data.",
            "Rainfall is required; soil moisture is not an input.",
            "Only rice and maize overlap with CropCast's current 12-crop list.",
            "Field accuracy in the Philippines has not been established.",
        ],
    }
    metrics_path.write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )

    print(f"Trained {MODEL_VERSION} on {len(x_train):,} rows")
    print(f"Evaluated on {len(x_test):,} held-out rows")
    print(f"Accuracy: {accuracy:.4f}")
    print(f"Macro F1: {macro_f1:.4f}")
    print(f"Top-3 accuracy: {top3_accuracy:.4f}")
    print(f"Model: {model_path}")
    print(f"Metrics: {metrics_path}")


if __name__ == "__main__":
    main()
