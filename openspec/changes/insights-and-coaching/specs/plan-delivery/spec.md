# Spec Delta

## Purpose

Gets the sessions and routes a runner has chosen on the web onto their phone over the home network, without cloud or manual file handling, and keeps both sides aware of what the phone holds.

## ADDED Requirements

### Requirement: Mark for the phone
The runner SHALL be able to mark any session or route of the active profile as "on the phone", and to unmark it, from the library and from its page.

#### Scenario: Send a session
- **WHEN** the runner marks "6 × 400 m" for the phone
- **THEN** the session is shown as waiting for the phone until the phone has received it

### Requirement: Delivery at home sync
Whenever the phone reaches the home server under the conditions of run sync, it SHALL bring its copy of the marked sessions and routes of its chosen profile in line with the server: new ones are added, changed ones replaced, unmarked or deleted ones removed. This SHALL need no action on the phone and SHALL work with the app closed.

#### Scenario: Prepared the evening before
- **WHEN** the runner marks a session and a route in the evening with the phone on the home Wi-Fi
- **THEN** both are on the phone within 15 minutes, and usable the next morning without network

#### Scenario: Edited after delivery
- **WHEN** the runner changes the pace target of a delivered session on the web
- **THEN** the phone holds the new version after the next sync

#### Scenario: Unmarked
- **WHEN** the runner unmarks a route
- **THEN** it is removed from the phone at the next sync

#### Scenario: Away from home
- **WHEN** the phone is on mobile data
- **THEN** nothing is fetched and the sessions and routes already on the phone stay usable

### Requirement: Never disturb a run in progress
A session or route in use by a run that is prepared or in progress SHALL NOT be replaced or removed until that run has ended.

#### Scenario: Sync while preparing
- **WHEN** a sync removes a route from the phone while that route is selected on the ready screen
- **THEN** the run can still be started and completed with it, and the route leaves the phone afterwards

### Requirement: Delivery state on the web
The web app SHALL show, for each marked session or route, whether it is waiting for the phone or on the phone, and since when. It SHALL show when the phone last synced with the profile.

#### Scenario: Delivered
- **WHEN** the phone has fetched a marked session
- **THEN** the web shows it as on the phone with the time of delivery

#### Scenario: Edited since delivery
- **WHEN** a delivered session is edited on the web and the phone has not synced since
- **THEN** it is shown as waiting again

### Requirement: Sessions and routes on the phone
The phone app SHALL list the sessions and routes it holds, with each session's outline and totals and each route's distance, elevation gain and trace, and SHALL let the runner choose among them when preparing a run. They SHALL NOT be editable on the phone.

#### Scenario: Nothing delivered
- **WHEN** the phone holds no session and no route
- **THEN** the app explains that they are prepared in the web app and arrive at home, and a free run can still be started

#### Scenario: Changing profile
- **WHEN** the runner chooses another profile on the phone
- **THEN** the previous profile's sessions and routes are removed and the new profile's are fetched at the next sync

### Requirement: Manual refresh
The manual sync action of the phone SHALL also refresh sessions and routes, and SHALL report the outcome.

#### Scenario: Just marked, leaving now
- **WHEN** the runner marks a session on the web and triggers a sync on the phone at home
- **THEN** the session is on the phone when the sync ends

### Requirement: Delivery stays local
Sessions and routes SHALL travel only between the home server and the phone, on the network conditions of run sync, and from the phone to the watch only as the live information of the run in progress.

#### Scenario: Network inspection
- **WHEN** the phone's traffic is observed during a delivery
- **THEN** sessions and routes come only from the configured server
