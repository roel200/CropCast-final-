"""Run repeatable five-fold tests for the Android public crop model design."""

from __future__ import annotations

import json
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, f1_score, recall_score, top_k_accuracy_score
from sklearn.model_selection import StratifiedKFold


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DATASET_PATH = PROJECT_ROOT / "data/raw/Crop_recommendation.csv"
OUTPUT_PATH = PROJECT_ROOT / "models/public_crop/cross_validation_metrics.json"
FEATURES = ["N", "P", "K", "temperature", "humidity", "ph"]
RANDOM_STATE = 42


def new_model() -> RandomForestClassifier:
    return RandomForestClassifier(
        n_estimators=100,
        random_state=RANDOM_STATE,
        class_weight="balanced_subsample",
        n_jobs=-1,
    )


def main() -> None:
    data = pd.read_csv(DATASET_PATH)
    x = data[FEATURES].apply(pd.to_numeric, errors="raise")
    y = data["label"].astype("string").str.strip().str.lower()
    classes = sorted(y.unique().tolist())
    class_index = {label: index for index, label in enumerate(classes)}
    folds = StratifiedKFold(n_splits=5, shuffle=True, random_state=RANDOM_STATE)

    out_of_fold_predictions = np.empty(len(y), dtype=object)
    out_of_fold_probabilities = np.zeros((len(y), len(classes)), dtype=float)
    fold_results = []

    for fold_number, (train_indices, test_indices) in enumerate(folds.split(x, y), start=1):
        model = new_model()
        model.fit(x.iloc[train_indices], y.iloc[train_indices])
        predictions = model.predict(x.iloc[test_indices])
        probabilities = model.predict_proba(x.iloc[test_indices])
        out_of_fold_predictions[test_indices] = predictions
        for model_index, label in enumerate(model.classes_):
            out_of_fold_probabilities[test_indices, class_index[label]] = probabilities[:, model_index]
        fold_results.append(
            {
                "fold": fold_number,
                "train_rows": len(train_indices),
                "test_rows": len(test_indices),
                "accuracy": accuracy_score(y.iloc[test_indices], predictions),
                "macro_f1": f1_score(y.iloc[test_indices], predictions, average="macro"),
            }
        )

    fold_accuracies = [result["accuracy"] for result in fold_results]
    per_crop_recall = recall_score(
        y,
        out_of_fold_predictions,
        labels=classes,
        average=None,
        zero_division=0,
    )
    confusion_pairs = Counter(
        (expected, predicted)
        for expected, predicted in zip(y, out_of_fold_predictions, strict=True)
        if expected != predicted
    )
    report = {
        "model_design": "android-public-crop-random-forest-v1",
        "evaluated_at_utc": datetime.now(timezone.utc).isoformat(),
        "protocol": "5-fold stratified cross-validation; every row is tested out-of-sample once",
        "features": FEATURES,
        "row_count": len(y),
        "class_count": len(classes),
        "folds": fold_results,
        "mean_fold_accuracy": float(np.mean(fold_accuracies)),
        "standard_deviation_fold_accuracy": float(np.std(fold_accuracies)),
        "minimum_fold_accuracy": min(fold_accuracies),
        "maximum_fold_accuracy": max(fold_accuracies),
        "out_of_fold_accuracy": accuracy_score(y, out_of_fold_predictions),
        "out_of_fold_macro_f1": f1_score(y, out_of_fold_predictions, average="macro"),
        "out_of_fold_top3_accuracy": top_k_accuracy_score(
            y,
            out_of_fold_probabilities,
            k=3,
            labels=classes,
        ),
        "per_crop_recall": dict(zip(classes, per_crop_recall, strict=True)),
        "misclassification_pairs": [
            {"expected": expected, "predicted": predicted, "count": count}
            for (expected, predicted), count in confusion_pairs.most_common()
        ],
        "limitations": [
            "Cross-validation tests consistency within the public dataset, not Philippine field accuracy.",
            "The Android model excludes rainfall because CropCast has no verified rainfall input.",
        ],
    }
    OUTPUT_PATH.write_text(
        json.dumps(report, indent=2, ensure_ascii=False) + "\n",
        encoding="utf-8",
    )
    print("Fold accuracies:", ", ".join(f"{value:.4f}" for value in fold_accuracies))
    print(f"Out-of-fold accuracy: {report['out_of_fold_accuracy']:.4f}")
    print(f"Out-of-fold macro F1: {report['out_of_fold_macro_f1']:.4f}")
    print(f"Out-of-fold top-3 accuracy: {report['out_of_fold_top3_accuracy']:.4f}")
    print(f"Lowest per-crop recall: {min(per_crop_recall):.4f}")
    print(f"Report: {OUTPUT_PATH}")


if __name__ == "__main__":
    main()
