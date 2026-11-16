# Proposal

## Why

`running-foundations` and `android-recording` deliver the capture-and-review loop: a run is recorded on the phone, synced at home and shown on a detailed activity page. Everything in `Specification.md` that turns a pile of runs into training — trends, records, predictions, load and recovery, recurring segments, planned sessions and routes, and coaching during the run — was pushed out of both changes and is still missing. This change builds all of it, so that the running module covers the whole brief.

## What Changes

Cross-check of `Specification.md` against what is built, and what this change adds:

| Brief | State before | This change |
|---|---|---|
| §2.A activity page, journal, import/export | built | unchanged, gains links to segments, records and the planned session |
| §2.A segments, records, predictions | missing | built |
| §2.A statistics, load and recovery | missing | built |
| §2.A planner for sessions and routes, sent to the phone | missing | built |
| §2.B recording, custom screens, history | built | unchanged |
| §2.B map screen following a GPX route | trace-only mini-map | built |
| §2.B guided intervals, pacer, voice | missing | built |
| §2.C watch controls, metrics, heart rate | built | gains coaching alerts and start from the wrist |

- **Interactive prototypes (two gates)**: one for the new web screens, one for the new phone and watch screens, on the already approved tokens, published as Claude artifacts. Each must be approved by the owner before the corresponding interface code is written. Server-side computation does not wait for them.
- **Training statistics (web)**: yearly calendar heatmap, weekly and monthly volume, progress curves.
- **Personal records and race predictions (web)**: best times over 400 m, 1 km, 5 km, 10 km, half-marathon and marathon found inside any run, their history, and estimated potential times for the race distances.
- **Training load and recovery (web)**: daily load from effort, short-term against long-term load with a plain-language status, and an advised recovery time after the latest run.
- **Personal segments (web)**: portions of route the runner covers repeatedly are detected automatically; every passage is timed and ranked against the others.
- **Workout and route library (web)**: an editor for structured sessions (warm-up, repeats, recoveries, targets), and a library of routes imported from `.gpx` or made from a past run.
- **Delivery to the phone**: sessions and routes marked for the phone arrive there at the next home sync, with no cloud; the web shows what the phone holds.
- **Live coaching on the phone**: guided execution of a structured session with voice, sound and vibration cues at each change of step; a virtual pacer showing time ahead or behind a target pace, fixed or adjusted to the route's gradient; optional voice announcement at each kilometre.
- **Map screen during the run**: a third live screen with a real basemap, the trace so far and the route being followed, distance remaining and an off-route alert. By the owner's decision the basemap is loaded online during the run, from the tile host configured on the server.
- **Watch**: vibration alerts and a coaching page for guided sessions and the pacer; a run can be started from the wrist when nothing was prepared on the phone (the phone, carried by the runner, remains the recorder); a **tile** shows the run's vitals during a run, offers to start outside one, and opens the app; each app can open the other on its device.
- **Resume after stop** (addition to the brief, asked by the owner): from the end-of-run summary a stopped run can be resumed as long as it is not saved; once saved only its title, effort and note can be changed, as today.
- **Household activity list** (asked by the owner on the web prototype): the activity list shows the runs of every member of the household whatever the active profile, with a filter for "mine", one member or everyone; a run copied between members appears once. Statistics, records, load, segments and the planner stay personal.
- **Wording**: the combined bottom field of the main live screen becomes "Temps, distance et mini-carte" and shows moving time.

### Out of scope

- **Standalone recording on the watch with its own GPS**: by the owner's decision in this proposal, only starting from the wrist is built; the phone must be carried.
- Offline or pre-downloaded basemaps on the phone; a map on the watch.
- Statistics, records, segments or planning screens on the phone (the phone only receives and executes sessions and routes).
- Multi-week training plans, a calendar of scheduled sessions, automatic session suggestions.
- Health Connect, Play Store publication, iOS; nutrition and weight modules.
- The open tasks of `running-foundations` and `android-recording` (device checks, field run, Raspberry Pi install), which stay in those changes.

