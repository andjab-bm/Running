# Spec Delta

## Purpose

Provides a consistent, modern look across the web app with first-class dark and light themes that the athlete can switch between.

## ADDED Requirements

### Requirement: Dark and light themes
The web app SHALL provide a dark theme and a light theme covering every screen and component, including charts and the map background.

#### Scenario: Dark theme on the activity page
- **WHEN** the dark theme is active on the activity page
- **THEN** page surfaces, charts, tables and the map background all use dark styling with no light-themed remnants

### Requirement: Theme selection
The web app SHALL let the user choose "Light", "Dark" or "System" from any screen. With "System", the theme SHALL follow the operating system preference and update when it changes.

#### Scenario: First visit
- **WHEN** a user opens the app for the first time on a device set to dark mode
- **THEN** the dark theme is applied

#### Scenario: Manual switch
- **WHEN** the user selects "Light" while the system is in dark mode
- **THEN** the light theme is applied immediately without reloading the page

#### Scenario: System change
- **WHEN** the selection is "System" and the operating system switches to light mode
- **THEN** the app switches to the light theme

### Requirement: Theme persistence
The web app SHALL remember the user's theme selection on the device and SHALL apply it before first paint so that the wrong theme never flashes on load.

#### Scenario: Return visit
- **WHEN** a user who chose "Dark" reloads the app on a device set to light mode
- **THEN** the page renders in the dark theme from the first frame

### Requirement: Legibility
Both themes SHALL keep body text and numeric metrics at a contrast ratio of at least 4.5:1 against their background. Colour-coded data (heart-rate zones, pace scale) SHALL remain distinguishable in both themes and SHALL always be accompanied by a text label or value.

#### Scenario: Zone colours in both themes
- **WHEN** the heart-rate zone chart is viewed in each theme
- **THEN** the five zones are visually distinct and each shows its zone label and duration as text
