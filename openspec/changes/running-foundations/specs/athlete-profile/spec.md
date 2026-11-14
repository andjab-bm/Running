# Spec Delta

## Purpose

Lets a household keep several athletes' data apart without accounts, and holds each athlete's physiological settings and display preferences that metrics and views depend on, such as heart-rate zones and units.

## ADDED Requirements

### Requirement: Multiple athlete profiles
The system SHALL support several athlete profiles, each with a unique name and a colour or initial used to recognise it. Profiles SHALL be creatable and renamable by anyone using the app, with no password. All settings in this capability SHALL be held per profile.

#### Scenario: First start
- **WHEN** the app is opened with no profile existing
- **THEN** it asks for a name and creates the first profile before anything else

#### Scenario: Add a second profile
- **WHEN** the user creates a profile named "Sarah"
- **THEN** it appears in the profile switcher with its own empty activity list and default settings

#### Scenario: Duplicate name
- **WHEN** the user creates a profile with a name already in use
- **THEN** the creation is rejected

### Requirement: Active profile
The web app SHALL always show which profile is active, SHALL let the user switch profile from any screen, and SHALL remember the choice on the device. Lists, statistics and settings SHALL concern only the active profile.

#### Scenario: Switch profile
- **WHEN** the user switches from their profile to "Sarah"
- **THEN** the activity list and settings shown are Sarah's

#### Scenario: Return visit
- **WHEN** the user reopens the app on a device where "Sarah" was last active
- **THEN** "Sarah" is still the active profile

### Requirement: Delete a profile
The system SHALL let the user delete a profile only after an explicit confirmation that names the profile and states how many activities will be permanently deleted with it. The last remaining profile SHALL NOT be deletable.

#### Scenario: Delete a profile with activities
- **WHEN** the user deletes a profile holding 12 activities and confirms
- **THEN** the profile and its 12 activities are removed
- **AND** activities of other profiles, including copies previously made from those 12, are untouched

#### Scenario: Last profile
- **WHEN** only one profile exists
- **THEN** deleting it is not offered

### Requirement: Heart-rate settings
The system SHALL let the athlete record their maximum heart rate and, optionally, their resting heart rate. A maximum heart rate outside 100–230 bpm, or a resting heart rate not below the maximum, SHALL be rejected.

#### Scenario: Set maximum heart rate
- **WHEN** the athlete saves a maximum heart rate of 188 bpm
- **THEN** the value is stored and used for zone calculation

#### Scenario: Implausible value
- **WHEN** the athlete enters a maximum heart rate of 300 bpm
- **THEN** the value is rejected with an explanation of the accepted range

### Requirement: Five heart-rate zones
The system SHALL define five contiguous heart-rate zones. By default they SHALL be derived from the maximum heart rate at 50–60 %, 60–70 %, 70–80 %, 80–90 % and 90–100 %. The athlete SHALL be able to override the zone boundaries, which MUST remain strictly increasing.

#### Scenario: Default zones
- **WHEN** the maximum heart rate is 190 bpm and no custom zones are set
- **THEN** zone 2 spans 114–133 bpm and zone 5 starts at 171 bpm

#### Scenario: Custom boundaries
- **WHEN** the athlete sets custom zone boundaries in increasing order
- **THEN** those boundaries are used instead of the percentage defaults

#### Scenario: Overlapping boundaries
- **WHEN** the athlete enters a zone 3 lower bound below the zone 2 lower bound
- **THEN** the change is rejected

#### Scenario: Reset to defaults
- **WHEN** the athlete resets custom zones
- **THEN** the zones are again derived from the maximum heart rate

### Requirement: Fallback before configuration
The system SHALL remain usable before the athlete configures heart-rate settings by using a documented default maximum heart rate, and SHALL indicate wherever zones are shown that they rest on a default value.

#### Scenario: Fresh installation
- **WHEN** an activity with heart rate is viewed before any profile is configured
- **THEN** zones are displayed
- **AND** a notice invites the athlete to set their maximum heart rate

### Requirement: Unit preference
The system SHALL let the athlete choose between metric units (km, m, min/km) and imperial units (mi, ft, min/mi), defaulting to metric, and SHALL apply the choice to all displayed distances, elevations, paces and split lengths.

#### Scenario: Switch to imperial
- **WHEN** the athlete selects imperial units
- **THEN** activity pages show miles, feet and min/mi, and splits are per mile

### Requirement: Time zone
The system SHALL display activity dates and times in the athlete's configured time zone, defaulting to the browser's time zone.

#### Scenario: Evening run stored in UTC
- **WHEN** a run started at 21:30 local time in Europe/Paris is viewed
- **THEN** the page shows 21:30 on the local calendar date
