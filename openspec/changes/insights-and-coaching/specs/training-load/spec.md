# Spec Delta

## Purpose

Tells a beginner runner, in plain words, whether they are doing too little, enough or too much lately, and how long to recover after their latest run, from the effort of each stored activity.

## ADDED Requirements

### Requirement: Load of an activity
Each activity SHALL have a training load. It SHALL be the activity's relative effort when heart rate was recorded. Otherwise it SHALL be estimated from the moving time and the perceived effort entered by the runner, or, when neither is available, from the moving time at a moderate intensity. An estimated load SHALL be marked as estimated wherever it is shown.

#### Scenario: Run with heart rate
- **WHEN** an activity has a relative effort of 95
- **THEN** its load is 95

#### Scenario: Run without heart rate but with perceived effort
- **WHEN** a 40-minute run without heart rate has a perceived effort of 8
- **THEN** its load is higher than that of a 40-minute run rated 3, and is marked estimated

#### Scenario: Perceived effort entered later
- **WHEN** the runner adds a perceived effort to a run without heart rate
- **THEN** its load and everything derived from it are updated

### Requirement: Daily and weekly load
The web app SHALL show the active profile's load per day and per week over a selectable period of at least twelve weeks, with rest days shown as zero.

#### Scenario: Three runs in a week
- **WHEN** the runner ran three times in a week with loads 60, 90 and 45
- **THEN** that week's load is 195 and four days show zero

### Requirement: Short-term against long-term load
The system SHALL maintain a short-term load reflecting about the last 7 days and a long-term load reflecting about the last 28 days, each as a daily average weighted towards recent days, and SHALL show both as curves over time.

#### Scenario: Sudden heavy week
- **WHEN** the runner doubles their usual weekly load for one week
- **THEN** the short-term curve rises clearly above the long-term curve

#### Scenario: Two weeks off
- **WHEN** the runner does not run for two weeks
- **THEN** the short-term load falls faster than the long-term load, and both decrease every day

### Requirement: Load status
The web app SHALL state the current relation between short-term and long-term load as one of: resuming, light, steady, building, or too much too soon, each with a one-sentence explanation. With fewer than 14 days of history the status SHALL be "not enough data yet".

#### Scenario: Steady training
- **WHEN** short-term load is within 20 % of long-term load
- **THEN** the status is steady

#### Scenario: Risky jump
- **WHEN** short-term load exceeds one and a half times long-term load
- **THEN** the status is too much too soon and advises easing off

#### Scenario: New profile
- **WHEN** the profile's first run was 5 days ago
- **THEN** the status says there is not enough data yet

### Requirement: Advised recovery time
After each activity the system SHALL estimate a recovery time in hours, longer for a harder session relative to the runner's usual load and longer when short-term load is already high, bounded between 6 and 96 hours. The web app SHALL show the time remaining now, counted from the end of the latest activity, and the moment at which the runner is considered recovered.

#### Scenario: Hard session
- **WHEN** the runner's latest run had twice their usual load and ended 10 hours ago
- **THEN** the remaining recovery time is longer than it would be after a usual run ended 10 hours ago

#### Scenario: Recovered
- **WHEN** the advised recovery time has fully elapsed
- **THEN** the app says the runner is recovered, with no remaining time

#### Scenario: Second run before recovery
- **WHEN** the runner runs again while 20 hours of recovery remain
- **THEN** the new advised recovery time takes the unrecovered part into account and is longer than for the same run done fully recovered

### Requirement: Estimates, not prescriptions
Load, status and recovery SHALL be presented as estimates based only on recorded runs, with a short explanation of how they are obtained available from the page. They SHALL NOT be presented as medical advice.

#### Scenario: How is this computed
- **WHEN** the runner opens the explanation on the load page
- **THEN** the page states what the load is based on and that sleep, stress and other sports are not taken into account

### Requirement: Load follows the library
Load figures SHALL reflect the profile's current activities and settings: importing, deleting, moving or copying an activity, or changing the profile's heart-rate zones, SHALL be reflected the next time the figures are shown.

#### Scenario: Changing maximum heart rate
- **WHEN** the runner corrects their maximum heart rate in the settings
- **THEN** the loads of past activities, the curves and the status are recomputed with the new zones
