"""Train and export the six-input public crop model used by the Android app."""

from __future__ import annotations

import hashlib
import json
import struct
from datetime import datetime, timezone
from pathlib import Path
from typing import BinaryIO

import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, f1_score, top_k_accuracy_score
from sklearn.model_selection import train_test_split


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DATASET_PATH = PROJECT_ROOT / "data/raw/Crop_recommendation.csv"
BASE_ASSET_PATH = PROJECT_ROOT / "app/src/main/assets/public_crop_forest.bin"
RAINFALL_ASSET_PATH = PROJECT_ROOT / "app/src/main/assets/public_crop_forest_rainfall.bin"
METRICS_PATH = PROJECT_ROOT / "models/public_crop/android_metrics.json"
BASE_FEATURES = ["N", "P", "K", "temperature", "humidity", "ph"]
RAINFALL_FEATURES = BASE_FEATURES + ["rainfall"]
TARGET = "label"
MAGIC = b"CCRF0001"
RANDOM_STATE = 42
TREE_COUNT = 100


def write_int(stream: BinaryIO, value: int) -> None:
    stream.write(struct.pack(">i", value))


def write_string(stream: BinaryIO, value: str) -> None:
    encoded = value.encode("utf-8")
    write_int(stream, len(encoded))
    stream.write(encoded)


def export_forest(
    model: RandomForestClassifier,
    destination: Path,
    features: list[str],
) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    with destination.open("wb") as stream:
        stream.write(MAGIC)
        write_int(stream, len(features))
        for feature in features:
            write_string(stream, feature)
        write_int(stream, len(model.classes_))
        for crop in model.classes_:
            write_string(stream, str(crop))
        write_int(stream, len(model.estimators_))

        for estimator in model.estimators_:
            tree = estimator.tree_
            write_int(stream, tree.node_count)
            for node_index in range(tree.node_count):
                feature = int(tree.feature[node_index])
                write_int(stream, feature)
                stream.write(struct.pack(">d", float(tree.threshold[node_index])))
                write_int(stream, int(tree.children_left[node_index]))
                write_int(stream, int(tree.children_right[node_index]))
                if feature < 0:
                    values = tree.value[node_index][0]
                    total = float(values.sum())
                    probabilities = values / total if total else values
                    stream.write(
                        struct.pack(
                            f">{len(probabilities)}f",
                            *(float(value) for value in probabilities),
                        )
                    )


def new_model() -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=TREE_COUNT,
        random_state=RANDOM_STATE,
        class_weight="balanced_subsample",
        n_jobs=-1,
    )


def main() -> None:
    data = pd.read_csv(DATASET_PATH)
    y = data[TARGET].astype("string").str.strip().str.lower()
    if y.isna().any() or y.eq("").any():
        raise ValueError("The public crop dataset contains missing values")
    variants = [
        ("without_rainfall", BASE_FEATURES, BASE_ASSET_PATH),
        ("with_weather_rainfall", RAINFALL_FEATURES, RAINFALL_ASSET_PATH),
    ]
    variant_metrics = {}
    for variant_name, features, asset_path in variants:
        x = data[features].apply(pd.to_numeric, errors="raise")
        if x.isna().any().any():
            raise ValueError("The public crop dataset contains missing values")
        x_train, x_test, y_train, y_test = train_test_split(
            x,
            y,
            test_size=0.20,
            random_state=RANDOM_STATE,
            stratify=y,
        )
        evaluation_model = new_model()
        evaluation_model.fit(x_train, y_train)
        predictions = evaluation_model.predict(x_test)
        probabilities = evaluation_model.predict_proba(x_test)

        final_model = new_model()
        final_model.fit(x, y)
        export_forest(final_model, asset_path, features)
        variant_metrics[variant_name] = {
            "features": features,
            "tree_count": TREE_COUNT,
            "train_rows_for_evaluation": len(x_train),
            "test_rows": len(x_test),
            "held_out_accuracy": accuracy_score(y_test, predictions),
            "held_out_macro_f1": f1_score(y_test, predictions, average="macro"),
            "held_out_top3_accuracy": top_k_accuracy_score(
                y_test,
                probabilities,
                k=3,
                labels=evaluation_model.classes_,
            ),
            "final_model_training_rows": len(x),
            "asset": str(asset_path.relative_to(PROJECT_ROOT)),
            "asset_size_bytes": asset_path.stat().st_size,
        }

    metrics = {
        "model_version": "android-public-crop-random-forest-v1",
        "trained_at_utc": datetime.now(timezone.utc).isoformat(),
        "dataset": str(DATASET_PATH.relative_to(PROJECT_ROOT)),
        "dataset_sha256": hashlib.sha256(DATASET_PATH.read_bytes()).hexdigest(),
        "row_count": len(data),
        "class_count": int(y.nunique()),
        "classes": sorted(y.unique().tolist()),
        "variants": variant_metrics,
        "limitations": [
            "Weather rainfall is a 30-day location estimate and is not a field rain gauge measurement.",
            "The six-input asset remains available when weather rainfall cannot be obtained.",
            "Metrics measure only a held-out portion of the public India-derived dataset.",
            "Model scores are Random Forest vote scores, not calibrated success probabilities.",
            "Philippine field accuracy has not been established.",
        ],
    }
    METRICS_PATH.parent.mkdir(parents=True, exist_ok=True)
    METRICS_PATH.write_text(
        json.dumps(metrics, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    for variant_name, result in variant_metrics.items():
        print(
            f"{variant_name}: accuracy={result['held_out_accuracy']:.4f}, "
            f"macro_f1={result['held_out_macro_f1']:.4f}, "
            f"top3={result['held_out_top3_accuracy']:.4f}"
        )
        print(f"Asset: {result['asset']} ({result['asset_size_bytes']:,} bytes)")


if __name__ == "__main__":
    main()
