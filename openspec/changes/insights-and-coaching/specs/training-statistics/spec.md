# Spec Delta

## Purpose

Shows a runner how much and how regularly they run over weeks, months and years, and whether they are progressing, from the activities stored in their profile.

## ADDED Requirements

### Requirement: Yearly calendar heatmap
The web app SHALL show, for a chosen year and the active profile, a calendar with one cell per day whose intensity reflects the distance run that day, with the year's totals: number of runs, distance, moving time and elevation gain. Days without a run SHALL be visibly empty. The measure colouring the cells SHALL be switchable between distance, moving time and training load.

#### Scenario: A year of running
- **WHEN** the runner opens the statistics for a year in which they ran on 96 days
- **THEN** 96 cells are filled, darker for longer runs, and the totals of the year are shown

#### Scenario: Day detail
- **WHEN** the runner points at or taps a filled day
- **THEN** that day's runs are named with their distance, and each can be opened

#### Scenario: Two runs on one day
- **WHEN** a day holds a 5 km run and a 3 km run
- **THEN** its cell reflects 8 km

#### Scenario: Year without any run
- **WHEN** the runner selects a year holding no activity
- **THEN** an empty calendar is shown with totals at zero, not an error

### Requirement: Weekly and monthly volume
The web app SHALL show the distance, moving time, number of runs and elevation gain per week and per month as a bar chart over a selectable period, with weeks starting on Monday in the profile's time zone. The current, unfinished period SHALL be distinguishable from completed ones.

#### Scenario: Last twelve weeks
- **WHEN** the runner views weekly volume over twelve weeks
- **THEN** twelve bars are shown, one per week, including weeks with no run as empty bars

#### Scenario: Run late on Sunday
- **WHEN** a run started on Sunday at 23:30 in the profile's time zone
- **THEN** it counts in the week that ends that Sunday

#### Scenario: Switch to months
- **WHEN** the runner switches the chart to months
- **THEN** the same activities are grouped by calendar month and the totals over the period are unchanged

### Requirement: Progress curves
The web app SHALL show how average pace, longest run and average heart rate evolve per month over at least the last twelve months, and SHALL allow limiting the curves to runs carrying a chosen tag. A month without data for a measure SHALL leave a gap rather than a zero.

#### Scenario: Getting faster
- **WHEN** the runner's monthly average pace went from 6:40 to 6:10 min/km over six months
- **THEN** the pace curve shows that improvement, with faster drawn higher

#### Scenario: Only easy runs
- **WHEN** the runner limits the curves to the tag "Endurance fondamentale"
- **THEN** only runs carrying that tag contribute

#### Scenario: Month without heart rate
- **WHEN** no run of a month carries heart rate
- **THEN** the heart-rate curve has a gap for that month

### Requirement: Statistics follow the library
Statistics SHALL reflect the active profile's activities as they are now: an activity that is imported, deleted, moved or copied to another profile SHALL be reflected the next time the statistics are shown. Statistics SHALL use the profile's units.

#### Scenario: Deleting a run
- **WHEN** the runner deletes a 10 km run and reopens the statistics
- **THEN** the week, month and year totals are 10 km lower

#### Scenario: Other profile
- **WHEN** the runner switches to another profile
- **THEN** the statistics show only that profile's runs

#### Scenario: Imperial profile
- **WHEN** the active profile uses imperial units
- **THEN** distances are in miles and paces in min/mi
