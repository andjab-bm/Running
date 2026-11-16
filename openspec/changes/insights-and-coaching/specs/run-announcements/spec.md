# Spec Delta

## Purpose

Defines how the phone speaks, beeps and vibrates during a run — the per-kilometre announcement and the delivery of coaching cues — so the runner can follow their run without looking at the screen and without the app trampling their music.

## ADDED Requirements

### Requirement: Three independent channels
The app SHALL deliver cues through three channels — voice, sound and vibration — each of which the runner can switch on or off in the settings. Every cue SHALL be meaningful through any single channel: voice says it, sound and vibration use a distinct pattern per kind of cue.

#### Scenario: Vibration only
- **WHEN** only vibration is enabled and a step changes
- **THEN** the phone vibrates with the step-change pattern and nothing is spoken or played

#### Scenario: Distinct patterns
- **WHEN** a step change, a target alert and a kilometre mark occur
- **THEN** each has a pattern the runner can tell apart without looking

### Requirement: Per-kilometre announcement
The app SHALL offer an optional voice announcement at each completed kilometre, or mile for an imperial profile, stating the distance covered, the total moving time and the pace of the split just completed. The runner SHALL be able to add average pace and heart rate to it. It SHALL be off by default.

#### Scenario: Third kilometre
- **WHEN** the runner completes the third kilometre in 5:52 with the announcement enabled
- **THEN** the phone says the distance of 3 kilometres, the total time and the split pace of 5:52

#### Scenario: Disabled
- **WHEN** the announcement is disabled
- **THEN** nothing is said at kilometre marks

#### Scenario: Imperial profile
- **WHEN** the active profile uses imperial units
- **THEN** the announcement is made at each mile, in miles and minutes per mile

### Requirement: Announcement interval
The runner SHALL be able to choose the announcement interval among every 0.5, 1, 2 or 5 units of distance.

#### Scenario: Every two kilometres
- **WHEN** the interval is 2 km
- **THEN** announcements are made at 2, 4 and 6 km and not at 1, 3 or 5 km

### Requirement: Spoken in the interface language
Announcements SHALL be spoken in French, with figures said the way a runner says them (for example "cinq minutes cinquante-deux au kilomètre"), using the phone's own speech engine without sending text to any service.

#### Scenario: No French voice installed
- **WHEN** voice is enabled and the phone has no French voice available offline
- **THEN** the settings say so and explain how to install one, and sound and vibration cues keep working

### Requirement: Coexist with music and calls
A voice or sound cue SHALL lower other audio for its duration rather than stop it, and other audio SHALL return to its level afterwards. No cue SHALL be played during a phone call. Cues SHALL be heard through the headphones when headphones are connected.

#### Scenario: Podcast playing
- **WHEN** a kilometre announcement occurs while a podcast is playing
- **THEN** the podcast is lowered during the announcement and then resumes at its level

#### Scenario: Phone call
- **WHEN** a cue occurs during a phone call
- **THEN** it is not spoken or played, and the vibration channel still applies

### Requirement: Cues work with the screen off
All cues SHALL be delivered with the screen off, the phone locked, or another app in front.

#### Scenario: Phone in an armband, screen off
- **WHEN** a step changes while the screen is off
- **THEN** the cue is delivered through the enabled channels

### Requirement: No pile-up
When several cues fall within a few seconds, they SHALL be delivered one after another in order of importance — step change, then target or pacer alert, then kilometre announcement — and a cue that has become stale by the time it could be delivered SHALL be dropped.

#### Scenario: Kilometre mark at a step change
- **WHEN** a kilometre is completed at the same moment as a step ends
- **THEN** the step change is announced first, then the kilometre

#### Scenario: Stale cue
- **WHEN** a kilometre announcement has waited more than 20 seconds behind other cues
- **THEN** it is dropped

### Requirement: Try the cues
The settings SHALL let the runner hear and feel a sample of each cue with the current channel settings, without starting a run.

#### Scenario: Test before leaving
- **WHEN** the runner presses the test control for the step-change cue
- **THEN** that cue is delivered through the enabled channels

### Requirement: Paused runs stay quiet
While a run is paused, manually or automatically, no kilometre announcement, target alert or pacer alert SHALL be delivered. The pause and resume transitions themselves SHALL be signalled.

#### Scenario: Auto-pause
- **WHEN** the run auto-pauses
- **THEN** the pause is signalled once and nothing else is announced until it resumes
