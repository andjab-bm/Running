# Spec Delta

## MODIFIED Requirements

### Requirement: List activities
The system SHALL list the stored activities of the whole household, whatever the active profile, most recent first, showing for each: whose it is, date, title, distance, moving time, average pace, elevation gain, tags and a small preview of the trace. A run present in several profiles through a copy SHALL appear once, with every person it belongs to. The list SHALL load in pages so that a large history does not have to be fetched at once.

#### Scenario: Browsing history
- **WHEN** the user opens the activity list with 300 stored activities
- **THEN** the most recent activities are shown first
- **AND** older activities load as the user continues through the list

#### Scenario: Activities of another member
- **WHEN** Alex is the active profile and Sarah recorded a run yesterday
- **THEN** Sarah's run appears in the list, marked as hers

#### Scenario: Run done together
- **WHEN** a run of Alex was copied to Sarah
- **THEN** the household list shows it once, marked as belonging to both

#### Scenario: Opening another member's activity
- **WHEN** the user opens an activity that belongs to another profile
- **THEN** its page opens and states whose activity it is

#### Scenario: Empty library
- **WHEN** the user opens the activity list with no stored activity in the household
- **THEN** an empty state invites them to import a file

### Requirement: Filter activities
The system SHALL let the user filter the list by person — the whole household, only the active profile ("mine"), or one chosen member — and by tag and date range. The person filter SHALL be remembered on the device, and SHALL be the whole household until the user chooses otherwise.

#### Scenario: Filter by tag
- **WHEN** the user selects the tag "Fractionné"
- **THEN** only activities carrying that tag are listed

#### Scenario: Only mine
- **WHEN** the user chooses to see only their activities
- **THEN** only the active profile's activities are listed, and the choice is still applied when the app is reopened

#### Scenario: Mine follows the active profile
- **WHEN** "only mine" is chosen and the active profile is switched from Alex to Sarah
- **THEN** the list shows Sarah's activities

#### Scenario: One member
- **WHEN** the user chooses to see Sarah's activities while Alex is the active profile
- **THEN** only Sarah's activities are listed

#### Scenario: Member without activity
- **WHEN** the chosen person has no activity
- **THEN** an empty state says so and offers to show the whole household

## ADDED Requirements

### Requirement: Personal figures stay personal
Statistics, records, predictions, training load, segments, sessions and routes SHALL remain those of the active profile only; the household view SHALL apply to the activity list alone. Importing SHALL keep adding to the active profile.

#### Scenario: Household list, personal statistics
- **WHEN** the user browses the whole household's activities and then opens the statistics
- **THEN** the statistics count only the active profile's runs
