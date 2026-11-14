# Spec Delta

## Purpose

Defines how the figures shown for a run — distance, times, pace, elevation, splits, laps, heart-rate zones and effort — are derived from recorded samples, so that every client displays the same values.

## ADDED Requirements

### Requirement: Summary metrics
The system SHALL compute for each activity: total distance, elapsed time, moving time, average pace (based on moving time), elevation gain, elevation loss, and — when the data exists — average and maximum heart rate and average cadence in steps per minute.

#### Scenario: Complete data
- **WHEN** an activity with GPS, elevation, heart rate and cadence is processed
- **THEN** all summary metrics are available

#### Scenario: Missing channel
- **WHEN** an activity has no heart rate samples
- **THEN** average and maximum heart rate are reported as unavailable
- **AND** all other summary metrics are still computed

#### Scenario: Cadence recorded per leg
- **WHEN** a file records cadence as strides per minute for one leg
- **THEN** the reported cadence is expressed in steps per minute for both legs

### Requirement: Moving time and auto-pause
The system SHALL distinguish elapsed time from moving time. When the source records pause events, moving time SHALL exclude the paused intervals. Otherwise the system SHALL treat as paused any interval of at least 10 seconds during which speed stays below 1.5 km/h, and any gap of more than 30 seconds between consecutive samples.

#### Scenario: Recorded pauses
- **WHEN** a 40-minute activity contains a recorded 3-minute timer pause
- **THEN** elapsed time is 40 minutes and moving time is 37 minutes

#### Scenario: Detected stop
- **WHEN** an activity without pause events contains 2 minutes standing still at a traffic light
- **THEN** moving time is approximately 2 minutes shorter than elapsed time

#### Scenario: Brief slowdown
- **WHEN** speed drops below the threshold for only 4 seconds
- **THEN** that interval counts as moving time

### Requirement: Elevation gain and loss
The system SHALL compute elevation gain and loss from a smoothed elevation profile so that sensor noise on flat ground does not accumulate as climbing. When the file has no elevation data, both values SHALL be reported as unavailable.

#### Scenario: Flat run with noisy altitude
- **WHEN** an activity on flat ground has altitude samples fluctuating by ±1 m
- **THEN** elevation gain and loss are each below 5 m per 10 km

#### Scenario: Real climb
- **WHEN** an activity contains a continuous climb of 100 m
- **THEN** elevation gain is within 10 % of 100 m

### Requirement: Grade-adjusted pace
The system SHALL compute a grade-adjusted pace that estimates the equivalent flat-ground pace of each portion of the run given its slope, and SHALL report it for the whole activity and for each split and lap. It SHALL be unavailable when elevation data is missing.

#### Scenario: Uphill split
- **WHEN** a split is run at 6:00 min/km on a steady 5 % uphill
- **THEN** its grade-adjusted pace is faster than 6:00 min/km

#### Scenario: Flat split
- **WHEN** a split is run on flat ground
- **THEN** its grade-adjusted pace is within 2 seconds per km of its actual pace

### Requirement: Distance splits
The system SHALL divide each activity into consecutive splits of one distance unit (1 km, or 1 mile when the athlete uses imperial units), with a final partial split, and SHALL report for each split: distance, moving time, average pace, grade-adjusted pace, elevation gain, elevation loss, average heart rate and average cadence.

#### Scenario: 5.4 km run
- **WHEN** a 5.4 km activity is processed with metric units
- **THEN** six splits are produced, the last one covering 0.4 km
- **AND** the split distances sum to the activity distance

#### Scenario: Paused time excluded
- **WHEN** a pause occurs during the third kilometre
- **THEN** the third split's time and pace exclude the paused interval

### Requirement: Manual laps
The system SHALL preserve the laps recorded in the source and report for each lap the same metrics as for a split. An activity whose source has no lap data SHALL have a single lap covering the whole activity.

#### Scenario: Interval session with laps
- **WHEN** a FIT file containing 12 recorded laps is imported
- **THEN** the activity exposes 12 laps with start offset, distance, time, pace and average heart rate

#### Scenario: Source without laps
- **WHEN** a GPX file is imported
- **THEN** the activity exposes exactly one lap equal to the whole activity

### Requirement: Heart-rate zone distribution
The system SHALL compute the moving time spent in each of the athlete's five heart-rate zones. The zone times SHALL sum to the moving time for which heart rate was recorded.

#### Scenario: Zone breakdown
- **WHEN** an activity with heart rate is processed for an athlete with configured zones
- **THEN** a time and a percentage are reported for each of the five zones
- **AND** the percentages sum to 100 %

#### Scenario: No heart rate
- **WHEN** an activity has no heart rate samples
- **THEN** the zone distribution is reported as unavailable

### Requirement: Relative effort score
The system SHALL compute a single relative effort score per activity from time in heart-rate zones, weighting higher zones more heavily, so that longer or more intense runs score higher. The score SHALL be unavailable without heart rate data.

#### Scenario: Harder run scores higher
- **WHEN** two runs have the same duration and one spends more time in zones 4–5
- **THEN** that run has the higher relative effort score

#### Scenario: Longer run scores higher
- **WHEN** two runs have the same zone proportions and one lasts twice as long
- **THEN** its relative effort score is twice as high

### Requirement: Recompute on profile change
The system SHALL recompute zone distribution and relative effort for existing activities when the athlete's heart-rate zones change, and SHALL recompute splits when the unit system changes.

#### Scenario: Max heart rate updated
- **WHEN** the athlete changes their maximum heart rate
- **THEN** previously imported activities show zone times and effort scores based on the new zones

### Requirement: Sample streams for charts
The system SHALL expose per-activity sample series (position, distance, time, elevation, pace, heart rate, cadence) aligned on a common index, and SHALL offer a reduced-resolution variant bounded in size regardless of activity length.

#### Scenario: Long activity
- **WHEN** a client requests the reduced-resolution series of a 4-hour activity
- **THEN** the response contains at most 2 000 points per series
- **AND** the first and last points match the start and end of the activity
