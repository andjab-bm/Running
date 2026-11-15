# How the figures are computed

Every number shown for a run comes from one pipeline in `server/src/pipeline/`: `parse → normalise → derive → summarise`. It is pure computation, so it can be re-run when an algorithm or a profile setting changes; `METRICS_VERSION` (currently 1) is bumped whenever results would differ.

The figures are estimates. They will not match Strava or Garmin to the second, because each product smooths and thresholds differently.

## 1. Reading the file

- **Format** is decided from the content, never the file name: a FIT file has `.FIT` at byte 8; a GPX file is XML containing a `<gpx>` element.
- **GPX**: each `<trk>` becomes one activity. Per point we read position, `<ele>`, `<time>` and, from any extension namespace, `hr`/`heartrate` and `cad`/`cadence`/`runcadence`. A track whose points have no time is rejected as a route.
- **FIT**: `record` messages give the samples, `event` messages of type `timer` give the pauses (each stop followed by a start), `lap` messages give lap start times, `session` gives the sport. A final stop with no restart is the end of the run, not a pause.
- **Partial records are merged.** Strava on Android writes several records for the same instant (one with the position, one with the distance, one with the cadence). Records sharing a timestamp are merged, keeping the last value of each channel.

## 2. Normalising

- Samples are sorted by time. Positions outside valid ranges, or exactly (0, 0), are discarded.
- **Distance**: if at least `MIN_DEVICE_DIST_COVERAGE` (80 %) of samples carry the device's own cumulative distance, it is used (forced to be non-decreasing). Otherwise distance is the sum of great-circle (haversine) steps between consecutive positions, on a sphere of radius `EARTH_RADIUS_M` (6 371 000 m).
- **Stepped distances are interpolated.** Strava on Android refreshes the distance only every other second, which would make speed oscillate. A distance that stays flat for at most `DIST_PLATEAU_MAX_S` (8 s) is spread linearly over the samples it covers; a longer plateau is treated as a real stop.
- **Cadence** is expressed in steps per minute for both legs. Devices usually store one leg; when the median non-zero cadence is under `PER_LEG_CADENCE_BELOW` (120), all values are doubled.

## 3. Moving time

Elapsed time is last sample minus first sample. Moving time is the sum of the intervals between samples that count as moving.

- **When the file records pauses**, an interval is paused if more than half of it falls inside a recorded pause. Nothing else is inferred: if you stood still without stopping the timer, that time counts as moving, as it does on the device.
- **Otherwise** pauses are detected:
  - a gap longer than `GAP_PAUSE_S` (30 s) between two samples is a pause;
  - a run of consecutive intervals slower than `STOP_SPEED_MPS` (1.5 km/h) lasting at least `STOP_MIN_S` (10 s) is a pause. Shorter slowdowns stay in moving time.

**Speed** at each sample is distance over moving time within ±`SPEED_WINDOW_S` (5 s). Pace is its inverse. Average pace is total distance over moving time.

## 4. Elevation

Used only when at least `MIN_ELE_COVERAGE` (50 %) of samples have an elevation; otherwise gain, loss and grade-adjusted pace are unavailable.

1. Missing values are filled from the previous value.
2. A median of five samples removes spikes.
3. A mean over ±`ELE_WINDOW_M` (30 m) of distance smooths sensor noise.
4. **Gain and loss** use hysteresis: elevation must move `ELE_HYSTERESIS_M` (2 m) away from the last reference before the change is counted. On flat ground with ±1 m of noise this yields under 5 m per 10 km.

Phone GPS altitude is poor; on flat terrain expect a few metres of spurious gain.

## 5. Grade-adjusted pace

The slope of each interval is the smoothed elevation change over ±`GRADE_WINDOW_M` (15 m), clamped to ±`GRADE_CLAMP` (30 %). Each interval's distance is multiplied by the relative energy cost of running on that slope (Minetti et al., 2002):

```
C(g) = 155.4·g⁵ − 30.4·g⁴ − 43.3·g³ + 46.3·g² + 19.5·g + 3.6      factor = C(g) / 3.6
```

Grade-adjusted speed is the adjusted distance over moving time. On flat ground the factor is 1; at +5 % it is about 1.30.

## 6. Splits and laps

- **Splits** are consecutive stretches of one unit (1 km, or 1 609.344 m with imperial units) plus a final partial one; a trailing remainder under `MIN_SPLIT_M` (1 m) is dropped. Boundaries fall inside an interval between two samples, so that interval's time, distance and climb are shared proportionally. Split distances therefore add up to the total.
- **Laps** start at the lap start times found in the file and end where the next one starts. Lap durations written by the device are ignored: some exports store the export time there. A file without laps has one lap covering the whole run.
- Each split or lap reports distance, moving and elapsed time, speed, grade-adjusted speed, gain, loss, and heart rate and cadence averaged over moving time.

## 7. Heart-rate zones and relative effort

Five zones are given by six bounds; by default 50–60–70–80–90–100 % of the profile's maximum heart rate. Values under zone 1 count as zone 1 and values over the maximum as zone 5.

- **Time in zone** sums moving intervals that have a heart-rate value, so the five times add up to the moving time for which heart rate was recorded.
- **Relative effort** = Σ (minutes in zone × zone number), the Edwards training impulse. It doubles when duration doubles and rises with intensity. It is unavailable without heart rate.

## 8. Reduced-resolution streams

Charts use at most `LOW_RES_POINTS` (2 000) points per series. The points are evenly spaced samples, the same indices for every series so they stay aligned, and the first and last samples are always kept. Even spacing is deliberate: shape-preserving reductions favour local extremes, which exaggerates GPS noise on a pace curve.

The web app smooths the pace curve further for display (a mean over about 100 m); summary figures, splits and laps never use the reduced or smoothed series.

## Test data

- **Synthetic runs** built in `server/tests/pipeline.rs` cover each scenario of the `activity-metrics` spec (pauses, climbs, splits, laps, zones, effort, cadence, downsampling) and GPX reading, rejection and export.
- **Real exports** in `testdata/` are private and not committed. When present, a test checks that the computed distance and moving time of each file agree with the totals the file itself declares (within 1.5 % and 2 %). On the five Strava exports from a Pixel 8 used during development they agree to within 2 s and 10 m.
- **Not yet covered by a real file**: FIT files with several laps or with heart rate (the available exports have one lap and no heart rate), and FIT files without elevation.
