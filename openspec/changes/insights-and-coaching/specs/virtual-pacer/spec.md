# Spec Delta

## Purpose

Lets the runner run against a target — a pace, or a finishing time on a route — and see at any moment how far ahead or behind they are, with the target effort spread sensibly over climbs and descents when the route is known.

## ADDED Requirements

### Requirement: Set a pace target
On the ready screen the runner SHALL be able to enable the pacer with a target pace in the profile's units, or, when a route is selected, with a target finishing time for that route. The last target used SHALL be proposed again. The pacer SHALL be off by default.

#### Scenario: Fixed pace
- **WHEN** the runner enables the pacer at 6:00 min/km and starts a free run
- **THEN** the run is paced against 6:00 min/km

#### Scenario: Target time on a route
- **WHEN** the runner selects a 10 km route and enters a target of 58:00
- **THEN** the run is paced to finish that route in 58:00

### Requirement: Ahead or behind
While the pacer is on, the app SHALL show the time by which the runner is ahead of or behind the target at their current distance, signed and updated at least every second, with the two cases clearly distinguishable by more than colour. The comparison SHALL use moving time, so that a pause does not put the runner behind.

#### Scenario: Slightly fast
- **WHEN** the runner has covered 3.00 km in 17:40 of moving time against a 6:00 min/km target
- **THEN** the pacer shows 20 seconds ahead

#### Scenario: Waiting at a crossing
- **WHEN** the run is paused for one minute
- **THEN** the ahead/behind figure is the same when the run resumes

#### Scenario: Before the position is found
- **WHEN** the run has started and no distance has been counted yet
- **THEN** the pacer shows a placeholder rather than an increasing delay

### Requirement: Gradient-adjusted strategy on a route
When the pacer has a target on a route that has elevation data, the runner SHALL be able to choose a gradient-adjusted strategy: the target SHALL then be slower uphill and faster downhill so that the effort is even, while the total still equals the target time. The strategy SHALL be on by default when available.

#### Scenario: Hill in the first half
- **WHEN** a route climbs steadily for its first 2 km and the runner follows the adjusted target exactly
- **THEN** the pacer shows neither ahead nor behind during the climb, although the pace is slower than the average target pace

#### Scenario: Whole route at target
- **WHEN** the runner follows the adjusted target over the whole route
- **THEN** they finish in the target time, within a few seconds

#### Scenario: Flat route
- **WHEN** the route has no significant gradient
- **THEN** the adjusted target equals the even pace throughout

#### Scenario: Route without elevation
- **WHEN** the selected route has no elevation data
- **THEN** only the even-pace strategy is offered

### Requirement: Strategy preview
Before starting with a target on a route, the runner SHALL be able to see the target pace of each kilometre (or mile) of the route and the planned time at the end of each.

#### Scenario: Preview a hilly 10 km
- **WHEN** the runner opens the preview of a 58:00 target on a hilly route
- **THEN** ten splits are listed with their target pace, slower for the climbing ones, and cumulative times ending at 58:00

### Requirement: Current target pace
While the pacer is on, the app SHALL show the target pace that applies at the runner's current position — constant with a fixed pace, varying with a gradient-adjusted strategy — next to the runner's current pace.

#### Scenario: Entering a climb
- **WHEN** the runner reaches a climb on an adjusted strategy
- **THEN** the displayed target pace becomes slower than on the flat

### Requirement: Off the route or past its end
When pacing on a route, progress SHALL be measured along the route. If the runner leaves the route, the pacer SHALL fall back to distance covered and say that it is approximate; when the runner passes the end of the route, the pacer SHALL show the final gap to the target and stop updating.

#### Scenario: Finish line
- **WHEN** the runner reaches the end of the route 35 seconds ahead of the target
- **THEN** the pacer shows the final result of 35 seconds ahead

#### Scenario: Shortcut
- **WHEN** the runner leaves the route for several hundred metres
- **THEN** the pacer keeps working from distance covered and is marked approximate until the route is rejoined

### Requirement: Pacer alerts
The runner SHALL be able to enable an alert given when they fall behind or get ahead of the target by more than a chosen margin, 30 seconds by default, repeated at most once per minute while it lasts, and a pacer status included in the per-kilometre announcement.

#### Scenario: Falling behind
- **WHEN** the runner drops to 30 seconds behind with alerts on
- **THEN** one alert states that they are 30 seconds behind

### Requirement: Pacer and guided session
The pacer SHALL NOT be available in a run with a structured session, where step targets apply instead.

#### Scenario: Session selected
- **WHEN** the runner selects a session on the ready screen
- **THEN** the pacer option is unavailable and says why

### Requirement: Pacer result is kept
A run done with the pacer SHALL show its target and the final gap in the end-of-run summary and on the phone's run page, and the target SHALL be visible on the server activity.

#### Scenario: After the run
- **WHEN** a paced run is synced and opened on the web
- **THEN** the activity page states the target and whether it was met
