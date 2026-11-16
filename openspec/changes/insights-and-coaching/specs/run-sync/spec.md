# Spec Delta

## MODIFIED Requirements

### Requirement: Local-only data flow
The phone app SHALL send run data only to the server address configured by the runner. It SHALL NOT contact any analytics, crash-reporting, advertising or other third-party service. The only other host it may contact is the map provider named by the home server's map style, solely to load the basemap of the map screen while that screen is shown and the basemap setting is on; no run data, identifier or account SHALL be sent to it.

#### Scenario: Network inspection
- **WHEN** the phone's traffic is observed during a run and the following sync, with the basemap setting off
- **THEN** the app's only connections are to the configured server

#### Scenario: Network inspection with the basemap on
- **WHEN** the phone's traffic is observed during a run in which the map screen was shown, with the basemap setting on
- **THEN** the app's only connections are to the configured server and to the map provider of the server's map style, and the latter are map requests only

## ADDED Requirements

### Requirement: Carry the coaching context
When a run done with a structured session, a route or a pace target is uploaded, the session's identity, name and steps as run, the route's identity and name, and the pace target with its final gap SHALL be applied to the server activity, with the same retry and later-edit rules as the end-of-run notes.

#### Scenario: Session run away from home
- **WHEN** a run done with "6 × 400 m" is uploaded once the phone is home
- **THEN** the server activity shows that session and its steps

#### Scenario: Session deleted meanwhile
- **WHEN** the session was deleted on the web between the run and the upload
- **THEN** the activity still shows the session's name and steps as they were run
