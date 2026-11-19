# Spec Delta

## Purpose

Gives the runner a map during the run — where they are, where they have been and, when following a route, where to go — and warns them when they leave the route.

## ADDED Requirements

### Requirement: Map screen
During a run the live screens SHALL include a map screen showing a basemap, the trace recorded so far and the runner's current position, centred on the runner. Elapsed time, distance and current pace SHALL remain readable on that screen, and the run controls SHALL remain available on it.

#### Scenario: Free run
- **WHEN** the runner swipes to the map screen 2 km into a free run
- **THEN** the map shows the surroundings, the 2 km of trace and the current position

#### Scenario: Controls on the map
- **WHEN** the runner is on the map screen
- **THEN** pause, lap and stop are available without changing screen

### Requirement: Choose a route to follow
On the ready screen the runner SHALL be able to choose one of the routes held by the phone, or none. The ready screen SHALL show the route's distance and how far the runner is from its start.

#### Scenario: Far from the start
- **WHEN** the runner selects a route whose start is 600 m away
- **THEN** the ready screen states that distance and the run can still be started

### Requirement: Follow a route
When a route is selected, the map screen SHALL draw it distinctly from the recorded trace, with its start and end, and SHALL show the distance remaining to its end and the direction of travel. The part of the route already covered SHALL be distinguishable from the part ahead.

#### Scenario: Halfway
- **WHEN** the runner has followed 5 km of a 10 km route
- **THEN** the map shows 5 km remaining and the part ahead stands out from the part done

#### Scenario: Route that crosses itself
- **WHEN** the runner reaches a point where a figure-of-eight route crosses itself
- **THEN** remaining distance follows the order of the route and does not jump to the later passage

#### Scenario: Joining the route partway
- **WHEN** the runner starts the run 1 km after the start of the route, on the route
- **THEN** following begins from that point and the remaining distance is counted from there

### Requirement: Map orientation and zoom
The runner SHALL be able to switch the map between north up and direction of travel up, to zoom, and to see the whole route at once. After the runner moves the map by hand, it SHALL return to following the position on request, and by itself after 10 seconds.

#### Scenario: Look ahead
- **WHEN** the runner drags the map to look at the next junction and then leaves it alone
- **THEN** the map returns to the runner's position within 10 seconds

### Requirement: Off-route alert
When the runner is more than 40 m from the route for more than 10 seconds, the app SHALL give an off-route alert through the enabled channels, show the distance to the route on the map screen, and signal the return to the route. The alert SHALL be repeated at most once per minute. Off-route alerts SHALL be a setting, enabled by default.

#### Scenario: Missed turn
- **WHEN** the runner runs 60 m past a turn of the route and keeps going
- **THEN** an off-route alert is given within about 15 seconds of leaving the route

#### Scenario: Back on the route
- **WHEN** the runner rejoins the route
- **THEN** the return is signalled once and the remaining distance resumes

#### Scenario: Position inaccuracy
- **WHEN** the position wanders 20 m to the side for a few seconds in a narrow street
- **THEN** no alert is given

### Requirement: End of the route
When the runner reaches the end of the route, the app SHALL announce it. The run SHALL continue until the runner stops it.

#### Scenario: Arrival
- **WHEN** the runner arrives within 30 m of the route's end after following it
- **THEN** the arrival is announced and recording continues

### Requirement: Basemap source and consent
The basemap SHALL be loaded from the map style configured on the home server, as last retrieved by the phone, and from nowhere else. A "basemap during the run" setting SHALL control it; the setting SHALL state that map requests reveal the viewed area to the map provider. With the setting off, or no map style configured, the map screen and the mini-map SHALL show the route and the trace on a plain background and make no network request.

#### Scenario: Basemap on
- **WHEN** the setting is on and the phone has mobile data
- **THEN** the map screen shows the basemap around the runner

#### Scenario: Basemap off
- **WHEN** the setting is off
- **THEN** the route and trace are drawn on a plain background and the app contacts no map host

#### Scenario: Server has no map style
- **WHEN** the home server is configured without a map style
- **THEN** the phone shows no basemap, as with the setting off

### Requirement: Usable without network
The map screen SHALL keep working without network: the route, the trace, the position, remaining distance and off-route alerts SHALL NOT depend on the basemap, and areas of the basemap already seen recently SHALL be shown again without network.

#### Scenario: No coverage in the forest
- **WHEN** the phone loses its data connection during a run
- **THEN** the route, the trace and the alerts keep working, and the basemap shows what it has and stays blank elsewhere

### Requirement: Map only when looked at
The map screen SHALL NOT load basemap data, nor redraw, while the screen is off or another live screen is shown. The mini-map SHALL load basemap data only while the screen is on and the main screen is shown, and only when the area it shows changes. Recording a run with the screen off SHALL therefore cost no more battery or data than without a map.

#### Scenario: Screen off for the whole run
- **WHEN** a run is recorded with a route selected and the screen off throughout
- **THEN** no map request is made and off-route alerts still work

### Requirement: Map follows the theme
The map screen SHALL follow the app's light or dark theme, with the route and trace colours of the approved design in both.

#### Scenario: Dark theme
- **WHEN** the app is in the dark theme
- **THEN** the map uses the dark basemap and the route and trace remain clearly visible
