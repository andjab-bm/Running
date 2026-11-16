# Spec Delta

## Purpose

Defines what the runner sees and touches on the phone during a run, so that the essential figures are readable at a glance while moving and the screen cannot be operated by rain, sweat or a pocket.

## ADDED Requirements

### Requirement: Main metrics screen
During a run the phone SHALL show a main screen with large, glanceable figures in four fields. By default it SHALL show average pace with the per-kilometre pace bars at the top, cadence at middle left, heart rate at middle right, and total time with distance and the mini-map area at the bottom, in the units of the active profile. A value that is not available SHALL be shown as a placeholder, never as zero.

#### Scenario: Default layout
- **WHEN** a run is in progress with the watch joined and no field has been changed
- **THEN** the main screen shows average pace with pace bars, cadence, heart rate, and total time with distance beside the mini-map area, updated at least once per second

#### Scenario: No heart rate
- **WHEN** a run is in progress without the watch
- **THEN** the heart-rate field shows a placeholder

#### Scenario: Imperial profile
- **WHEN** the active profile uses imperial units
- **THEN** distance is shown in miles and pace in min/mi

### Requirement: Per-kilometre pace bars
When average pace is shown in the top field, the field SHALL also show one bar per completed split of the profile's distance unit, labelled with its pace, taller when faster, plus a visually distinct bar for the split in progress. At most the eight most recent bars SHALL be shown.

#### Scenario: Fourth kilometre in progress
- **WHEN** the runner is 3.4 km into a run
- **THEN** three bars show the pace of kilometres 1 to 3 and a fourth, lighter bar shows the pace of the kilometre in progress

#### Scenario: Long run
- **WHEN** the runner has completed 12 km
- **THEN** only the eight most recent bars are shown

### Requirement: Mini-map area
The combined bottom field SHALL show total elapsed time and distance beside an area reserved for a mini-map. Until a map is available, that area SHALL show the shape of the trace recorded so far, without basemap, and SHALL make no network request.

#### Scenario: Trace so far
- **WHEN** the runner has covered 2 km
- **THEN** the mini-map area shows the shape of those 2 km, ending at the current position

### Requirement: Stable current pace
The current pace shown SHALL be smoothed so that it does not jump between consecutive seconds at steady effort, and SHALL show a placeholder when the runner is stopped or the position is unreliable.

#### Scenario: Steady running
- **WHEN** the runner holds a steady 6:00 min/km for one minute
- **THEN** the displayed pace stays within 15 s/km of 6:00

#### Scenario: Standing still
- **WHEN** the runner is not moving
- **THEN** the pace field shows a placeholder rather than an extreme value

### Requirement: Heart-rate zone colour
When heart rate is available, the main screen SHALL show the current heart-rate zone of the active profile by its number and by the zone colour used in the web app. Zones SHALL be those last retrieved from the server for the active profile, or the documented default zones when none were retrieved.

#### Scenario: Entering zone 4
- **WHEN** the heart rate rises into the profile's zone 4 range
- **THEN** the heart-rate field takes the zone 4 colour and shows "Z4"

#### Scenario: Never paired with a server
- **WHEN** the app has never retrieved a profile
- **THEN** zones are derived from the default maximum heart rate

### Requirement: Current-lap screen
A second screen, reachable by a horizontal swipe, SHALL show the current lap: its number, time, distance and average pace, and the previous lap's time and pace once a lap has been marked.

#### Scenario: After a lap mark
- **WHEN** the runner marks a lap and swipes to the lap screen
- **THEN** it shows lap 2 counting from zero and the time and pace of lap 1

### Requirement: Choose the displayed fields
The runner SHALL be able to choose, in the settings, which metric appears in each field of the main screen from: moving time, elapsed time, distance, current pace, average pace, lap pace, heart rate, cadence, elevation gain, time of day; the bottom field SHALL also offer the combined total time, distance and mini-map. The choice SHALL be remembered.

#### Scenario: Replace a field
- **WHEN** the runner replaces cadence by current pace in the settings
- **THEN** the next run shows current pace in that field

### Requirement: Controls during a run
Pause, resume, lap and stop SHALL be reachable from every live screen with targets large enough to hit while running. The paused state, manual or automatic, SHALL be unmistakable on every live screen.

#### Scenario: Lap from the lap screen
- **WHEN** the runner is on the current-lap screen
- **THEN** the lap control is available without changing screen

#### Scenario: Auto-paused
- **WHEN** the run is auto-paused
- **THEN** the screen shows that it is paused automatically and that it will resume on movement

### Requirement: Touch lock
The runner SHALL be able to lock the screen against touches during a run. While locked, figures SHALL stay visible and up to date, no control SHALL react to a tap or a swipe, and unlocking SHALL require a deliberate gesture.

#### Scenario: Rain on a locked screen
- **WHEN** the screen is locked and receives random taps and swipes
- **THEN** the run is not paused, lapped or stopped, and the screen shown does not change

#### Scenario: Unlock
- **WHEN** the runner performs the unlock gesture
- **THEN** the controls respond again

### Requirement: Keep the screen readable
The app SHALL offer a setting to keep the screen on during a run, and SHALL show the live screens over the phone's lock screen so the runner does not have to unlock the phone to read them.

#### Scenario: Screen kept on
- **WHEN** the keep-screen-on setting is enabled and a run is in progress
- **THEN** the screen does not time out

#### Scenario: Waking the phone mid-run
- **WHEN** the runner presses the power button during a run with the phone locked
- **THEN** the live screen is shown without entering the phone's unlock code

### Requirement: Run notification
While a run is in progress the notification SHALL show its state, moving time and distance, and SHALL open the live screen when tapped.

#### Scenario: Returning from another app
- **WHEN** the runner taps the run notification while another app is in front
- **THEN** the live screen of the current run opens
