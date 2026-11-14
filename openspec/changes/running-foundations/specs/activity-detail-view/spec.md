# Spec Delta

## Purpose

Presents a single run on the web as a polished, interactive analysis page — map, pace chart, splits, laps and effort — so the athlete can understand how the run went.

## ADDED Requirements

### Requirement: Activity header and summary
The activity page SHALL show the title, date and start time, tags, and a summary of distance, moving time, elapsed time, average pace, grade-adjusted pace, elevation gain and loss, average and maximum heart rate, average cadence and relative effort. Unavailable metrics SHALL be shown as a neutral placeholder, never as zero.

#### Scenario: Open an activity
- **WHEN** the user opens an activity from the list
- **THEN** the header and all summary metrics are displayed in the athlete's unit system

#### Scenario: Activity without heart rate
- **WHEN** the user opens an activity that has no heart rate data
- **THEN** heart-rate metrics and relative effort show a placeholder
- **AND** the heart-rate zone section explains that no heart rate was recorded

### Requirement: Interactive map with coloured trace
The page SHALL display the GPS trace on an interactive, pannable and zoomable map, initially fitted to the whole trace, with distinct start and finish markers. The trace SHALL be coloured along its length by pace or by heart rate, selectable by the user, with a legend explaining the colour scale.

#### Scenario: Default colouring
- **WHEN** the page opens
- **THEN** the full trace is visible and coloured by pace with a legend

#### Scenario: Switch to heart rate
- **WHEN** the user selects heart-rate colouring
- **THEN** the trace is recoloured by heart-rate zone and the legend updates

#### Scenario: Heart rate unavailable
- **WHEN** the activity has no heart rate data
- **THEN** the heart-rate colouring option is disabled

#### Scenario: Map tiles unreachable
- **WHEN** the map background cannot be loaded
- **THEN** the trace is still drawn and the rest of the page remains usable

### Requirement: Performance chart synchronised with the map
The page SHALL display a chart of one measure over distance, showing pace by default. Pointing at a position on the chart SHALL show a marker at the matching location on the map and a tooltip with distance, pace, heart rate, cadence and elevation at that point; pointing at the trace on the map SHALL highlight the matching position on the chart.

#### Scenario: Default view
- **WHEN** the page opens
- **THEN** the chart shows pace over distance, with faster pace higher on the vertical axis and the average pace marked

#### Scenario: Hover the chart
- **WHEN** the user moves the pointer over the chart at kilometre 3.2
- **THEN** a marker appears on the map at the point 3.2 km into the run
- **AND** a tooltip shows the values at that point

#### Scenario: Hover the map
- **WHEN** the user moves the pointer over a point of the trace on the map
- **THEN** the corresponding position is highlighted on the chart

#### Scenario: Touch device
- **WHEN** the user drags a finger along the chart on a touch screen
- **THEN** the map marker and tooltip follow the finger

### Requirement: Selectable chart measure
The page SHALL let the user switch the chart between pace, heart rate, cadence and elevation. A measure with no recorded data SHALL be offered as disabled. The user SHALL be able to show or hide per-kilometre averages of the selected measure and a faint relief silhouette behind it.

#### Scenario: Switch to heart rate
- **WHEN** the user selects heart rate
- **THEN** the chart shows heart rate over distance with its own scale and average

#### Scenario: No heart rate recorded
- **WHEN** the activity has no heart rate data
- **THEN** the heart-rate choice is disabled

#### Scenario: Per-kilometre averages
- **WHEN** per-kilometre averages are shown with pace selected
- **THEN** each kilometre is drawn as a block at its average pace behind the curve

#### Scenario: Relief silhouette
- **WHEN** the relief silhouette is enabled and the selected measure is not elevation
- **THEN** the elevation shape appears discreetly along the bottom of the chart

### Requirement: Splits and laps tables
The page SHALL show a table of distance splits and a table of laps, switchable by the user, with columns for distance, time, pace, grade-adjusted pace, elevation change and average heart rate. Each row SHALL carry a visual bar comparing its pace to the other rows. The laps table SHALL be offered only when the activity has more than one lap.

#### Scenario: View splits
- **WHEN** the user opens a 10 km activity
- **THEN** ten split rows are shown with the fastest split visually identifiable

#### Scenario: Switch to laps
- **WHEN** the user switches to laps on an interval session
- **THEN** the table lists each recorded lap

#### Scenario: Highlight a split
- **WHEN** the user points at or selects a split row
- **THEN** the matching portion of the trace is highlighted on the map and on the chart

### Requirement: Effort analysis
The page SHALL show the time and percentage spent in each of the five heart-rate zones as a bar chart using a consistent colour per zone, together with the relative effort score.

#### Scenario: Zone chart
- **WHEN** the user opens an activity with heart rate data
- **THEN** five zone bars are shown with duration and percentage, each labelled with its bpm range

### Requirement: Journal on the activity page
The page SHALL display the activity's description, perceived effort and tags, and SHALL let the user edit them and the title in place.

#### Scenario: Edit in place
- **WHEN** the user edits the description on the activity page and saves
- **THEN** the page shows the updated description without a full reload

### Requirement: Activity actions
The page SHALL offer actions to export the activity as GPX, download the original file, copy or move the activity to another profile, and delete the activity. Copy and move SHALL be offered only when more than one profile exists.

#### Scenario: Copy to a partner from the page
- **WHEN** the user chooses "Copy to profile", picks "Sarah" and confirms
- **THEN** a confirmation states that the run was added to Sarah's profile

#### Scenario: Export from the page
- **WHEN** the user chooses "Export GPX"
- **THEN** the browser downloads a `.gpx` file named after the activity date and title

### Requirement: Responsive layout
The page SHALL be fully usable from a 360 px wide phone screen up to a desktop screen, without horizontal scrolling of the page; wide tables SHALL scroll within their own container.

#### Scenario: Phone width
- **WHEN** the page is displayed at 360 px width
- **THEN** map, chart, summary, tables and zones are stacked vertically and all remain readable and operable

### Requirement: Loading and error states
The page SHALL show placeholders while data loads and a clear message with a way back to the list when the activity does not exist.

#### Scenario: Unknown activity
- **WHEN** the user opens the address of an activity that does not exist
- **THEN** a "not found" message is shown with a link to the activity list
