# Spec Delta

## Purpose

Gives the athlete a browsable history of their stored runs and lets them annotate each one with a training journal entry or remove it.

## ADDED Requirements

### Requirement: List activities
The system SHALL list the active profile's stored activities most recent first, showing for each: date, title, distance, moving time, average pace, elevation gain, tags and a small preview of the trace. The list SHALL load in pages so that a large history does not have to be fetched at once.

#### Scenario: Browsing history
- **WHEN** the user opens the activity list with 300 stored activities
- **THEN** the most recent activities are shown first
- **AND** older activities load as the user continues through the list

#### Scenario: Empty library
- **WHEN** the user opens the activity list with no stored activity
- **THEN** an empty state invites them to import a file

### Requirement: Filter activities
The system SHALL let the user filter the list by tag and by date range.

#### Scenario: Filter by tag
- **WHEN** the user selects the tag "Fractionné"
- **THEN** only activities carrying that tag are listed

### Requirement: Default title
The system SHALL give every imported activity a title. It SHALL use the name found in the file when present, and otherwise generate one from the time of day of the run.

#### Scenario: File without a name
- **WHEN** a file with no activity name, started at 07:10 local time, is imported
- **THEN** the activity is titled as a morning run

### Requirement: Journal entry
The system SHALL let the user edit, for each activity, a title, a free-text description, a perceived effort (RPE) from 1 to 10, and a set of tags. Editing the journal SHALL NOT alter recorded data or computed metrics.

#### Scenario: Save a journal entry
- **WHEN** the user sets the title, a description, RPE 7 and the tag "Endurance fondamentale", then saves
- **THEN** those values are shown when the activity is reopened

#### Scenario: Invalid RPE
- **WHEN** a client submits an RPE of 11
- **THEN** the change is rejected with a validation error
- **AND** the stored journal entry is unchanged

#### Scenario: Clear RPE
- **WHEN** the user removes the RPE value and saves
- **THEN** the activity has no perceived effort recorded

### Requirement: Tags
The system SHALL provide the predefined tags "Endurance fondamentale", "Fractionné" and "Compétition", SHALL let the user create additional tags, and SHALL allow several tags on one activity.

#### Scenario: Create a custom tag
- **WHEN** the user types a new tag name "Trail" on an activity and saves
- **THEN** the tag is attached to the activity
- **AND** "Trail" is offered as a suggestion on other activities

### Requirement: Copy an activity to another profile
The system SHALL let the user copy an activity to another profile so that a run done together and recorded by one person also counts for the other. The copy SHALL be an independent activity with its own journal. By default the copy SHALL omit heart rate, which belongs to the person who recorded; the user SHALL be able to keep it.

#### Scenario: Attribute a shared run to a partner
- **WHEN** the user copies one of their activities to "Sarah" with the default options
- **THEN** Sarah's profile gains an activity with the same trace, distance, times, pace, elevation and splits
- **AND** its heart-rate metrics, zones and relative effort are unavailable
- **AND** the original activity is unchanged

#### Scenario: Keep heart rate
- **WHEN** the user copies an activity and chooses to keep heart rate
- **THEN** the copy's zones and relative effort are computed with the target profile's heart-rate zones

#### Scenario: Copies are independent
- **WHEN** the journal of the copy is edited or the copy is deleted
- **THEN** the original activity is unaffected

#### Scenario: Already present
- **WHEN** the user copies an activity to a profile that already holds a copy of it
- **THEN** no second copy is created and the existing one is pointed out

### Requirement: Move an activity to another profile
The system SHALL let the user move an activity to another profile, for a run imported into the wrong profile. The activity SHALL keep its recorded data and journal, and its zone distribution and relative effort SHALL be recomputed with the target profile's zones.

#### Scenario: Wrong profile at import
- **WHEN** the user moves an activity from their profile to "Sarah"
- **THEN** it disappears from their list and appears in Sarah's with its journal intact

### Requirement: Delete activity
The system SHALL let the user permanently delete an activity after an explicit confirmation, removing its computed data, journal entry and retained original file.

#### Scenario: Confirmed deletion
- **WHEN** the user deletes an activity and confirms
- **THEN** the activity no longer appears in the list
- **AND** its original file can no longer be downloaded

#### Scenario: Cancelled deletion
- **WHEN** the user starts deleting an activity and cancels the confirmation
- **THEN** the activity remains unchanged

#### Scenario: Re-import after deletion
- **WHEN** the user imports the file of a previously deleted activity
- **THEN** it is imported as a new activity and not reported as a duplicate
