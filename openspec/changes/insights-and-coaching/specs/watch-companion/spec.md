# Spec Delta

## MODIFIED Requirements

### Requirement: Join a session
Opening the watch app while a session is prepared or running on the phone SHALL join that session. When no session exists on the phone, the watch app SHALL say so; it SHALL offer to start a run from the wrist when that option is enabled on the phone, and otherwise SHALL ask the runner to prepare the run on the phone.

#### Scenario: Session prepared on the phone
- **WHEN** the runner opens the watch app while the phone shows the ready screen
- **THEN** the watch shows that it has joined and the phone shows the watch as connected

#### Scenario: Nothing prepared, start from the wrist disabled
- **WHEN** the runner opens the watch app with no session on the phone and the option disabled
- **THEN** the watch asks the runner to prepare the run on the phone

#### Scenario: Nothing prepared, start from the wrist enabled
- **WHEN** the runner opens the watch app with no session on the phone and the option enabled
- **THEN** the watch offers to start a run

#### Scenario: Phone out of reach
- **WHEN** the watch app is opened while the phone is not connected
- **THEN** the watch states that the phone is not reachable

### Requirement: Glanceable metrics
During a run the watch SHALL show a metrics screen with current pace as the largest, central figure, moving time above it, and distance and heart rate below it, and SHALL make the paused state obvious. The controls SHALL be one swipe away. During a run with a structured session, a pace target or a route, a coaching screen SHALL also be one swipe away. Apart from its tile, the watch app SHALL have no other screens, settings or complication.

#### Scenario: Glance while running
- **WHEN** the runner raises the wrist during a run
- **THEN** pace is shown largest in the centre, with time above and distance and heart rate below, at most 2 seconds old

#### Scenario: Paused
- **WHEN** the run is paused from the phone
- **THEN** the watch screen shows the paused state within 2 seconds

#### Scenario: Free run
- **WHEN** the run has no session, no pace target and no route
- **THEN** the watch has only the metrics and controls screens

### Requirement: End with the session
When the run is saved or discarded on the phone, or the phone has been unreachable for 10 minutes, the watch SHALL stop measuring heart rate and release its sensors. While a stopped run is still waiting on the phone's summary, the watch SHALL keep measuring for up to 5 minutes so that a resumed run keeps its heart rate, and SHALL show the run as stopped. The watch SHALL NOT keep any run data after the session.

#### Scenario: Saved on the phone
- **WHEN** the runner stops the run and saves it on the phone
- **THEN** the watch stops measuring within 10 seconds of the save and shows the run as finished

#### Scenario: Resumed after a stop
- **WHEN** the runner stops the run and resumes it on the phone one minute later
- **THEN** the watch shows the run as running again and the saved run has heart rate after the resume

#### Scenario: Summary left open
- **WHEN** the run is stopped and neither saved, discarded nor resumed for 5 minutes
- **THEN** the watch stops measuring

## ADDED Requirements

### Requirement: Watch tile
The watch app SHALL provide a tile the runner can add to the watch's tiles. During a run the tile SHALL show the run's state, moving time, distance and heart rate, at most one minute old. Outside a run it SHALL offer to start one. Touching the tile SHALL open the watch app, directly on the start when the start control was touched. The tile SHALL NOT measure anything by itself.

#### Scenario: Glance at the tile during a run
- **WHEN** the runner swipes to the tile 20 minutes into a run joined by the watch
- **THEN** the tile shows the run as running with its time, distance and heart rate

#### Scenario: Start from the tile
- **WHEN** the runner touches the start control of the tile while no run exists
- **THEN** the watch app opens and starts or joins a run exactly as if opened by hand

#### Scenario: Open the app from the tile
- **WHEN** the runner touches the tile during a run
- **THEN** the watch app opens on its metrics screen

#### Scenario: Tile never added
- **WHEN** the runner has not added the tile
- **THEN** the app behaves as before and nothing runs for the tile

#### Scenario: Run not joined by the watch
- **WHEN** a run is recorded on the phone and the watch app was never opened
- **THEN** the tile offers to open the app and shows no figure it does not have

### Requirement: Open the app on the other device
The phone's ready screen SHALL offer to open the watch app when the watch is connected and has not joined, and the watch app SHALL offer to open the phone app when nothing is prepared on the phone. When the other device cannot be reached or does not open the app, the runner SHALL be told to open it by hand.

#### Scenario: Open the watch app from the phone
- **WHEN** the runner touches "open on the watch" on the ready screen with the watch on the wrist
- **THEN** the watch app opens and joins the session without the runner touching the watch

#### Scenario: Open the phone app from the watch
- **WHEN** the runner touches "open on the phone" on the watch with nothing prepared
- **THEN** the phone app comes to the ready screen, once the phone is unlocked if it was locked

#### Scenario: Watch out of reach
- **WHEN** the runner touches "open on the watch" while the watch is not connected
- **THEN** the phone says the watch could not be reached

### Requirement: Start a run from the wrist
When the option is enabled on the phone, the runner SHALL be able to start a run from the watch without touching the phone. The phone SHALL then start recording a free run with its usual settings, and the watch SHALL join it. The watch SHALL show the run as started only once the phone has confirmed it, and SHALL say so when the phone could not start.

#### Scenario: Phone in the pocket, app closed
- **WHEN** the runner presses start on the watch with the phone in reach and its app not open
- **THEN** the phone starts recording within 5 seconds and the watch shows the running metrics

#### Scenario: Position not found yet
- **WHEN** the run is started from the wrist before the phone has found its position
- **THEN** time is counted at once, distance once the position is found, and the watch shows that the position is being searched

#### Scenario: Phone cannot start
- **WHEN** the phone refuses or does not answer within 10 seconds
- **THEN** the watch says the run was not started and asks to start it on the phone

### Requirement: Coaching on the wrist
During a run with a structured session, a pace target or a route, the watch SHALL vibrate at step changes, target and pacer alerts and off-route alerts, with a distinct pattern per kind, including when its screen is off. Its coaching screen SHALL show the current step with what remains of it and its target, or the time ahead or behind the pace target. Watch vibrations SHALL be a setting on the phone, enabled by default.

#### Scenario: Step change with the wrist down
- **WHEN** a step changes while the watch screen is off
- **THEN** the watch vibrates with the step-change pattern within 2 seconds

#### Scenario: Glance during an effort
- **WHEN** the runner opens the coaching screen during a 400 m effort
- **THEN** it shows the distance remaining in the step and the target pace

#### Scenario: Paced run
- **WHEN** the runner opens the coaching screen during a paced run
- **THEN** it shows the time ahead or behind the target

#### Scenario: Link lost
- **WHEN** the phone is out of reach during a session
- **THEN** the watch gives no coaching vibration, shows the phone as unreachable, and the session continues on the phone

### Requirement: Coaching stays light
Coaching on the watch SHALL NOT add any sensor, storage or network use on the watch, and a one-hour guided session SHALL stay within the battery budget of the watch app.

#### Scenario: One-hour interval session
- **WHEN** a one-hour session with 30 step changes is run with the watch joined
- **THEN** the watch battery level has dropped by 12 points or fewer
