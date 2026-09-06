# CropCast Monthly Crop Recommendation Plan

## Goal

Extend CropCast so that every eligible closed month with enough valid sensor data receives:

1. A recommendation for that completed month based on its measured conditions.
2. A recommendation for the next month based on the latest month and available historical months.
3. A saved history that lets the farmer review recommendations and compare forecasts with later results.

The recommendation is a suitability recommendation, not a guaranteed yield prediction. The app now collects planted crop, harvest weight, rating, and problem notes. After at least three valid ratings for a crop, the engine applies only a conservative local score adjustment; harvest weight remains evaluation data until field area and yield units are normalized.

The saved recommendation history, next-month forecast, variability details, seasonal advisory, and farm-outcome collection described below are implemented in the current app.

## Current project baseline

- Firebase stores readings at `devices/{deviceId}/readings/monthly/{yyyy-MM}`.
- `MonthlySensorAggregator` already averages valid readings for each month.
- `SeedRecommendationEngine` currently scores 12 crops using temperature, humidity, soil moisture, pH, nitrogen, phosphorus, and potassium.
- `CropCastUiState` already keeps all Firebase monthly summaries, but `SeedsScreen` currently displays only the latest available month.
- Demo mode already generates 12 months with 8 readings per month; the history UI should expose that existing data instead of creating a separate demo path.
- Crop ranges come from `data/processed/crop_requirements_selected.json`.
- `data/processed/mobile_tomato_complete.csv` and the other files under `data/raw` are offline/reference datasets. They are not currently loaded by the Android runtime or joined to Firebase readings.
- The NPK unit compatibility and moisture ranges are still provisional and must remain visible in the recommendation explanation.
- Light should remain display-only until the lux and `W/m2` datasets can be calibrated against each other.

## Recommendation definitions

### Completed-month recommendation

For a month `M`:

1. Load all valid readings under `readings/monthly/M`.
2. Aggregate them into one `MonthlySensorSummary`.
3. Score every crop against the monthly average.
4. Sort by score, using the fixed crop-profile order already defined in `SeedRecommendationEngine` as the tie-breaker.
5. Save the top crop, score, runner-up, sample count, and score breakdown for `M`.

This answers: **“Which crop best matched the conditions recorded during this month?”**

### Next-month recommendation

When month `M` is complete, produce a forecast for `M+1`:

1. Use the latest complete month as the strongest signal.
2. Include the average sensor profile for the same calendar month in previous years when available.
3. Include the weighted average sensor profile of the three complete months before the latest month to reduce one-month sensor noise without double-counting the latest month.
4. Blend those sensor profiles field-by-field, then score the resulting forecast sensor profile against every crop profile.
5. Save the top crop as the recommendation for `M+1`, clearly labelled as a forecast.

Use these default weights for the forecast:

| Signal | Weight |
|---|---:|
| Latest complete month | 60% |
| Same calendar month in prior years | 25% |
| Three complete months before the latest | 15% |

If a signal is unavailable, redistribute its weight across the available signals and record which signals were used. If there is only one valid month, use that month as the forecast baseline and lower the confidence label to `Limited history`.

For the first implementation, define a complete month as a `yyyy-MM` month before the current UTC month. Later, support an explicit farmer/admin close action for locations where the growing calendar does not follow UTC calendar months. A current-month record may be shown as `Month to date`, but it must not be presented as a closed-month result.

If there is no eligible closed month, do not forecast a crop. Show `Insufficient history` and the number of valid readings still needed.

## Scoring rules

Keep the existing range-based scoring as the first implementation:

```text
field score = 100 when the value is inside the crop range
field score decreases by distance outside the range
crop score = average of the seven field scores
```

The seven scored fields are temperature, humidity, soil moisture, soil pH, nitrogen, phosphorus, and potassium. Keep the score between 0 and 100.

Keep the current `SeedRecommendation.confidence` value as the suitability score for backward compatibility, but add a separate confidence label. Start with these deterministic labels:

