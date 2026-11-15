# Spec Delta

## Purpose

Gives the runner heart rate, the essential figures and the session controls on the wrist, with an app light enough for an ageing Galaxy Watch 4: the watch assists the phone and never records a run by itself.

## ADDED Requirements

### Requirement: Join a session
Opening the watch app while a session is prepared or running on the phone SHALL join that session. When no session exists on the phone, the watch app SHALL say so and SHALL NOT start one.

#### Scenario: Session prepared on the phone
- **WHEN** the runner opens the watch app while the phone shows the ready screen
- **THEN** the watch shows that it has joined and the phone shows the watch as connected

#### Scenario: Nothing prepared
- **WHEN** the runner opens the watch app with no session on the phone
- **THEN** the watch asks the runner to prepare the run on the phone

#### Scenario: Phone out of reach
- **WHEN** the watch app is opened while the phone is not connected
- **THEN** the watch states that the phone is not reachable

### Requirement: Heart-rate capture
While joined to a session, the watch SHALL measure heart rate continuously and deliver it to the phone with the time of each measurement, so that the recorded run carries heart rate at a resolution of 5 seconds or better. Capture SHALL continue when the watch screen is off.

#### Scenario: Wrist down for the whole run
- **WHEN** the runner runs 30 minutes without looking at the watch
- **THEN** the saved run has heart rate over the whole duration

#### Scenario: Permission refused
- **WHEN** the runner has not granted body-sensor access on the watch
- **THEN** the watch explains the need and offers to grant it
- **AND** controls and metrics work without heart rate if it stays refused

### Requirement: Session controls on the wrist
Once a session is joined, the runner SHALL be able to start the prepared run, pause, resume, mark a lap and stop from the watch. Stop SHALL require a deliberate gesture. A control SHALL be confirmed by a short vibration only when the phone has applied it.

#### Scenario: Start from the wrist
- **WHEN** the runner presses start on the watch while the phone is on the ready screen
- **THEN** the run starts on the phone

#### Scenario: Lap from the wrist
- **WHEN** the runner presses lap on the watch
- **THEN** the lap is marked on the phone at that moment and the watch vibrates

#### Scenario: Control while disconnected
- **WHEN** the runner presses pause on the watch while the phone is out of reach
- **THEN** the watch signals that the command was not applied

### Requirement: Glanceable metrics
During a run the watch SHALL show a single screen with current pace as the largest, central figure, moving time above it, and distance and heart rate below it, and SHALL make the paused state obvious. The controls SHALL be one swipe away. The watch app SHALL have no other screens, settings, tile or complication.

#### Scenario: Glance while running
- **WHEN** the runner raises the wrist during a run
- **THEN** pace is shown largest in the centre, with time above and distance and heart rate below, at most 2 seconds old

#### Scenario: Paused
- **WHEN** the run is paused from the phone
- **THEN** the watch screen shows the paused state within 2 seconds

### Requirement: Tolerate link interruptions
If the link with the phone drops during a run, the phone SHALL keep recording, and the watch SHALL keep measuring heart rate and deliver the missed measurements when the link returns. The watch SHALL show that the phone is unreachable instead of stale figures.

#### Scenario: Two-minute dropout
- **WHEN** the link drops for two minutes during a run and comes back
- **THEN** the saved run has heart rate for those two minutes

#### Scenario: Link never returns
- **WHEN** the link is lost and the run is stopped on the phone before it returns
- **THEN** the run is saved with the heart rate received until the dropout

### Requirement: End with the session
When the run is stopped or discarded on the phone, or the phone has been unreachable for 10 minutes, the watch SHALL stop measuring heart rate and release its sensors. The watch SHALL NOT keep any run data after the session.

#### Scenario: Stop on the phone
- **WHEN** the runner stops the run on the phone
- **THEN** the watch stops measuring within 10 seconds and shows the run as finished

### Requirement: Light on resources
The watch app SHALL NOT use the watch's GPS, Wi-Fi or mobile network, and SHALL NOT refresh the screen while it is dimmed more often than the system's ambient mode allows. A one-hour run SHALL consume no more than 12 % of a Galaxy Watch 4 battery.

#### Scenario: One-hour run
- **WHEN** a one-hour run is recorded with the watch joined and occasional glances
- **THEN** the watch battery level has dropped by 12 points or fewer
