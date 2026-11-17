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

## 9. Figures computed after import

Records, load and segments need more than one activity, or more time than an import should take. They are computed in the background by one worker (`server/src/derive/`): a request commits its own change, then hands the worker a job. An activity whose `derived_version` is older than `DERIVED_VERSION` (currently 1) is waiting for it; that is also how history is caught up after an upgrade. `GET /profiles/{id}/derive-status` says how much is left.

## 10. Best efforts and records

For each run the fastest stretch of 400 m, 1 km, 5 km, 10 km, a half-marathon (21 097.5 m) and a marathon (42 195 m) is searched wherever it lies in the run (`STANDARD_DISTANCES`).

- **Timed on moving time.** Both the distance and the time of a stretch are summed over moving intervals only: a pause, manual or automatic, adds no time, and ground covered while the timer was stopped adds no distance. A 5 km stretch covered in 28:40 on the clock with a 40-second pause is a 28:00 effort, and a run of exactly the distance gives the run's own moving time.
- **Search.** The moving distance and moving time are piecewise linear, so the fastest window starts or ends on a sample; both families are swept once, interpolating the other end inside its interval.
- **Position errors.** An interval faster than `MAX_EFFORT_SPEED_MPS` (12.5 m/s, a sprinter's top speed) counts for nothing, and a window faster than the world record for its distance (`WORLD_RECORD_S`: 43 s, 2:11, 12:35, 26:11, 57:00, 2:00:00) is discarded. What remains wrong can be excluded by hand ("exclure des records" on the activity).
- **Records** are not stored: they are the fastest efforts among the profile's activities that are not excluded, read on request, one effort per run. Deleting, moving or excluding a run therefore promotes the next best time at once, and an activity's rank is always today's.
- **History** lists, in date order, each run that beat every earlier one.

## 11. Race predictions

Riegel's formula projects a time from one distance to another:

```
T2 = T1 × (D2 / D1)^1.06          RIEGEL_EXPONENT = 1.06
```

- Only efforts of the last `PREDICTION_WINDOW_WEEKS` (12) and at least `PREDICTION_MIN_EFFORT_M` (1 km) long are used.
- For each race distance (5 km, 10 km, half-marathon, marathon — `PREDICTED_DISTANCES`) every qualifying effort is projected and the fastest projection is kept, together with the effort it comes from. An effort at the race distance projects to itself, so a prediction is never slower than a result achieved in the window.
- A prediction for a distance more than `INDICATIVE_DISTANCE_RATIO` (4) times the longest run of the window is marked indicative.

These are estimates of potential, not forecasts: the formula knows nothing about the course, the weather or the training behind the effort, and it flatters runners whose endurance has not caught up with their speed — which is why long distances are flagged.

## 12. Training load and recovery

**Load of a run** (`activities.load`, stored by the worker):

- with heart rate, the relative effort of section 7;
- otherwise, with a perceived effort: `moving minutes × RPE × SRPE_FACTOR` (0.35), which puts an hour at RPE 5 near an hour around zone 2;
- otherwise `moving minutes × DEFAULT_INTENSITY` (2.0).

The last two are marked estimated. Entering a perceived effort later, or changing the profile's heart-rate zones, recomputes the load.

**Daily series.** One value per local day from the first run to today, rest days at zero; it is aggregated on request, with the day cut by the caller's time-zone offset.

**Short-term and long-term load** are exponentially weighted daily averages with time constants `SHORT_TERM_DAYS` (7) and `LONG_TERM_DAYS` (28): each day, `avg += (load − avg) × (1 − e^(−1/τ))`. The averages are corrected for their start (divided by `1 − e^(−n/τ)` after `n` days), so a habit held for two weeks is already read at its level instead of creeping up from zero.

**Status** from the ratio short / long, once there are `MIN_HISTORY_DAYS` (14) days of history:

| Ratio | Status |
|---|---|
| under `RATIO_RESUMING` (0.5) | resuming |
| under `RATIO_LIGHT` (0.8) | light |
| up to `RATIO_STEADY` (1.2) | steady |
| up to `RATIO_BUILDING` (1.5) | building |
| above | too much, too soon |

**Recovery** after a run, in hours:

```
advised = clamp(RECOVERY_BASE_H + RECOVERY_PER_TYPICAL_H × (load / typical) × max(1, ratio), RECOVERY_MIN_H, RECOVERY_MAX_H)
        + RECOVERY_CARRY × hours still owed from the previous run        (12, 24, 6, 96, 0.5; capped at 96)
```

`typical` is the median load of the runs of the last `TYPICAL_WINDOW_WEEKS` (6) and `ratio` the short / long ratio on the day of the run. A usual run in a steady period gives 36 hours. The time remaining is the advised time minus the hours since the end of the run.

All of this is built from recorded runs alone. Sleep, stress, other sports and health are not known to it, and the heart-rate effort it starts from depends on the maximum heart rate set in the profile.

## 13. Recurring segments

A segment is a portion of route the profile has covered, in the same direction, in at least `MIN_TRAVERSALS` (3) activities, over at least `MIN_COURSE_M` (300 m). Segments are found by the background worker; nothing has to be drawn by hand. In the code they are called *courses*, because `segments` already names splits and laps.

**Same place.** Each trace is resampled every `RESAMPLE_M` (20 m) of its distance, and each point gets the direction from the point before it to the point after it. Two points are the same place when they are within `TOLERANCE_M` (25 m) and their directions differ by at most `MAX_HEADING_DIFF_DEG` (60°). Twenty-five metres absorbs phone GPS and the two sides of a road; forty metres to the side is another path, and the opposite direction is another segment.

**Who else passes here.** Every resampled point is stored with its grid cell (`CELL_M`, 25 m squares) and its direction as one of eight sectors. For a new activity, one query returns the other activities of the profile present in the cell of each of its points or one of the eight around it, heading the same way give or take one sector.

**New segments.** A stretch of the new activity becomes a segment where at least two other activities pass, where no known segment (hidden ones included) already runs, and over at least 300 m. Along the way:

- holes of at most 2 points left by position noise are bridged;
- the stretch is cut where the runs sharing it change by more than half between the 5 points before and the 5 points after — a junction where habits diverge;
- the stretch is cut where it comes back onto a point it passed at least 10 points (200 m) earlier, so that laps of a loop give one segment of one lap;
- 2 points are dropped at each end, where paths join and part and directions are still turning.

The grid only proposes. A new segment is kept only if at least three activities then cover it completely (below); otherwise it is discarded.

**Covering a segment.** An activity has an effort on a segment when it reaches the segment's first point, then meets at least `MATCH_COVERAGE` (90 %) of its points in order — the nearest point of the segment at most 5 points ahead, with at most 4 consecutive points of the activity off the segment — and reaches its last point. Turning back, cutting through, or running it the other way gives no effort; running it twice gives two.

**Efforts are timed on elapsed time**, between the two points of the activity that start and end the passage: on a segment you race your earlier passages, stops included. (Records, section 10, use moving time.)

**Names** are made from the length and the overall direction, in French like the default titles: "1,2 km · vers l'est", or "… · boucle" when the segment ends within `LOOP_M` (100 m) of its start. No place name is looked up: that would need an outbound request.

**When.** After each import, copy or move, for that activity only; a new segment is matched at once against the earlier activities that pass there, so its history is complete from its creation. Deleting or moving an activity removes its efforts; the segment stays.

**Checked on real data.** On the five real exports used during development the worker finds one segment: a 3.9 km loop with five complete passages across the runs. That is too little history to tune the thresholds further; they are checked again on the owner's full history (task 16.4). A one-hour run over a route already run twice is indexed, matched and turned into a segment in under 2 s on the development machine (debug build).

**Known limits.** Paths less than 25 m apart running the same way (a towpath beside a road) are read as one. A long shared stretch stays one long segment unless habits diverge on it. A segment is cut from the passage that created it, so its ends are where that run joined and left the shared path.

## Test data

- **Synthetic runs** built in `server/tests/pipeline.rs` cover each scenario of the `activity-metrics` spec (pauses, climbs, splits, laps, zones, effort, cadence, downsampling) and GPX reading, rejection and export.
- **Real exports** in `testdata/` are private and not committed. When present, a test checks that the computed distance and moving time of each file agree with the totals the file itself declares (within 1.5 % and 2 %). On the five Strava exports from a Pixel 8 used during development they agree to within 2 s and 10 m.
- **Not yet covered by a real file**: FIT files with several laps or with heart rate (the available exports have one lap and no heart rate), and FIT files without elevation.
