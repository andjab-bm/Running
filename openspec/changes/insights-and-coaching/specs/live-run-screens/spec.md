# Spec Delta

## MODIFIED Requirements

### Requirement: Main metrics screen
During a run the phone SHALL show a main screen with large, glanceable figures in four fields. By default it SHALL show average pace with the per-kilometre pace bars at the top, cadence at middle left, heart rate at middle right, and time with distance and the mini-map area at the bottom, in the units of the active profile. The time of that bottom field SHALL be the moving time, pauses excluded, and SHALL be labelled "Temps". A value that is not available SHALL be shown as a placeholder, never as zero.

#### Scenario: Default layout
- **WHEN** a run is in progress with the watch joined and no field has been changed
- **THEN** the main screen shows average pace with pace bars, cadence, heart rate, and time with distance beside the mini-map area, updated at least once per second

#### Scenario: Time stops during a pause
- **WHEN** the run is paused for one minute
- **THEN** the time of the bottom field does not advance during that minute

#### Scenario: No heart rate
- **WHEN** a run is in progress without the watch
- **THEN** the heart-rate field shows a placeholder

#### Scenario: Imperial profile
- **WHEN** the active profile uses imperial units
- **THEN** distance is shown in miles and pace in min/mi

### Requirement: Mini-map area
The combined bottom field SHALL show moving time and distance beside an area reserved for a mini-map. That area SHALL show the shape of the trace recorded so far and, when a route is followed, the route under it. It SHALL show the basemap under them under the same conditions as the map screen — the "basemap during the run" setting on and a map style configured on the home server — and otherwise a plain background with no network request. Touching it SHALL open the map screen.

#### Scenario: Trace so far
- **WHEN** the runner has covered 2 km
- **THEN** the mini-map area shows the shape of those 2 km, ending at the current position

#### Scenario: Tap the mini-map
- **WHEN** the runner taps the mini-map area with the screen unlocked
- **THEN** the map screen is shown

#### Scenario: Route in the mini-map
- **WHEN** a route is followed
- **THEN** the mini-map area shows the shape of the route with the trace so far over it

#### Scenario: Basemap in the mini-map
- **WHEN** the "basemap during the run" setting is on and the server has a map style
- **THEN** the mini-map area shows the basemap of the area under the trace

#### Scenario: Mini-map without basemap
- **WHEN** the setting is off or the server has no map style
- **THEN** the mini-map area draws the trace on a plain background and makes no network request

### Requirement: Choose the displayed fields
The runner SHALL be able to choose, in the settings, which metric appears in each field of the main screen from: moving time, elapsed time, distance, current pace, average pace, lap pace, heart rate, cadence, elevation gain, time of day, time ahead or behind the pace target, and target pace; the bottom field SHALL also offer the combined field, named "Temps, distance et mini-carte". The choice SHALL be remembered.

#### Scenario: Replace a field
- **WHEN** the runner replaces cadence by current pace in the settings
- **THEN** the next run shows current pace in that field

#### Scenario: Name of the combined field
- **WHEN** the runner opens the choice for the bottom field
- **THEN** the combined field is offered as "Temps, distance et mini-carte"

#### Scenario: Pacer off
- **WHEN** the pacer is off and a field is set to ahead/behind
- **THEN** that field shows a placeholder

## ADDED Requirements

### Requirement: Live screens adapt to the run
The live screens, reached by horizontal swipes, SHALL be: the step screen when the run has a structured session, the main metrics screen, the current-lap screen, and the map screen. A dot indicator SHALL show how many screens there are and which is shown. Touch lock, the paused state and the run controls SHALL behave the same on every screen.

#### Scenario: Free run
- **WHEN** a free run is in progress
- **THEN** three screens are available: main metrics, current lap and map

#### Scenario: Guided run
- **WHEN** a run with a session is in progress
- **THEN** four screens are available and the step screen is first

#### Scenario: Locked on the map
- **WHEN** the screen is locked while the map screen is shown
- **THEN** taps and swipes neither move the map nor change screen

### Requirement: Pacer on the live screens
While the pacer is on, the time ahead or behind SHALL be shown on the main metrics screen without the runner having to configure a field.

#### Scenario: Paced run with the default layout
- **WHEN** a paced run is in progress and no field was changed
- **THEN** the main screen shows the ahead/behind figure alongside its usual fields
