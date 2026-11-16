# Spec Delta

## Purpose

Lets a runner build structured sessions — warm-up, repeats, recoveries, cool-down, each with an optional target — on the web, and keep them in a library to run them later with guidance.

## ADDED Requirements

### Requirement: Create a structured session
The runner SHALL be able to create a session for the active profile, made of a name, an optional note and an ordered list of steps. A session SHALL need a name and at least one step to be saved.

#### Scenario: Simple interval session
- **WHEN** the runner creates "6 × 400 m" with a 10-minute warm-up, six repeats of 400 m fast and 1:30 easy, and a 10-minute cool-down, and saves
- **THEN** the session appears in the library with that structure

#### Scenario: No step
- **WHEN** the runner tries to save a session without any step
- **THEN** it is not saved and the missing step is pointed out

### Requirement: Steps
Each step SHALL have a kind — warm-up, effort, recovery or cool-down — and an end condition: a duration, a distance, or "until I press lap". Durations SHALL range from 5 seconds to 6 hours and distances from 50 m to 100 km, entered in the profile's units.

#### Scenario: Open-ended recovery
- **WHEN** the runner sets a recovery step to end on the lap button
- **THEN** the session is saved with that step having no fixed length

#### Scenario: Invalid length
- **WHEN** the runner enters a distance of 0 for a step
- **THEN** the step is refused with an explanation

### Requirement: Step targets
Each step SHALL optionally carry one target: a pace range, or a heart-rate zone of the profile. A pace range SHALL have its fast bound faster than its slow bound. A step without target SHALL be allowed.

#### Scenario: Pace range
- **WHEN** the runner sets an effort step's target to between 5:00 and 5:15 min/km
- **THEN** the step is saved with that range

#### Scenario: Heart-rate zone
- **WHEN** the runner sets a recovery step's target to zone 2
- **THEN** the step shows zone 2 with the profile's current bounds for that zone

#### Scenario: Inverted range
- **WHEN** the runner enters a range from 5:15 to 5:00 as fast and slow bounds
- **THEN** the range is refused or its bounds are swapped, and never saved inverted

### Requirement: Repeats
The runner SHALL be able to group consecutive steps into a block repeated from 2 to 50 times. Blocks SHALL NOT be nested. The editor SHALL offer skipping the last recovery of a block.

#### Scenario: Six repeats
- **WHEN** a block of "400 m effort, 1:30 recovery" is repeated 6 times
- **THEN** the session unfolds as twelve steps, or eleven when the last recovery is skipped

### Requirement: Session overview
The library and the editor SHALL show, for each session, a visual outline of its steps in proportion to their length, and its total duration and distance when they can be known. When some steps are open-ended or have no pace from which to convert, the totals SHALL be shown as a minimum or as unknown, never as an invented figure.

#### Scenario: Time-based session
- **WHEN** every step of a session is a duration
- **THEN** its total duration is shown exactly and its distance is shown as unknown

#### Scenario: Open-ended step
- **WHEN** a session contains a step ending on the lap button
- **THEN** its total duration is shown as "at least" the sum of the other steps

### Requirement: Edit, duplicate, delete
The runner SHALL be able to edit a session, reorder its steps, duplicate it under a new name, and delete it after confirmation. Deleting a session SHALL NOT alter past activities that were run with it.

#### Scenario: Duplicate and adapt
- **WHEN** the runner duplicates "6 × 400 m" and changes the repeats to 8 in the copy
- **THEN** both sessions exist and the original is unchanged

#### Scenario: Delete a session already run
- **WHEN** the runner deletes a session that two activities were run with
- **THEN** those activities keep their steps and the name of the session as it was

### Requirement: Starter sessions
A profile with no session SHALL be offered a few ready-made sessions suited to beginners — an easy continuous run, a run/walk alternation, and a short interval session — which the runner can add to their library and edit like any other.

#### Scenario: Empty library
- **WHEN** the runner opens the session library for the first time
- **THEN** the starter sessions are proposed and none is in the library until the runner adds it

### Requirement: Sessions are per profile
Sessions SHALL belong to one profile and SHALL NOT be visible from another. A session SHALL be copyable to another profile; a heart-rate zone target then refers to the same zone number in the other profile.

#### Scenario: Share a session with a partner
- **WHEN** the runner copies a session to another profile
- **THEN** it appears in that profile's library and later changes to either copy do not affect the other

### Requirement: Session done, on the activity page
An activity that was run with a session SHALL show, on its page, the session's name and each step executed with its target and what was achieved — time, distance, average pace and average heart rate — and whether the target was met.

#### Scenario: Interval session review
- **WHEN** the runner opens an activity run with "6 × 400 m"
- **THEN** each of the six efforts is listed with its pace against its target range

#### Scenario: Session cut short
- **WHEN** the run was stopped during the fourth repeat
- **THEN** the steps actually executed are shown and the remaining ones are shown as not done
