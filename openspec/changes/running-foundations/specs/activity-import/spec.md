# Spec Delta

## Purpose

Lets the athlete bring recorded runs into the system from standard `.gpx` and `.fit` files and take them back out, without relying on any third-party cloud service.

## ADDED Requirements

### Requirement: Import activity files
The system SHALL accept uploaded `.gpx` and `.fit` files and create one stored activity per recorded activity found in each file. Every import SHALL target exactly one athlete profile: the active profile in the web app, or the profile named by the uploading client. The file format SHALL be determined from the file content, not only from its extension.

#### Scenario: Import a GPX track
- **WHEN** the user uploads a valid `.gpx` file containing one track with timestamps
- **THEN** a new activity is created with its start time, GPS trace and elevation taken from the file
- **AND** the response identifies the created activity

#### Scenario: Import a FIT activity
- **WHEN** the user uploads a valid `.fit` activity file
- **THEN** a new activity is created with GPS trace, elevation, heart rate, cadence, laps and timer pause events when the file contains them

#### Scenario: Import into the active profile
- **WHEN** the user uploads a file while "Sarah" is the active profile
- **THEN** the activity is created in Sarah's profile and does not appear in other profiles

#### Scenario: Unknown target profile
- **WHEN** a client uploads a file naming a profile that does not exist
- **THEN** the upload is refused and no activity is created

#### Scenario: Wrong extension
- **WHEN** the user uploads a FIT file named with a `.gpx` extension
- **THEN** the file is imported as FIT

### Requirement: Batch import
The system SHALL accept several files in one import operation and SHALL process each file independently, reporting a per-file outcome of `imported`, `duplicate` or `failed` with a reason.

#### Scenario: Mixed batch
- **WHEN** the user uploads ten files of which one is corrupt and one is already imported
- **THEN** eight activities are created
- **AND** the result lists eight `imported`, one `duplicate` and one `failed` entry, each tied to its file name

### Requirement: Reject unusable files
The system SHALL reject a file that is not parseable as GPX or FIT, that contains no samples, or that exceeds the configured maximum upload size, and SHALL report a human-readable reason without creating an activity.

#### Scenario: Corrupt file
- **WHEN** the user uploads a file that is neither valid GPX nor valid FIT
- **THEN** no activity is created
- **AND** the outcome for that file is `failed` with a reason stating the format is not recognised

#### Scenario: Track without timestamps
- **WHEN** the user uploads a GPX file whose track points carry no time information
- **THEN** no activity is created
- **AND** the reason states that the file is a route without timing data

#### Scenario: File too large
- **WHEN** the user uploads a file larger than the configured maximum size
- **THEN** the upload is refused with a reason stating the size limit

### Requirement: Detect duplicates
Within one profile, the system SHALL NOT create a second activity when an uploaded file is byte-identical to a file already imported there, or when its start time is within 60 seconds of an existing activity's start time and its duration differs by less than 2 %. Activities of other profiles SHALL NOT count as duplicates.

#### Scenario: Same file uploaded twice
- **WHEN** the user uploads a file that was already imported
- **THEN** no new activity is created
- **AND** the outcome is `duplicate` and references the existing activity

#### Scenario: Same run exported in another format
- **WHEN** the user uploads a GPX export of a run already imported from FIT
- **THEN** no new activity is created
- **AND** the outcome is `duplicate` and references the existing activity

#### Scenario: Same file in two profiles
- **WHEN** a file already imported in one profile is uploaded into another profile
- **THEN** a new activity is created in the second profile

### Requirement: Retain original files
The system SHALL keep the unmodified uploaded file for every imported activity and SHALL let the user download it.

#### Scenario: Download the original
- **WHEN** the user requests the original file of an imported activity
- **THEN** the system returns a file byte-identical to the one uploaded

### Requirement: Tolerate missing data channels
The system SHALL import files that lack heart rate, cadence, elevation or lap data and SHALL mark the corresponding metrics as unavailable rather than failing or substituting zero.

#### Scenario: GPX without heart rate
- **WHEN** the user imports a GPX file with position and time only
- **THEN** the activity is created
- **AND** heart rate and cadence are reported as unavailable

### Requirement: Export activity as GPX
The system SHALL export any stored activity as a GPX 1.1 file containing the trace with position, elevation and time, plus heart rate and cadence as per-point extensions when available.

#### Scenario: Export an activity imported from FIT
- **WHEN** the user exports an activity that was imported from a FIT file with heart rate
- **THEN** the system returns a valid GPX 1.1 file whose points carry position, elevation, time and heart rate

#### Scenario: Round trip
- **WHEN** an activity is exported as GPX, deleted, and the exported file is imported again
- **THEN** the resulting activity has the same start time and a distance within 0.5 % of the original