- `High confidence`: score at least 80, at least 8 valid readings, and a top-crop margin of at least 10 points over the runner-up.
- `Moderate confidence`: score at least 60 but the high-confidence conditions are not met.
- `Low confidence`: score below 60.
- `Limited history`: fewer than two eligible historical months are available for a forecast, even when the suitability score is high.

Each recommendation should include:

- Crop name, variety, soil type, and expected growing days.
- Suitability score and confidence label.
- Sample count and date range used.
- The three strongest matching fields.
- The fields that reduced the score.
- A warning when NPK units or moisture calibration are still provisional.
- Whether it is an `Observed month` recommendation or a `Next month forecast`.

## Proposed data model

Add a model similar to:

```kotlin
data class MonthlyCropRecommendation(
    val monthKey: String,
    val cropName: String,
    val cropVariety: String,
    val cropIcon: String,
    val growDays: Int,
    val soil: String,
    val score: Int,
    val runnerUpName: String = "",
    val runnerUpScore: Int = 0,
    val scoreByCrop: Map<String, Int> = emptyMap(),
    val sampleCount: Int = 0,
    val firstReadingAt: Long = 0L,
    val lastReadingAt: Long = 0L,
    val basedOnMonths: List<String> = emptyList(),
    val recommendationType: String, // observed_month or next_month_forecast
    val confidenceLabel: String,
    val matchingFields: List<String> = emptyList(),
    val limitingFields: List<String> = emptyList(),
    val generatedAt: Long = 0L,
    val algorithmVersion: String = "monthly-v1"
)
```

Use this Firebase structure if recommendations are persisted:

```text
/devices/{deviceId}/recommendations/monthly/{yyyy-MM}
  observed/
    recommendedCrop
    score
    sampleCount
    basedOnMonths
    scoreByCrop
    confidenceLabel
    generatedAt
    algorithmVersion
  forecast/
    recommendedCrop
    score
    sampleCount
    basedOnMonths
    confidenceLabel
    generatedAt
    algorithmVersion
```

Store the observed record under the month whose readings were measured. Store a forecast under its target month, and use `basedOnMonths` to identify the earlier data that produced it. This makes it straightforward to compare `/monthly/{target}/forecast` with `/monthly/{target}/observed`.

The app should be able to calculate recommendations locally from `readings/monthly` when the recommendation node is missing. This keeps the feature usable with existing Firebase fixtures and demo mode.

Because the current `firebase/database.rules.json` has no `recommendations` write rule, persistence requires an explicit rules update. Validate the month-key format, crop name, score bounds, sample count, and algorithm version before enabling writes.

The current `SensorReading` data class supplies `0.0` defaults for missing fields, which can make a missing sensor value look like a real zero. Validate required child fields from the Firebase snapshot before converting to `SensorReading`, or introduce a nullable transport model. Do not rely on `0.0` alone to detect missing data.

## Monthly workflow

Run this workflow whenever monthly readings change and when the Seeds screen opens:

1. Read all monthly readings.
2. Ignore readings with an invalid or missing timestamp.
3. Build one summary per `yyyy-MM` month.
4. Mark a month as eligible only when it meets the minimum sample count. Start with `8` samples, matching the generated demo and test fixtures, and make the threshold configurable later. This threshold applies to monthly history/forecast eligibility; keep the low-level engine able to score smaller test summaries.
5. Generate an observed-month recommendation for every eligible month.
6. Generate a next-month forecast from the newest eligible month.
7. Do not overwrite a completed month’s observed recommendation unless the algorithm version changes or new corrected readings are received.
8. Display the newest forecast first, followed by the monthly recommendation history.

The runtime flow should remain:

```text
Firebase observeMonthlyReadings()
        -> MonthlySensorAggregator.summarize()
        -> monthly recommendation/forecast engine
        -> CropCastUiState
        -> SeedsScreen
```

Use `YearMonth` in UTC to calculate `M+1`; never increment the `yyyy-MM` string manually.

Example:

