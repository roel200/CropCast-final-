# CropCast data layout

`raw/` contains byte-for-byte copies of the downloaded source files. Do not edit
these files. `processed/` contains reproducible outputs created by
`scripts/prepare_cropcast_data.py`.

## Processed outputs

- `mobile_tomato_complete.csv` keeps the 693 rows in the mobile/manual tomato
  source where all 7-in-1, radiation, air-temperature, and air-humidity sensor
  fields are present. Headers are normalized and a non-destructive
  `quality_flag` identifies zero sensor values and extremely low air humidity.
- `crop_requirements_selected.json` extracts the 12 crops supported by CropCast,
  including Basella alba (displayed as Alugbati), from the downloaded
  crop-requirements file.

## Scientific limitations

- The crop-requirements source does not state the NPK unit. Its numeric ranges
  must not be claimed as calibrated `mg/kg` thresholds until they are checked
  against the RS485 probe and a laboratory soil test.
- Soil-moisture percentages depend on sensor and soil calibration. The ranges in
  the processed crop table are explicit prototype defaults and must be replaced
  after local calibration trials.
- Solar radiation (`W/m2`) and illuminance (`lux`) are retained as different
  variables and are never directly merged or converted by this pipeline.
- The source datasets do not provide labeled outcomes for all 12 supported crops
  under one common schema that includes soil moisture. They are supporting
  references, not a valid 12-class machine-learning training set.

## Rebuild

Run with the bundled or project Python environment containing pandas:

```powershell
python scripts/prepare_cropcast_data.py
```
