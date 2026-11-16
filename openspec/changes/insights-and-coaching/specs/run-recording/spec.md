# Spec Delta

## MODIFIED Requirements

### Requirement: End-of-run summary
After stopping, the app SHALL show a summary — distance, moving time, average pace, elevation gain, average heart rate when available, laps — and SHALL let the runner save the run, optionally with a title, a perceived effort from 1 to 10 and a note, discard it after confirmation, or resume it. Once a run is saved, its title, perceived effort and note SHALL remain editable and the run SHALL NOT be resumable.

#### Scenario: Save with perceived effort
- **WHEN** the runner sets a perceived effort of 6 and saves
- **THEN** the run appears in the phone history with that effort

#### Scenario: Discard
- **WHEN** the runner chooses to discard and confirms
- **THEN** the run is deleted from the phone and is never sent to the server

#### Scenario: Very short run
- **WHEN** a run is stopped after less than 10 seconds or 20 metres
- **THEN** the app proposes discarding it by default

#### Scenario: Saved run cannot be resumed
- **WHEN** the runner opens a saved run from the history
- **THEN** its title, effort and note can be edited and no resume action is offered

## ADDED Requirements

### Requirement: Resume after stopping
From the end-of-run summary, as long as the run has been neither saved nor discarded, the runner SHALL be able to resume it. The run SHALL then continue as the same run: the time between the stop and the resume SHALL count as a pause, distance and moving time SHALL NOT increase during it, the current lap SHALL continue, and a structured session, a route and a pace target SHALL continue where they were. What was typed in the summary SHALL be kept for the next stop.

#### Scenario: Stopped by mistake
- **WHEN** the runner stops at 4.00 km, sees the summary and resumes two minutes later
- **THEN** the run continues from 4.00 km with the same moving time
- **AND** elapsed time has advanced by two minutes

#### Scenario: One activity, not two
- **WHEN** a run was stopped, resumed, stopped again and saved
- **THEN** a single run is saved, and the server reports the stop as a pause

#### Scenario: Resume in the middle of a session
- **WHEN** the runner stops during the fourth repeat of a session and resumes
- **THEN** the session continues in the fourth repeat at the progress it had

#### Scenario: Moved while stopped
- **WHEN** the runner walks 300 m between the stop and the resume
- **THEN** those 300 m are not added to the distance

#### Scenario: App closed on the summary
- **WHEN** the app is closed while the summary is shown and reopened later
- **THEN** the summary is shown again with save, discard and resume

### Requirement: Prepare a run with a plan
The ready screen SHALL let the runner attach to the run, each optionally: one structured session, one route, and a pace target. What is attached SHALL be shown on the ready screen before starting, and SHALL be removable. Nothing attached SHALL give a free run identical to the existing behaviour.

#### Scenario: Session and route together
- **WHEN** the runner attaches a session and a route and starts
- **THEN** the run is guided through the session and the map screen follows the route

#### Scenario: Remove before starting
- **WHEN** the runner removes the attached route on the ready screen
- **THEN** the run starts without a route

#### Scenario: Remembered choice is not silent
- **WHEN** the runner opens the ready screen the day after a guided run
- **THEN** nothing is attached until the runner chooses again

### Requirement: Start on request from the watch
When the "start from the watch" option is enabled, the phone SHALL be able to begin recording a free run on request from the watch while its app is not open and its screen is off, with the same recording guarantees as a run started on the phone. The option SHALL be off by default, and enabling it SHALL explain and request the permissions it needs; if they are refused the option SHALL stay off.

#### Scenario: Enable the option
- **WHEN** the runner enables the option and grants location access at all times
- **THEN** the option is on and the watch offers to start a run

#### Scenario: Permission refused
- **WHEN** the runner declines location access at all times
- **THEN** the option stays off and starting a run from the phone works as before

#### Scenario: Run started from the wrist
- **WHEN** a run is started from the watch with the phone locked in a pocket
- **THEN** the phone records it, shows its run notification, and the live screen is shown when the phone is woken

#### Scenario: Permission revoked later
- **WHEN** the permission was revoked in the system settings and the watch requests a start
- **THEN** the phone does not start, tells the watch so, and shows a notification explaining what to restore
