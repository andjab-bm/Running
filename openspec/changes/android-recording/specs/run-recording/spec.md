# Spec Delta

## Purpose

Lets the runner record a run on their own phone — position, time, pace, elevation, cadence and heart rate — reliably for the whole outing, and produces a standard activity file from it without any third-party service.

## ADDED Requirements

### Requirement: Prepare a session
The phone app SHALL offer a ready screen that acquires the position before the run starts and shows whether the position fix is good enough and whether the watch has joined. Starting SHALL be possible before a good fix is obtained, with a warning.

#### Scenario: Good fix
- **WHEN** the runner opens the ready screen outdoors and the position accuracy becomes better than 20 m
- **THEN** the screen shows the position as ready

#### Scenario: Start without a fix
- **WHEN** the runner starts while no good fix is available
- **THEN** the run starts and time is counted
- **AND** the runner is told that distance will only be counted once the position is found

#### Scenario: Permission refused
- **WHEN** the runner opens the ready screen without having granted precise location access
- **THEN** the app explains why it is needed and offers to grant it
- **AND** a run cannot be started until it is granted

### Requirement: Session control
The runner SHALL be able to start a run, pause it, resume it and stop it from the phone. Stopping SHALL require a deliberate gesture so that an accidental touch cannot end a run. While paused, elapsed time SHALL keep running and moving time and distance SHALL NOT increase.

#### Scenario: Pause and resume
- **WHEN** the runner pauses for 90 seconds at a 2.00 km mark and then resumes
- **THEN** distance is still 2.00 km at resume
- **AND** elapsed time has advanced by 90 seconds and moving time by none

#### Scenario: Accidental stop
- **WHEN** the stop control is touched briefly
- **THEN** the run continues

#### Scenario: Deliberate stop
- **WHEN** the runner holds the stop control until its confirmation completes
- **THEN** the run ends and the end-of-run summary is shown

### Requirement: Auto-pause
When auto-pause is enabled, the app SHALL pause the run automatically when the runner stops moving and resume it when they move again, and SHALL signal both transitions. Auto-pause SHALL be a setting, enabled by default. A manual pause SHALL only end by a manual resume.

#### Scenario: Waiting at a traffic light
- **WHEN** the runner stands still for 30 seconds with auto-pause enabled
- **THEN** the run is shown as auto-paused within 10 seconds of stopping
- **AND** it resumes on its own within 5 seconds of running again

#### Scenario: Auto-pause disabled
- **WHEN** the runner stands still with auto-pause disabled
- **THEN** moving time keeps increasing

#### Scenario: Manual pause is not resumed automatically
- **WHEN** the runner pauses manually and then walks around
- **THEN** the run stays paused until the runner resumes it

### Requirement: Manual laps
The runner SHALL be able to mark a lap at any time while the run is active. Each lap SHALL record its distance, moving time, average pace and, when available, average heart rate. The last lap SHALL be closed when the run stops.

#### Scenario: Mark a lap
- **WHEN** the runner marks a lap 400 m after the previous lap mark
- **THEN** a lap of 400 m is recorded and a new lap starts at zero

#### Scenario: Run without lap marks
- **WHEN** a run is stopped without any lap mark
- **THEN** it holds a single lap covering the whole run

### Requirement: Recorded data
For each run the app SHALL record, about once per second while active: position, elevation, cumulative distance and time; plus cadence when the phone can measure steps and heart rate when the watch provides it. Position fixes that are implausible SHALL NOT add distance. Missing channels SHALL be left empty, never filled with zero.

#### Scenario: Run with the watch
- **WHEN** a run is recorded with the watch joined
- **THEN** the recorded samples carry heart rate alongside position

#### Scenario: Run without the watch
- **WHEN** a run is recorded without the watch
- **THEN** the run is recorded normally and heart rate is absent

#### Scenario: Position jump
- **WHEN** a single fix lands 300 m away from the previous one a second later
- **THEN** that fix does not increase the distance

### Requirement: Recording continues in the background
Recording SHALL continue when the screen is off, when another app is in front, and when the phone is locked. While a session is prepared or running, the phone SHALL show a persistent notification stating its state.

#### Scenario: Screen off for the whole run
- **WHEN** the runner starts a run, turns the screen off and runs 5 km
- **THEN** the recorded trace is continuous and the distance is within 2 % of the real distance

### Requirement: Survive interruption
Recorded data SHALL be saved durably as the run progresses. If the app or the phone stops unexpectedly during a run, the app SHALL, at its next opening, offer to resume the run or to end it and keep what was recorded.

#### Scenario: App killed mid-run
- **WHEN** the app process is killed 20 minutes into a run and the app is reopened
- **THEN** the app offers to resume or end that run
- **AND** at most the last 10 seconds before the kill are missing

#### Scenario: End an interrupted run
- **WHEN** the runner chooses to end the interrupted run
- **THEN** it is saved with the data recorded up to the interruption

### Requirement: End-of-run summary
After stopping, the app SHALL show a summary — distance, moving time, average pace, elevation gain, average heart rate when available, laps — and SHALL let the runner save the run, optionally with a title, a perceived effort from 1 to 10 and a note, or discard it after confirmation.

#### Scenario: Save with perceived effort
- **WHEN** the runner sets a perceived effort of 6 and saves
- **THEN** the run appears in the phone history with that effort

#### Scenario: Discard
- **WHEN** the runner chooses to discard and confirms
- **THEN** the run is deleted from the phone and is never sent to the server

#### Scenario: Very short run
- **WHEN** a run is stopped after less than 10 seconds or 20 metres
- **THEN** the app proposes discarding it by default

### Requirement: Activity file
Every saved run SHALL be written as one FIT activity file containing the samples, the manual laps, the pause and resume events, and a running sport type. The file SHALL be importable by the home server, which SHALL then report the same lap count and pauses, and a distance within 1 % of the distance shown on the phone.

#### Scenario: Server reads back a recorded run
- **WHEN** a run with three laps, one manual pause and heart rate is saved and imported by the server
- **THEN** the server activity has three laps, heart rate, and a moving time that excludes the pause

#### Scenario: Run without elevation or heart rate
- **WHEN** a run recorded without heart rate is saved
- **THEN** the file is valid and the server reports heart rate as unavailable
