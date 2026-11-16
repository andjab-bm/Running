# Spec Delta

## Purpose

Finds a runner's best times over standard distances inside all their runs, keeps their history, and estimates what they could run in a race, so that progress is visible without ever entering a result by hand.

## ADDED Requirements

### Requirement: Best efforts inside every run
For each activity the system SHALL find the fastest continuous stretch of 400 m, 1 km, 5 km, 10 km, a half-marathon (21.0975 km) and a marathon (42.195 km), wherever it lies in the run, and SHALL time it by moving time: time spent in a pause, manual or automatic, SHALL NOT count. A distance longer than the run SHALL have no effort for that run.

#### Scenario: Fast kilometre in the middle
- **WHEN** an 8 km run contains a kilometre covered in 4:50 between 3.2 km and 4.2 km and no faster one
- **THEN** that run's best 1 km effort is 4:50, starting at 3.2 km

#### Scenario: Run shorter than the distance
- **WHEN** a run is 7 km long
- **THEN** it has best efforts for 400 m, 1 km and 5 km and none for 10 km or longer

#### Scenario: Pause inside the stretch
- **WHEN** a 5 km stretch was covered in 28:40 on the clock, including a 40-second pause at a crossing
- **THEN** its time is 28:00

#### Scenario: Same rule as the run's own time
- **WHEN** a run is exactly 5 km long with a moving time of 27:30
- **THEN** its best 5 km effort is 27:30

### Requirement: Personal records
The web app SHALL show, for the active profile, the record for each standard distance — the fastest best effort among its activities — with its time, its pace, its date and a link to the activity, and the two next best times. A distance never covered SHALL be shown as not yet run.

#### Scenario: Records page
- **WHEN** the runner has run 5 km in 27:40, 28:15 and 29:02 at different dates
- **THEN** the 5 km record is 27:40 with its date and the two other times are listed below it

#### Scenario: Never run a marathon
- **WHEN** no activity is at least 42.195 km long
- **THEN** the marathon line says it has not been run yet

#### Scenario: Open the run of a record
- **WHEN** the runner follows a record's link
- **THEN** the activity opens with the stretch of that effort highlighted on the map and the chart

### Requirement: New record on an activity
When an activity holds a best effort that is the profile's record, or its second or third best time, for a distance, the activity page SHALL say so. The ranking SHALL be the current one, not the one at the time of the run.

#### Scenario: First sub-30 on 5 km
- **WHEN** a newly synced run contains the profile's fastest 5 km
- **THEN** its activity page shows a record mark for 5 km with the time

#### Scenario: Record beaten later
- **WHEN** a later run beats that 5 km time
- **THEN** the earlier activity shows a second-best mark instead of the record mark

### Requirement: Record history
For each standard distance the web app SHALL show how the record evolved over time, as the succession of dates at which it was beaten.

#### Scenario: Three improvements
- **WHEN** the 10 km record was set at 1:02:10, then 59:40, then 58:05 on three dates
- **THEN** the history shows three steps down at those dates

### Requirement: Records stay consistent
Records SHALL always reflect the profile's current activities. Deleting or moving away the activity that holds a record SHALL promote the next best time. An activity copied to another profile SHALL count for the records of that profile too.

#### Scenario: Delete the record run
- **WHEN** the runner deletes the activity holding the 1 km record
- **THEN** the previous second-best time becomes the record

#### Scenario: Imported history
- **WHEN** the runner imports two years of history
- **THEN** the records take the imported runs into account once processing has finished, and the page says so while it is still in progress

### Requirement: Exclude an activity from records
The runner SHALL be able to exclude an activity from records and predictions, for example a bike ride recorded by mistake or a run with a faulty trace, and to include it again. An excluded activity SHALL say so on its page.

#### Scenario: Faulty GPS
- **WHEN** the runner excludes an activity whose trace jumped and produced a 2:10 kilometre
- **THEN** that time disappears from the records and the previous record is restored

### Requirement: Race predictions
The web app SHALL estimate the runner's potential time over 5 km, 10 km, half-marathon and marathon from their best recent efforts, and SHALL show each estimate with its pace, the effort it is based on, and a statement that it is an estimate. Only efforts from the last 12 weeks, at least 1 km long, SHALL be used.

#### Scenario: Recent 5 km
- **WHEN** the runner's best recent effort is 5 km in 28:00
- **THEN** a 10 km prediction close to 58:20 is shown, naming the 5 km effort and its date as its basis

#### Scenario: No recent effort
- **WHEN** the runner has no qualifying effort in the last 12 weeks
- **THEN** no prediction is shown and the page explains that a recent run is needed

#### Scenario: Prediction never slower than a recent result
- **WHEN** the runner ran 10 km in 57:00 within the last 12 weeks
- **THEN** the 10 km prediction is 57:00 or faster

### Requirement: Honest long-distance predictions
A prediction for a distance more than four times the runner's longest run of the last 12 weeks SHALL be marked as indicative, with the reason. Predictions SHALL never be presented as a guarantee or as training advice.

#### Scenario: Marathon for a 5 km runner
- **WHEN** the runner's longest recent run is 8 km
- **THEN** the marathon prediction is marked indicative because no recent run comes close to that distance
