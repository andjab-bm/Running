# Spec Delta

## Purpose

Finds the portions of route a runner covers again and again, times every passage and compares them, so that the runner can measure themself against their own earlier runs on familiar ground.

## ADDED Requirements

### Requirement: Automatic detection of recurring portions
The system SHALL create a personal segment, without user action, for each portion of route at least 300 m long that the active profile has covered in the same direction in at least three activities. Passages SHALL be recognised despite the usual inaccuracy of position, within about 25 m of each other.

#### Scenario: The usual riverside stretch
- **WHEN** three runs of a profile follow the same 1.2 km riverside path in the same direction, as part of otherwise different outings
- **THEN** a segment of about 1.2 km exists for that path

#### Scenario: Only twice
- **WHEN** a portion has been covered twice
- **THEN** no segment exists for it yet

#### Scenario: Opposite direction
- **WHEN** a path is run three times northbound and three times southbound
- **THEN** two segments exist, one per direction

#### Scenario: Other side of the road
- **WHEN** two passages follow the same street 10 m apart
- **THEN** they are treated as the same portion

### Requirement: Segments are per profile
Segments SHALL be detected and kept separately for each profile, from that profile's activities only.

#### Scenario: Run copied to a partner
- **WHEN** a run is copied to another profile
- **THEN** it counts towards segment detection in that profile, independently of the first

### Requirement: Time every passage
Every complete passage of an activity along a segment SHALL be recorded as an effort with its elapsed time, average pace, and average heart rate when available. A passage that leaves the segment before its end, or covers it in the other direction, SHALL NOT be an effort. An activity SHALL be able to hold several efforts on the same segment.

#### Scenario: New run over a known segment
- **WHEN** a newly synced run covers an existing segment from start to end
- **THEN** an effort is recorded for it without user action

#### Scenario: Turning back halfway
- **WHEN** a run enters a segment and turns back at its midpoint
- **THEN** no effort is recorded on that segment

#### Scenario: Two laps of a loop
- **WHEN** a run covers the same segment twice
- **THEN** two efforts are recorded on it

### Requirement: Earlier runs are matched too
When a segment is created, the profile's earlier activities that cover it SHALL receive their efforts, so that its history is complete from its creation.

#### Scenario: Third passage creates the segment
- **WHEN** the third passage over a portion creates a segment
- **THEN** the segment has three efforts, including the two earlier runs

### Requirement: Segment list
The web app SHALL list the active profile's segments with, for each, a name, its length and elevation gain, the number of efforts, the best time and the date of the latest effort, and SHALL allow sorting by number of efforts and by latest effort.

#### Scenario: No segment yet
- **WHEN** the profile has no segment
- **THEN** the page explains that segments appear by themselves once the same portion has been run three times

#### Scenario: Default name
- **WHEN** a segment is created
- **THEN** it has a generated name based on its length and the area it lies in, which can be replaced

### Requirement: Segment page
Opening a segment SHALL show its trace on a map, its elevation profile, and all its efforts with date, time, pace and heart rate, ranked by time, with the best three marked. A chart SHALL show effort times in chronological order. Each effort SHALL link to its activity.

#### Scenario: Compare with the best
- **WHEN** the runner opens a segment with eight efforts
- **THEN** each effort shows its gap to the best time

#### Scenario: Progress over time
- **WHEN** the efforts of a segment got faster over six months
- **THEN** the chronological chart shows the times going down

### Requirement: Segment efforts on the activity page
The activity page SHALL list the segments the activity covers, each with the effort's time, its rank among the profile's efforts on that segment and the gap to the best. Pointing at an effort SHALL highlight its portion on the map and the chart.

#### Scenario: Best time on a segment
- **WHEN** an activity holds the profile's fastest effort on a segment
- **THEN** that segment is marked as a best time on the activity page

#### Scenario: Activity without segment
- **WHEN** an activity covers no segment
- **THEN** the page shows no segment section, not an empty table

### Requirement: Curate segments
The runner SHALL be able to rename a segment and to hide it. A hidden segment SHALL disappear from the list and from activity pages, SHALL NOT be detected again, and SHALL be restorable from a list of hidden segments.

#### Scenario: Hide a pointless segment
- **WHEN** the runner hides the segment made of their own street
- **THEN** it no longer appears anywhere and does not come back after the next run

#### Scenario: Restore
- **WHEN** the runner restores a hidden segment
- **THEN** it reappears with all its efforts, including those run while it was hidden

### Requirement: Segments stay consistent
Efforts SHALL follow their activity: deleting or moving an activity SHALL remove its efforts from the profile. A segment whose activities fall below three SHALL remain, with its remaining efforts, until the runner hides it.

#### Scenario: Delete a run
- **WHEN** the runner deletes an activity holding the best time on a segment
- **THEN** the effort disappears and the next best time becomes the best

### Requirement: Detection does not slow import
Segment detection and matching SHALL NOT delay the import of an activity: the activity SHALL be available as soon as it is imported, and its segment efforts SHALL appear without user action within one minute for a one-hour run on the reference home server.

#### Scenario: Batch import of history
- **WHEN** the runner imports 200 activities at once
- **THEN** every activity is listed as soon as it is imported, and the segments page states that detection is in progress until it completes
