# Public crop model experiment

This model is trained from `data/raw/Crop_recommendation.csv`. The source has
2,200 labeled rows for 22 crop classes and uses nitrogen, phosphorus, potassium,
temperature, humidity, pH, and rainfall as inputs.

This experiment is separate from CropCast's 12-crop rule engine. The public
dataset overlaps with that list only for rice and maize (displayed as Corn in
CropCast), does not include soil moisture, and does not establish field accuracy
in the Philippines. The returned model scores must not be described as verified
probabilities of crop success.

Create an isolated environment and train:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r requirements-ml.txt
.venv/bin/python scripts/train_public_crop_model.py
```

Run an example prediction:

```bash
.venv/bin/python scripts/predict_public_crop.py \
  --nitrogen 90 --phosphorus 42 --potassium 43 \
  --temperature 20.88 --humidity 82.0 --ph 6.5 --rainfall 203
```

The training command writes `crop_recommendation.joblib` and `metrics.json` in
this directory. Only load the Joblib artifact from a trusted source.

## Android model

CropCast has no rainfall sensor, so the Android app uses a separate Random
Forest trained from the same public CSV with six inputs: N, P, K, temperature,
humidity, and pH. Rebuild its compact on-device asset with:

```bash
.venv/bin/python scripts/train_android_public_crop_model.py
```

This writes `app/src/main/assets/public_crop_forest.bin` and
`models/public_crop/android_metrics.json`. The Android evaluation metrics are
kept separate from the seven-input Python model metrics.

Run the five-fold test, where every public CSV row is held out once, with:

```bash
.venv/bin/python scripts/evaluate_android_public_crop_model.py
```

The detailed fold, per-crop recall, and misclassification results are written to
`models/public_crop/cross_validation_metrics.json`.