```text
2026-08 sensor readings
        ↓
2026-08 observed recommendation
        ↓
2026-09 next-month forecast
        ↓
When 2026-09 data is complete: compare forecast with 2026-09 observed recommendation
```

## UI changes

Update the Crop Recommendation screen to contain:

1. **Next month recommendation** — the primary card, with the target month and `Forecast` label.
2. **Why this crop** — score, matching fields, limiting fields, data sources, and confidence.
3. **Monthly history** — one row/card for every eligible `yyyy-MM` month when data exists, including the month, recommended crop, score, and sample count. Group by year if more than 12 months are available.
4. **Forecast result** — after a month closes, show whether the previous forecast matched the observed recommendation.
5. **Insufficient data state** — explain that more readings are needed instead of showing a misleading crop.

Do not present the forecast as certain. Use wording such as “Recommended for October based on August–September data.”

## Implementation phases

### Phase 1: Engine and models

- Add `MonthlyCropRecommendation` and a detailed score-result type.
- Refactor `SeedRecommendationEngine` so it can return all crop scores, not only the top crop.
- Add functions for observed-month recommendations and next-month forecasts.
- Add deterministic tie-breaking and confidence calculation.
- Keep the current seven-field scoring behavior unchanged as the baseline.

### Phase 2: State and data flow

- Update `CropCastUiState` to expose all monthly recommendations and the next-month forecast.
- Update `CropCastViewModel` to build recommendations from every monthly summary instead of only `monthlySummary`.
- Add repository read/write methods for persisted recommendations, with local calculation as a fallback.
- Reuse the existing 12-month demo history and add demo assertions for the history and next-month forecast states.

### Phase 3: UI

- Update `SeedsScreen` for the next-month card, explanation, history list, and insufficient-data state.
- Add localized strings for observed recommendation, forecast, confidence, sample count, and forecast comparison.
- Show the recommendation month explicitly to avoid confusing current-month data with next-month advice.

### Phase 4: Data quality and calibration

- Validate timestamp, sample count, duplicate readings, and missing sensor fields.
- Ensure a Firebase month key matches the reading timestamp month, using UTC and `YearMonth` rather than ad hoc string arithmetic.
- Confirm that NPK Firebase readings use the same unit assumptions as the crop requirement ranges.
- Calibrate soil-moisture ranges locally and replace the provisional values.
- Keep light out of scoring until a defensible lux-to-radiation relationship is available.
- Continue evaluating the implemented farm-outcome records against normalized field area and yield units before allowing harvest weight to influence recommendation scores.
- Keep the processed tomato dataset as an offline calibration/evaluation input until a documented mapping to Firebase fields and units exists.

## Tests and acceptance criteria

Add unit tests for:

- One eligible month produces exactly one observed recommendation.
- Multiple months produce one recommendation per month in descending month order.
- The newest eligible month produces a forecast for the following calendar month.
- The current UTC month is treated as month-to-date and is not used as a completed-month result.
- Same-calendar-month history is used when available.
- Missing historical signals redistribute weights without changing the 0–100 score bounds.
- Months below the sample threshold are excluded and do not produce a recommendation.
- Invalid timestamps and incomplete readings are ignored consistently.
- Missing Firebase child fields are not silently converted to valid zero readings.
- Ties are deterministic and follow the existing crop-profile order.
- A forecast can be compared with the later observed recommendation.
- Existing Potato and Rice fixtures still recommend their target crop.

The feature is complete when:

- Every eligible month has an observed crop recommendation.
- The app always shows the next target month for the forecast.
- The forecast explains which past months were used.
- The farmer can review the month-by-month history.
- Low-data and calibration warnings are visible.
- Existing recommendation tests and the full debug unit-test suite pass.

## Suggested delivery order

1. Implement all-score output and monthly recommendation models.
2. Add observed-month and next-month forecast calculations.
3. Add ViewModel state and local fallback behavior.
4. Add Firebase persistence and rules only after local calculations are verified.
5. Build the history and next-month UI.
6. Add tests, demo fixtures, and calibration/data-quality warnings.
