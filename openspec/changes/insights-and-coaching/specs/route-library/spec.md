# Spec Delta

## Purpose

Keeps the routes a runner wants to follow — imported from `.gpx` files or taken from a past run — with their length and profile, ready to be sent to the phone or exported.

## ADDED Requirements

### Requirement: Import a route from a GPX file
The runner SHALL be able to add a route to the active profile from a `.gpx` file containing a route or a track, with or without timestamps. The route SHALL keep the path and, when present, the elevations; times, heart rate and other recorded data SHALL be ignored. A file with several routes or tracks SHALL give one route per route or track.

#### Scenario: Route planned on another site
- **WHEN** the runner imports a `.gpx` route of 9.4 km without timestamps
- **THEN** a route of 9.4 km appears in the library, named after the file's route name

#### Scenario: Unusable file
- **WHEN** the runner imports a file that is not GPX or holds fewer than two points
- **THEN** no route is created and the reason is stated

#### Scenario: Timestamp-less file dropped on the activity import
- **WHEN** a `.gpx` file without timestamps is submitted as an activity
- **THEN** the refusal offers to add it to the routes instead

### Requirement: Make a route from an activity
The runner SHALL be able to create a route from any activity of the active profile, optionally from a selected portion of it. The route SHALL carry only the path and elevations.

#### Scenario: Save last Sunday's loop
- **WHEN** the runner creates a route from a 12 km activity
- **THEN** a 12 km route exists that carries no time, pace or heart rate

### Requirement: Route figures
Each route SHALL show its distance, elevation gain and loss, and whether it is a loop, computed the same way as for activities. A route without elevation data SHALL show gain and loss as unavailable.

#### Scenario: Loop
- **WHEN** a route ends within 100 m of where it starts
- **THEN** it is shown as a loop

#### Scenario: No elevation in the file
- **WHEN** an imported route has no elevation
- **THEN** its gain and loss are shown as unavailable and gradient-adjusted pacing is not offered for it

### Requirement: Route library and route page
The web app SHALL list the active profile's routes with a trace thumbnail, name, distance and elevation gain, and SHALL show each route on a page with a map, the elevation profile synchronised with the map on hover, and the activities already run with that route.

#### Scenario: Hover the profile
- **WHEN** the runner moves over the elevation profile of a route
- **THEN** the corresponding point is marked on the map

#### Scenario: Empty library
- **WHEN** the profile has no route
- **THEN** the page invites importing a `.gpx` file or creating a route from an activity

### Requirement: Rename, reverse, delete
The runner SHALL be able to rename a route, create its reverse as a new route, and delete it after confirmation. Deleting a route SHALL NOT alter the activities run with it.

#### Scenario: Reverse direction
- **WHEN** the runner reverses a route with 120 m of gain and 80 m of loss
- **THEN** a new route exists with the same distance, 80 m of gain and 120 m of loss

### Requirement: Export a route
The runner SHALL be able to download any route as a GPX 1.1 file containing the path and elevations, readable by other applications and devices.

#### Scenario: Round trip
- **WHEN** the runner exports a route and imports the file again
- **THEN** the new route has the same distance within 0.5 %

### Requirement: Routes are per profile
Routes SHALL belong to one profile, and SHALL be copyable to another profile.

#### Scenario: Copy to a partner
- **WHEN** the runner copies a route to another profile
- **THEN** it appears in that profile's library and each copy can be deleted without affecting the other

### Requirement: Route followed, on the activity page
An activity that was run following a route SHALL name that route on its page, with a link to it while it exists.

#### Scenario: Run with a route
- **WHEN** the runner opens an activity recorded while following "Boucle du canal"
- **THEN** the page names that route and links to it