## Capabilities

### New Capabilities

- `training-statistics`: calendar heatmap, weekly and monthly volume and progress curves for a profile.
- `personal-records`: best times over standard distances found inside runs, their history, and race-time predictions derived from them.
- `training-load`: daily training load, short-term versus long-term load status, and advised recovery time.
- `personal-segments`: automatic detection of recurring portions of route, timing of each passage, and comparison of passages.
- `workout-library`: creating, editing and organising structured sessions (steps, repeats, targets) on the web.
- `route-library`: routes imported from `.gpx` or made from a run, their profile, export and deletion.
- `plan-delivery`: getting chosen sessions and routes onto the phone over the home network, and knowing what the phone holds.
- `guided-workout`: executing a structured session during a run — step progression, cues, live step screen, and how the result is recorded.
- `virtual-pacer`: target pace or target time, fixed or adjusted to the route's gradient, with the live ahead/behind indicator.
- `run-announcements`: voice, sound and vibration channels, the per-kilometre announcement, and coexistence with other audio.
- `route-navigation`: the map screen during a run, route following, distance remaining and off-route alerts.

### Modified Capabilities

`openspec/specs/` is still empty: the capabilities below are defined by the delta specs of `android-recording`, which is not archived yet. This change carries deltas against them, so **`running-foundations` and `android-recording` must be archived before this change is**.

- `activity-library` (defined by `running-foundations`): the list covers the whole household and gains a person filter (requirements "List activities" and "Filter activities" change; one requirement is added).
- `watch-companion`: the watch may start a run when nothing is prepared on the phone; it gains a coaching page, coaching vibrations, a tile and the opening of the other device's app; it keeps measuring for a while after a stop so a resumed run keeps its heart rate (requirements "Join a session", "Glanceable metrics" and "End with the session" change; five requirements are added).
- `run-sync`: the "Local-only data flow" requirement changes — the phone may also contact the map tile host configured on the server, only for the map screen and only when the runner leaves that option on.
- `run-recording`: adds resuming a stopped run before it is saved (requirement "End-of-run summary" changes), preparing a run with a session, a route and a pace target, and starting on request from the watch.
- `live-run-screens`: adds the coaching and map screens to the live pager and the pacer fields; the combined bottom field shows moving time and is renamed (requirements "Main metrics screen", "Mini-map area" and "Choose the displayed fields" change).

## Impact

- **Server** (`server/`): a second migration (best efforts, daily load, recurring segments and their passages, workouts, routes, delivery state, activity links to a workout and a route); new pipeline modules (best efforts, predictions, load, segment matching, route building); new API resources under `/api/v1` and three new optional fields on `PATCH /activities/{id}`; a background recomputation job. `docs/api/openapi.json` and `docs/metrics.md` grow accordingly. Existing endpoints keep their behaviour.
- **Web** (`web/`): new sections (progress, segments, planner) and navigation; additions to the activity page. New prototype `prototype/insights-planner.html`.
- **Android** (`android/`): `core` gains the workout engine, the pacer, the cue scheduler, route geometry and version 2 of the watch protocol; `phone` gains plan storage and sync, coaching and map screens, text-to-speech, and an optional background-location permission for the start from the wrist; `wear` gains the coaching page, cue vibrations and the start request. New prototype `prototype/mobile-coaching.html`. Room schema version 2.
- **Dependencies**: MapLibre Native for Android (map screen); the phone's built-in text-to-speech engine with a French voice installed. No new server or web dependency is expected.
- **Privacy**: with the basemap on, the phone requests map tiles over mobile data during a run, which reveals the areas viewed to the tile host. The option can be switched off, and an empty map style on the server disables it. The server still makes no outbound request.
- **Assumptions recorded**: the phone is carried on every run; a session, route or pace target is always chosen on the phone (a run started from the wrist is a free run); records use moving time (pauses excluded), by the owner's decision, while segment efforts use elapsed time; load, recovery and predictions are estimates and are labelled as such; the interface stays in French and voice announcements are in French.
