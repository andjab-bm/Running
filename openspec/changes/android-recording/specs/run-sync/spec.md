# Spec Delta

## Purpose

Gets runs recorded on the phone onto the home server automatically and only over the home network, into the right athlete profile, without any cloud relay or account.

## ADDED Requirements

### Requirement: Pair with the home server
The runner SHALL be able to enter the address of the home server in the phone app. The app SHALL check that the address answers as this product's server and SHALL report clearly when it does not. Recording SHALL work without any server configured.

#### Scenario: Valid address
- **WHEN** the runner enters the address of a running server on the same network
- **THEN** the app confirms the connection and shows the server version

#### Scenario: Wrong address
- **WHEN** the runner enters an address where nothing answers or another service answers
- **THEN** the app reports that no server was found there and keeps the previous setting

#### Scenario: No server configured
- **WHEN** no server has been configured
- **THEN** runs can still be recorded and are kept on the phone

### Requirement: Choose the target profile
Once paired, the app SHALL list the server's athlete profiles and let the runner choose the one their runs go to. The chosen profile's units and heart-rate zones SHALL be retrieved and used on the phone, and refreshed whenever the server is reached.

#### Scenario: Choose a profile
- **WHEN** the runner selects "Sarah" among the server's profiles
- **THEN** later runs are uploaded to Sarah's profile
- **AND** live screens use Sarah's units and zones

#### Scenario: Profile deleted on the server
- **WHEN** the chosen profile no longer exists on the server at upload time
- **THEN** nothing is uploaded, the runs stay pending, and the app asks the runner to choose a profile

### Requirement: Automatic upload on the home network
The app SHALL upload every saved, not yet uploaded run without user action when the phone is on an unmetered network and the configured server answers. It SHALL NOT upload over a metered connection. Upload SHALL happen in the background, without the app being open.

#### Scenario: Coming home
- **WHEN** the phone joins the home Wi-Fi after a run, with the app closed
- **THEN** the run appears on the server within 15 minutes

#### Scenario: Away from home
- **WHEN** the phone is on mobile data or on a Wi-Fi where the server does not answer
- **THEN** nothing is sent and the run stays pending

#### Scenario: Several pending runs
- **WHEN** three runs were recorded while away and the phone comes home
- **THEN** all three are uploaded, oldest first

### Requirement: Manual sync
The runner SHALL be able to trigger a sync from the app and SHALL see its outcome.

#### Scenario: Sync now
- **WHEN** the runner triggers a sync while the server is reachable
- **THEN** pending runs are uploaded and their state updates on screen

#### Scenario: Server unreachable
- **WHEN** the runner triggers a sync while the server does not answer
- **THEN** the app states that the server cannot be reached and runs stay pending

### Requirement: Safe retries
An upload that fails or is interrupted SHALL be retried later without user action. A run SHALL never appear twice in the server profile, whatever the number of retries. A run the server already holds SHALL be treated as uploaded.

#### Scenario: Connection lost mid-upload
- **WHEN** the Wi-Fi drops during an upload and returns later
- **THEN** the run is uploaded on a later attempt and exists once on the server

#### Scenario: Already on the server
- **WHEN** the server answers that the run is a duplicate of an existing activity
- **THEN** the run is marked as synced and linked to that activity

#### Scenario: File refused
- **WHEN** the server refuses a run's file as unusable
- **THEN** the run is marked as failed with the server's reason, is kept on the phone, and is not retried automatically

### Requirement: Carry the end-of-run notes
When a run is uploaded, the title, perceived effort and note entered on the phone SHALL be applied to the server activity. A title left empty SHALL leave the server's default title in place.

#### Scenario: Effort entered after the run
- **WHEN** a run saved with perceived effort 7 and the note "Jambes lourdes" is uploaded
- **THEN** the activity on the server shows effort 7 and that note

#### Scenario: No title entered
- **WHEN** a run saved without a title is uploaded
- **THEN** the server activity keeps its automatically generated title

### Requirement: Carry later edits
When the title, perceived effort or note of an already uploaded run is changed on the phone, the change SHALL be applied to the server activity at the next sync, and the run SHALL be shown as pending until then. Edits made in the web app SHALL NOT be overwritten unless the runner edits the run on the phone afterwards.

#### Scenario: Edit a synced run at home
- **WHEN** the runner changes the note of a synced run while the server is reachable
- **THEN** the server activity shows the new note after the next sync and the run is marked synced again

#### Scenario: Edit away from home
- **WHEN** the runner changes the title of a synced run while away
- **THEN** the run is marked pending and the server activity is updated once the phone is home

### Requirement: Local-only data flow
The phone app SHALL send run data only to the server address configured by the runner. It SHALL NOT contact any analytics, crash-reporting, advertising or other third-party service.

#### Scenario: Network inspection
- **WHEN** the phone's traffic is observed during a run and the following sync
- **THEN** the app's only connections are to the configured server
