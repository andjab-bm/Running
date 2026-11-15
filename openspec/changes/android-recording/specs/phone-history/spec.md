# Spec Delta

## Purpose

Lets the runner look back at their runs on the phone, see whether each one has reached the home server, and get the recorded file out, even when the server is not reachable.

## ADDED Requirements

### Requirement: Run list
The phone app SHALL list the runs recorded on the phone, newest first, each with its date, title, distance, moving time and average pace. The list SHALL be available without network access.

#### Scenario: Offline
- **WHEN** the runner opens the history with no network
- **THEN** the runs recorded on the phone are listed

#### Scenario: No run yet
- **WHEN** no run has been recorded
- **THEN** an empty state invites the runner to record a first run

### Requirement: Runs from the server
When the server is reachable, the list SHALL also show the active profile's activities that were not recorded on this phone, such as imported history, and SHALL keep the last retrieved list for offline viewing. A run present both on the phone and on the server SHALL appear once.

#### Scenario: History imported on the web
- **WHEN** the profile holds activities imported through the web app and the server is reachable
- **THEN** they appear in the phone list in date order with the phone's own runs

#### Scenario: Synced run
- **WHEN** a run recorded on the phone has been uploaded
- **THEN** it appears once in the list

### Requirement: Basic totals
The history SHALL show the active profile's totals for the current week and the current month: number of runs, distance and moving time.

#### Scenario: Three runs this week
- **WHEN** the runner has run 5 km, 8 km and 10 km since Monday
- **THEN** the weekly total shows 3 runs and 23 km

### Requirement: Run page
Opening a run recorded on the phone SHALL show its summary figures, its laps, a drawing of its trace, and its title, perceived effort and note. Opening a run that exists only on the server SHALL show its summary figures.

#### Scenario: Open a recorded run
- **WHEN** the runner opens a run recorded with three laps
- **THEN** the page shows distance, moving time, average pace, elevation gain, the three laps and the shape of the trace

### Requirement: Edit a run's notes
The runner SHALL be able to change the title, perceived effort and note of any run recorded on the phone, from its run page, at any time after saving it. The recorded data itself SHALL NOT be editable.

#### Scenario: Change the effort later
- **WHEN** the runner opens a run saved with effort 5, sets it to 7 and saves
- **THEN** the run page and the list show effort 7

#### Scenario: Cancel
- **WHEN** the runner changes the title and cancels
- **THEN** the run keeps its previous title

### Requirement: Sync state
Each run recorded on the phone SHALL show whether it is pending, synced or failed, and a failed run SHALL show the reason and offer a retry.

#### Scenario: Pending run
- **WHEN** a run was saved away from home
- **THEN** it is marked as pending in the list

#### Scenario: Retry a failed run
- **WHEN** the runner retries a failed run while the server is reachable
- **THEN** the upload is attempted again and the state is updated

### Requirement: Share the recorded file
The runner SHALL be able to share or save the FIT file of any run recorded on the phone through the phone's standard sharing sheet.

#### Scenario: Save to files
- **WHEN** the runner shares a run and chooses a file destination
- **THEN** a `.fit` file of that run is written there

### Requirement: Delete a local run
The runner SHALL be able to delete a run from the phone after confirmation. Deleting a synced run from the phone SHALL NOT delete it from the server, and the confirmation SHALL say so. Deleting a pending run SHALL warn that it was never uploaded.

#### Scenario: Delete a synced run
- **WHEN** the runner deletes a synced run from the phone and confirms
- **THEN** its recorded data is removed from the phone
- **AND** the activity remains on the server

#### Scenario: Delete a pending run
- **WHEN** the runner asks to delete a pending run
- **THEN** the confirmation states that the run has not been uploaded and will be lost
