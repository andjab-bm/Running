# Proposal

## Why

The repository is empty apart from `Specification.md`, which describes a self-hosted, privacy-first running tracker (web + Android + Wear OS) with a strong UI/UX requirement. Before any mobile or watch work can start, the project needs a validated visual direction, a decided overall architecture, and a running server that can ingest existing `.gpx`/`.fit` history and show a Strava-quality activity page on a Raspberry Pi.

## What Changes

This is the first of several changes. It delivers the foundations only:

- **Interactive UI prototype (gate)**: a clickable mock-up of the whole web app (activity list, activity detail, import, settings), published as a Claude artifact, in the pastel, borderless art direction requested by the owner, with dark and light themes. It must be reviewed and approved by the owner before any application code is written.
- **Architecture decision** for the whole product (watch → phone → Raspberry Pi), recorded in `design.md`, so later changes build on a fixed contract.
- **Backend service**: single Rust binary with an embedded SQLite database, exposing a versioned REST API and serving the web app.
- **Activity import/export**: manual upload of `.gpx` and `.fit` files (single or batch), duplicate detection, original file retention, `.gpx` export.
- **Metric computation**: distance, elapsed vs moving time (auto-pause), average and grade-adjusted pace, elevation gain/loss, cadence, heart rate, per-kilometre splits, manual laps, time in HR zones, relative effort score.
- **Athlete profiles**: several profiles (e.g. the owner and their spouse) without any sign-in; each activity belongs to one profile, and an activity can be moved or copied to another profile so a run recorded by one person can also be attributed to the person who ran alongside.
- **Web app**: profile switcher, activity list, activity detail page (as validated in the prototype), journal editing (title, description, RPE, tags), profile settings (HR zones), dark and light themes.
- **Deployment**: one multi-arch Docker image (ARM64 + AMD64) with a compose file, suitable for a Raspberry Pi, open to anyone on the trusted home network (no authentication).

### Out of scope (planned follow-up changes)

1. `android-recording` — native Android recorder, live screens, automatic sync on home Wi-Fi.
2. `training-insights` — statistics/heatmap calendar, personal records, race predictions, training load and recovery.
3. `personal-segments` — automatic detection of recurring portions and effort comparison.
4. `workout-planner` — structured interval sessions, route library, push to phone/watch.
5. `live-coaching` — guided intervals, virtual pacer, voice announcements on Android.
6. `wear-os-companion` — Galaxy Watch 4 app (controls, metrics, vibration alerts, HR streaming).

Nutrition and weight tracking are separate modules and are not addressed.

## Capabilities

### New Capabilities

- `activity-import`: ingesting `.gpx` and `.fit` files into stored activities, duplicate handling, retention of originals, and `.gpx` export.
- `activity-metrics`: how summary metrics, splits, laps, heart-rate zone distribution and relative effort are derived from recorded samples.
- `activity-library`: listing, opening, annotating (journal) and deleting stored activities.
- `activity-detail-view`: the web activity page — map, pace-first chart, splits/laps tables, effort analysis and their interactions.
- `athlete-profile`: multiple athlete profiles, the active-profile selection, and per-profile physiological settings (max heart rate, zone boundaries) and display preferences used by metrics and views.
- `ui-theming`: dark and light themes across the web app.
- `self-hosted-deployment`: container packaging, resource envelope, data persistence and the trusted-network access model for a home server.

### Modified Capabilities

None — no specs exist yet.

## Impact

- **New code**: `prototype/` (static mock-up), `server/` (Rust API + processing), `web/` (single-page app), `deploy/` (Dockerfile, compose). No existing code is affected.
- **APIs**: introduces REST API `/api/v1` (profiles, activities, import, export), unauthenticated. This becomes the contract the future Android app syncs against.
- **Dependencies**: Rust toolchain, Node.js (build time only), SQLite, MapLibre GL JS, a FIT and a GPX parsing library.
- **Systems**: a Raspberry Pi (ARM64) running Docker; optional outbound requests to a map tile provider (configurable, can be self-hosted).
- **Assumptions recorded**: no authentication, the service is reachable only from a trusted network; a few household profiles rather than accounts; a copy of an activity made for a running partner omits the recorder's heart rate by default; metric units and km splits by default; one sport (running) — other FIT sport types are imported but not specially handled.
