# Spec Delta

## Purpose

Guides the runner through a structured session during the run: it tracks which step they are in, tells them when it changes and what to do next, and records the session so it can be reviewed afterwards.

## ADDED Requirements

### Requirement: Start a run with a session
On the ready screen the runner SHALL be able to choose one of the sessions held by the phone, or none. A run started with a session SHALL begin at its first step. A run without a session SHALL behave exactly as before.

#### Scenario: Choose a session
- **WHEN** the runner chooses "6 × 400 m" on the ready screen and starts
- **THEN** the run starts in the warm-up step

#### Scenario: Free run
- **WHEN** the runner starts without choosing a session
- **THEN** the run is a free run with no step

### Requirement: Step progression
A step with a duration SHALL end when that much moving time has passed in it; a step with a distance SHALL end when that distance has been covered in it; an open-ended step SHALL end when the runner presses lap. The next step SHALL start at that instant. Pressing lap during a timed or distance step SHALL end it early and move to the next step.

#### Scenario: Distance step
- **WHEN** the runner has covered 400 m since the start of a 400 m effort step
- **THEN** the step ends and the recovery step starts

#### Scenario: Open-ended recovery
- **WHEN** the runner presses lap during a recovery that ends on the lap button
- **THEN** the next effort starts at that moment

#### Scenario: Skip ahead
- **WHEN** the runner presses lap two minutes into a 10-minute warm-up
- **THEN** the warm-up ends and the next step starts

### Requirement: Pauses do not consume a step
While the run is paused, manually or automatically, the current step SHALL NOT progress. During effort steps shorter than two minutes, auto-pause SHALL NOT trigger.

#### Scenario: Traffic light during a recovery
- **WHEN** the run auto-pauses for 40 seconds during a 1:30 recovery
- **THEN** the recovery still lasts 1:30 of moving time

#### Scenario: Short effort
- **WHEN** the runner is in a 200 m effort step
- **THEN** the run does not auto-pause, whatever the measured speed

### Requirement: Cues at step changes
At each change of step the app SHALL signal the change and announce the step that starts — its kind, its length and its target — through the channels the runner enabled. Timed and distance steps of 30 seconds or 200 m and more SHALL be preceded by a short countdown signal in their last seconds.

#### Scenario: Entering an effort
- **WHEN** the recovery ends and a 400 m effort with a 5:00–5:15 target starts
- **THEN** the phone vibrates and announces the effort, its distance and its target pace

#### Scenario: Countdown
- **WHEN** five seconds remain in a 1:30 recovery
- **THEN** a countdown signal is given so the runner is ready when the effort starts

#### Scenario: All channels off
- **WHEN** the runner has disabled voice, sound and vibration
- **THEN** steps still change and the screen shows them, with no cue

### Requirement: Live step screen
During a run with a session, the live screens SHALL include a step screen showing the current step's kind, what remains of it as time or distance, its target with the current value against it, its position in the session such as "repeat 3 of 6", and the next step. The step screen SHALL be the screen shown when the run starts.

#### Scenario: Middle of a repeat
- **WHEN** the runner is 250 m into the third 400 m effort
- **THEN** the screen shows 150 m remaining, "3 of 6", the current pace against the target range, and the recovery that follows

#### Scenario: Open-ended step
- **WHEN** the current step ends on the lap button
- **THEN** the screen shows the time elapsed in the step and that pressing lap ends it

### Requirement: Target feedback
When the current step has a target, the step screen SHALL show whether the runner is within it, too slow or too fast (or below or above the heart-rate zone). When the runner stays outside the target for 15 seconds, an alert SHALL be given, and SHALL NOT be repeated more than once every 30 seconds. Target alerts SHALL be a setting, enabled by default.

#### Scenario: Too fast in an effort
- **WHEN** the runner holds 4:40 min/km for 15 seconds in a step targeting 5:00–5:15
- **THEN** the screen shows "too fast" and one alert is given

#### Scenario: Back in range
- **WHEN** the runner returns within the target
- **THEN** the indication returns to "in target" and no further alert is given

#### Scenario: No heart rate for a zone target
- **WHEN** a step targets a heart-rate zone and no heart rate is available
- **THEN** the target is shown without feedback and no alert is given

### Requirement: End of the session
When the last step ends, the app SHALL announce that the session is complete and the run SHALL continue as a free run until the runner stops it. Stopping the run before the last step SHALL be possible at any time.

#### Scenario: Session complete
- **WHEN** the cool-down ends
- **THEN** the completion is announced and time and distance keep counting until the runner stops

### Requirement: Session survives interruption
If the app stops unexpectedly during a run with a session and the runner resumes the run, the session SHALL resume in the step and at the progress it had reached.

#### Scenario: App killed in the fourth repeat
- **WHEN** the app is killed during the fourth effort and the runner resumes the run
- **THEN** the session continues in the fourth effort with at most 10 seconds of progress lost

### Requirement: Steps are recorded
Each executed step SHALL be recorded as a lap of the run, so that the activity file and the server's laps follow the session. The run SHALL carry the identity of the session, its name and its steps as they were when it was run, and the server SHALL keep them with the activity.

#### Scenario: Server reads back a session run
- **WHEN** a run of "6 × 400 m" with warm-up and cool-down is saved, synced and opened on the web
- **THEN** the activity has fourteen laps matching the fourteen steps, each labelled with its step

#### Scenario: Phone summary
- **WHEN** the run ends
- **THEN** the end-of-run summary lists the steps with their time, distance and pace against target
