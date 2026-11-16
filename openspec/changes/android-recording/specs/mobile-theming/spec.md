# Spec Delta

## Purpose

Keeps the phone and watch apps visually consistent with the approved web direction, and lets the runner pick a light or dark appearance on the phone.

## ADDED Requirements

### Requirement: Same visual language as the web app
The phone app SHALL use the colours, typefaces, shapes and colour-to-information pairing recorded as approved in the project's design direction, in both themes: pastel tonal surfaces without border lines, lavender for pace, rose for heart rate, mint for time and cadence, sky for distance and elevation, and the same five zone colours.

#### Scenario: Zone colours match
- **WHEN** the same activity is viewed on the web and its heart-rate zones are shown on the phone
- **THEN** each zone has the same colour in both

#### Scenario: No border lines
- **WHEN** any phone screen is displayed
- **THEN** surfaces are separated by tone, not by outline strokes

### Requirement: Light, dark and system themes
The phone app SHALL offer Light, Dark and System appearance, defaulting to System, SHALL remember the choice, and SHALL follow the phone's setting live when System is selected.

#### Scenario: Follow the system
- **WHEN** System is selected and the phone switches to dark mode
- **THEN** the app switches to its dark theme without restarting

#### Scenario: Forced light
- **WHEN** the runner selects Light while the phone is in dark mode
- **THEN** the app stays light and still is after being reopened

### Requirement: Readable while running
Live-screen figures SHALL meet a contrast ratio of at least 7:1 against their background in both themes, and the primary figures SHALL be large enough to read at arm's length.

#### Scenario: Sunlight
- **WHEN** the main metrics screen is shown in the light theme
- **THEN** every figure has a contrast ratio of at least 7:1 with its background

### Requirement: Watch appearance
The watch app SHALL use a single dark appearance with a true black background, the same typeface for figures and the same zone colours as the phone, and SHALL fit the round screen without clipped content.

#### Scenario: Round screen
- **WHEN** the metrics screen is shown on the Galaxy Watch 4
- **THEN** no figure or control is cut by the edge of the screen

### Requirement: Design approved before implementation
The phone and watch screens SHALL be validated by the owner on an interactive prototype before the applications are implemented, and the approval SHALL be recorded in the project's design direction document.

#### Scenario: Approval recorded
- **WHEN** the owner approves the prototype
- **THEN** the design direction document records the approval date and the mobile-specific tokens
