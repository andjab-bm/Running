# Design

## Context

See `proposal.md` for motivation and scope. The architecture fixed in `running-foundations/design.md` Decision 1 (phone records, FIT file, plain LAN upload, server as system of record) and the phone/watch structure of `android-recording/design.md` are not reopened. This document decides how analysis, planning and coaching are added on top.

What exists today and is relied upon (observed in the code):

- **Server**: one migration (`server/migrations/0001_init.sql`) with `profiles`, `activities` (summary columns, `effort`, `zone_seconds`, zstd-compressed `streams` blob), a `segments` table that holds **splits and laps** (the name is taken), `tags`. The pipeline (`server/src/pipeline/metrics.rs`) is pure: `build_streams`, `segment(axis, a, b)`, `splits`, `laps`, `zones`, `process`. `store.rs` has `recompute_profile`, `copy_activity`, `move_activity`, `delete_activity`. The router (`server/src/api/mod.rs`) is hand-listed and mirrored in `api/openapi.rs`, with a test that fails when `docs/api/openapi.json` is stale. GPX reading (`pipeline/gpx.rs`) rejects a track without timestamps as "a route". `/api/v1/config` exposes `map_style_url` and `map_style_url_dark`.
- **Web**: SvelteKit static SPA with three sections (`/`, `/import`, `/settings`) in `web/src/routes/+layout.svelte`; reusable `ActivityMap` (MapLibre), `MetricChart` (uPlot), `Trace`; tokens in `web/src/lib/tokens.css`; Playwright suites in `web/e2e/`.
- **Android `core`** (pure Kotlin): `SessionEngine` driven by `Event`s and emitting `Signal`s and a `Snapshot`; the run in progress is an event log replayed after a kill (`EventCodec`); `FitEncoder`; `WatchProtocol` version 1 with fixed binary layouts on six paths.
- **Android `phone`**: `RunController` (the single run), `RecordingService` (location foreground service started from the ready screen), Room schema version 1, `SyncEngine` on WorkManager under an unmetered constraint, a horizontal pager with two live screens, a tile-less mini-map canvas.
- **Android `wear`**: `HrService` (Health Services exercise, heart rate only), `WatchSession`, two pages drawn with plain Compose.

Constraints:

- **Hardware**: Raspberry Pi (ARM64, target below 128 MB idle), Pixel 8, Galaxy Watch 4 (12 points of battery per hour for the watch app).
- **Platform rules** (read in the Android documentation through Context7 on 2026-11-16): an app in the background cannot start a foreground service unless an exemption applies (among them: the user turned off battery optimisation for the app), and a `location` foreground service cannot be created from the background unless the app holds `ACCESS_BACKGROUND_LOCATION`.
- **Owner's decisions for this change**: one phased change; the watch only *starts* a run, the phone still records; the basemap is loaded online during the run; interactive prototypes are approved before interface code.
- **Privacy**: the server makes no outbound request; the phone talks to the home server, plus — new, and optional — the tile host of the server's map style.

## Goals / Non-Goals

**Goals:**

- Every derived figure (best efforts, load, segment efforts) is recomputable from stored streams, like the existing metrics, and is kept consistent by one mechanism.
- Coaching logic (steps, pacer, route progress, cue ordering) lives in `core`, is driven by the same event stream as the session engine, and is tested by replaying scripted runs on a desktop JVM.
- The watch stays a thin remote: no new sensor, storage or network on it.
- A free run with every coaching option off behaves, and costs, exactly as today.

**Non-Goals:**

- Physiological models needing lab-grade inputs (VO2max, lactate threshold, HRV). Load, recovery and predictions use simple, documented formulas.
- Turn-by-turn instructions or routing on a road network: following a route means comparing the position with a polyline.
- Segments shared between profiles, leaderboards, or manually drawn segments.
- Editing sessions or routes on the phone; a planning calendar.
- Pre-downloaded basemaps, a map on the watch, watch GPS.
- A general job queue or scheduler on the server.

## Decisions

### 1. Derived data: one recomputation path, run in the background

A new module `server/src/derive/` owns everything computed *across* or *after* activities: best efforts, activity load, daily load series, segment detection and matching. It is fed by a single in-process queue (a `tokio` channel consumed by one worker task) of jobs `Activity(id)`, `Profile(id)` and `Backfill`. Import, delete, copy, move, journal patch (RPE) and profile-settings changes enqueue jobs after their own transaction commits; they never wait for the worker.

- Jobs are idempotent and coalesced (a profile job supersedes pending activity jobs of that profile). Each activity row carries a `derived_version`; at startup the worker enqueues every activity whose version is older than `DERIVED_VERSION`, which is also how history is backfilled after the migration and after an algorithm change.
- `GET /profiles/{pid}/derive-status` reports pending work so pages can say "processing" (specs `personal-records`, `personal-segments`).
- The worker is single-threaded and processes one activity at a time, which bounds memory on the Pi.

*Alternative*: compute inside the import request — simplest, but segment matching against history would break the "one-hour run imports in under 5 seconds" check of `running-foundations` and block batch imports. *Alternative*: compute on read — records and segments need history-wide scans; too slow on a Pi and impossible to make consistent cheaply.

### 2. Statistics are SQL aggregates, not stored

Heatmap days, weekly and monthly volume and monthly progress curves are aggregated on request from `activities` (indexed by profile and start time), bucketed in the profile's time zone (the browser's when the profile has none, passed as `tz_offset_minutes` like the import endpoint). A few thousand rows aggregate in milliseconds; nothing to keep consistent.

Endpoints: `GET /profiles/{pid}/stats/calendar?year=`, `/stats/volume?unit=week|month&from=&to=`, `/stats/progress?months=&tag=`.

*Alternative*: materialised daily table — only the load series needs one (Decision 4).

### 3. Best efforts and predictions

- **Best efforts**: for each standard distance, a two-pointer sweep over the full-resolution cumulative-distance and time arrays finds the window with the smallest **moving** time (the cumulative moving-time array the pipeline already builds from the moving mask), interpolating the window end between samples. Stored in `best_efforts(activity_id, distance_m, moving_s, start_offset_m)`, one row per distance per activity. Records are `MIN(moving_s)` per distance over the profile's non-excluded activities — a query, so they are always "current" (spec: rankings are not frozen). Moving time is the owner's decision: a record then agrees with the time the run itself shows, and a stop at a crossing does not spoil it. The price is that a rest taken as a pause is not counted; segment efforts keep elapsed time (Decision 5).
- **Sanity bound**: an effort faster than the world-record pace for its distance is discarded, which removes most GPS jumps without user action; `activities.exclude_from_records` covers the rest.
- **Predictions**: Riegel's formula `T2 = T1 × (D2 / D1)^1.06`. For each target distance, every qualifying effort of the last 12 weeks (≥ 1 km) is projected and the fastest projection wins, so a prediction is never slower than an actual recent result at that distance. Marked indicative when the target is more than four times the longest run of the window. The exponent and windows are constants documented in `docs/metrics.md`.

*Alternative*: a VO2max estimate from pace and heart rate (Garmin's approach) — needs reliable heart rate and a calibrated model; unverifiable here and misleading for a beginner. *Alternative*: elapsed-time efforts (what a race clock measures) — first proposed, declined by the owner.

### 4. Training load and recovery

- **Activity load**: `effort` (Edwards, already computed) when heart rate exists. Otherwise session-RPE: `moving minutes × RPE × K`, with `K = 0.35` chosen so that a typical RPE and its usual zone give comparable numbers (RPE 5 for 60 min ≈ 105, like 60 min around zone 2); without RPE, `moving minutes × ZONE_DEFAULT (2.0)`. `activities.load` and `load_estimated` are stored by the derive worker.
- **Daily series**: aggregated on read from `activities.load`, bucketed by day with the same time-zone offset as the statistics (Decision 2) — a stored per-day table was first planned and dropped during implementation, because the server has no time-zone database and the day boundary therefore comes with the request. Short-term and long-term loads are exponentially weighted moving averages with time constants of 7 and 28 days, computed over that series.
- **Status** from the ratio short/long: `< 0.5` resuming, `0.5–0.8` light, `0.8–1.2` steady, `1.2–1.5` building, `> 1.5` too much too soon; "not enough data" under 14 days of history.
- **Recovery**: `hours = clamp(12 + 24 × (load / typical) × max(1, ratio), 6, 96)`, where `typical` is the profile's median activity load over 6 weeks; the unrecovered remainder of the previous run is added at half weight. Remaining time is that minus hours since the end of the run. Deliberately simple and explained on the page.

*Alternative*: Banister impulse-response with fitted parameters — needs performance tests to fit; false precision. *Alternative*: heart-rate-reserve TRIMP — requires resting heart rate, which most beginners have not set; can replace the load definition later behind `DERIVED_VERSION`.

### 5. Recurring segments: a grid index and directional matching

Names: the existing `segments` table keeps splits and laps; the new tables are `courses` (the recurring portion) and `course_efforts`. The interface says "segments".

1. **Index**: each activity's trace is resampled every 20 m along distance and each point is mapped to a square cell of about 25 m (latitude/longitude scaled by the cosine of the latitude). `trace_cells(profile_id, cell, activity_id, seq, bearing8)` stores the ordered cells with the direction of travel in eight sectors. About 50 rows per kilometre; a year of running stays in the low hundreds of thousands of rows.
2. **Matching to known courses** (cheap, every new activity): a course is a polyline with its ordered cells. An activity matches when it visits the course's first cell (or a neighbour) in a compatible direction and then visits at least 90 % of the course's cells in order (neighbouring cells tolerated) until its last cell. Start and end are then refined to the closest stream samples and the effort is measured with the existing `segment()` on the time axis. Several matches per activity are allowed.
3. **Detection of new courses**: for the new activity, each resampled point counts the *other* activities of the profile present in its cell or a neighbour with a compatible direction. Maximal runs of consecutive points with a count ≥ 2 (so ≥ 3 traversals with the new one) and a length ≥ 300 m become candidates. A candidate overlapping an existing or hidden course by more than 70 % is dropped. A candidate is cut where the set of sharing activities changes by more than half (a junction where habits diverge), so a shared stretch does not swallow two distinct habits. New courses are then matched against the profile's history (step 2).
4. **Hidden** courses keep their row (`hidden = 1`) so detection does not recreate them; efforts keep being matched so restoring is complete.
5. **Names**: "1,2 km · <direction>" from length and overall bearing; no reverse geocoding (it would need an outbound request).

**As built** (details and constants in `docs/metrics.md` §13), where it differs from the plan above:

- Matching compares resampled points directly (within 25 m and 60° of heading), not cells; the grid is only used to find which other activities pass somewhere. `courses` therefore stores the path, not a cell list.
- A candidate overlapping a known course is not rejected at 70 %: the points already covered by a known course are removed first, so new segments never overlap existing ones.
- Candidates are also cut where a stretch comes back onto itself (laps of a loop), and lose two points at each end, where headings are still turning. Both came from tests: laps made one unmatched multi-lap segment on the owner's real exports, and corner points prevented earlier runs from being matched.
- A new segment is kept only if three activities then cover it completely.

*Alternative*: Fréchet / dynamic-time-warping similarity between all pairs — quadratic in history and far beyond a Pi. *Alternative*: an R-tree (as hinted in `running-foundations`) — the grid in SQLite does the same job with no dependency and survives restarts. *Alternative*: user-drawn segments only — the brief asks for automatic detection.

### 6. Sessions and routes: stored as documents, versioned for delivery

- `workouts(id, profile_id, name, note, steps JSON, on_phone, version, updated_at)`. `steps` is a list of items; an item is a **step** `{kind, end: {type: time|distance|lap, value}, target: {type: none|pace|hr_zone, lo, hi | zone}}` or a **repeat** `{count, skip_last_recovery, steps: [step…]}` (one level). Validation lives in one Rust function shared by create and update, mirrored by a pure-Kotlin parser in `core` tested against the same JSON fixtures (`server/tests/fixtures/workouts/`, committed — `testdata/` is git-ignored). Paces in the document are seconds per kilometre (`fast`, `slow`).
- `routes(id, profile_id, name, distance, gain, loss, is_loop, has_elevation, points BLOB, preview, on_phone, version, updated_at)`. `points` is the zstd JSON of parallel arrays (lat, lon, ele, cumulative distance), simplified to 5 m tolerance (Douglas–Peucker) and capped at 5 000 points. Elevation gain/loss reuse the activity elevation code. A route is built from a GPX `<rte>` or `<trk>` (times ignored) or from an activity's streams; GPX export reuses `pipeline/gpx.rs`.
- Starter sessions are templates embedded in the web app; adding one is an ordinary create.
- `version` increments on every change; `on_phone` is the "mark for the phone" flag.

API: `GET|POST /profiles/{pid}/workouts`, `GET|PUT|DELETE /workouts/{id}`, `POST /workouts/{id}/copy`; `GET /profiles/{pid}/routes`, `POST /profiles/{pid}/routes` (multipart GPX), `POST /activities/{id}/route` (JSON `{name, from_m, to_m}`, a route from a run), `GET|PATCH|DELETE /routes/{id}`, `POST /routes/{id}/reverse`, `POST /routes/{id}/copy`, `GET /routes/{id}/export.gpx`.

*Alternative*: FIT workout and course files as the stored form — the phone app is ours, so a JSON document both sides validate is simpler than two more FIT message families; GPX export covers interoperability for routes.

### 7. Delivery: the phone pulls a manifest during sync

- `GET /profiles/{pid}/plan` returns every item with `on_phone = 1`: `{workouts: [{id, version, …full document}], routes: [{id, version, …metadata}]}`; route points are fetched separately (`GET /routes/{id}/points`) only for new or changed versions. The response also carries the map style URLs so the phone needs no extra call.
- After applying it, the phone sends `PUT /profiles/{pid}/plan/receipt` with `{device, items: [{kind, id, version}]}`. `plan_receipts(profile_id, kind, item_id, version, device, received_at)` is what the web reads to show "on the phone since…" or "waiting" (receipt version older than the item's).
- On the phone this is a new step at the end of the existing `SyncEngine` attempt (same unmetered-and-server-answers condition, same WorkManager chain). Items attached to the prepared or running session are pinned and reconciled when it ends. Room gains `workouts` and `routes` tables (schema version 2, with a migration test).

*Alternative*: the server pushes — it cannot reach a sleeping phone, as already established. *Alternative*: export files by hand — defeats "à envoyer vers le mobile".

### 8. Coaching in `core`: engines beside the session engine

`SessionEngine` stays the authority on time, distance and pauses. Three new pure components consume its outputs:

- **`WorkoutRunner`**: holds the unfolded step list and the current step's progress in moving time and distance, taken from snapshot deltas. Emits `StepStarted`, `Countdown`, `SessionComplete`, `TargetOut`/`TargetBack`. A step change issues an internal `Lap` to the session engine, so steps *are* laps and the FIT file needs no new message. A manual lap press is intercepted as "end this step". Auto-pause is suppressed by a flag on the session engine while an effort step shorter than two minutes is current.
- **`RouteTracker`**: projects each accepted fix on the route polyline, searching only a window ahead of the last matched position (plus a bounded re-acquisition search when lost), which handles self-crossing routes and joining partway. Gives progress along the route, lateral distance, on/off-route state with the 40 m / 10 s hysteresis, and arrival.
- **`Pacer`**: a plan is a monotone table `distance → target moving time`. Fixed pace: a straight line. Route with target time: per 100 m slice, cost = length × Minetti factor of the slice's smoothed grade (the same polynomial and ±30 % clamp as `docs/metrics.md`, ported to `core` and checked against server-generated values); times are the costs scaled so the total equals the target. Ahead/behind = planned time at the current progress − moving time; progress comes from `RouteTracker` when on the route, from distance covered otherwise (flagged approximate).

All three are replayable: the attached plan is written as the first entries of the run's event log (`EventCodec` gains `Plan` events carrying the workout document, the route id and version, and the pacer target), so the existing replay-after-kill restores coaching state with no extra mechanism.

**Cues** are values, not side effects: the engines emit `Cue(kind, priority, text parts, createdAt)`; a `CueQueue` in `core` orders by priority, drops stale ones (20 s) and suppresses everything but pause/resume while paused. The phone's `CuePlayer` only renders them.

*Alternative*: fold steps and pacing into `SessionEngine` — it would grow three unrelated responsibilities and every coaching change would risk recording.

### 9. Rendering cues on the phone

- **Voice**: Android `TextToSpeech` with the French locale, on the device's engine; sentences are built by a small French formatter in `core` (numbers as a runner says them), unit-tested. Availability of an offline French voice is checked in the settings.
- **Audio focus**: a transient "may duck" focus request around each utterance or tone, with the assistance-navigation usage attribute, so music lowers and resumes; cues are skipped while the telephony state is not idle.
- **Sound**: short synthesised tones (one pattern per cue kind) generated at build time into `res/raw`, played through `SoundPool`.
- **Vibration**: `VibrationEffect` waveforms, one pattern per kind, defined once in `core` as millisecond arrays so the watch uses the same patterns.
- Everything runs inside `RecordingService`, which already holds the wake lock, so cues work with the screen off.

*Alternative*: pre-recorded voice clips — natural but cannot say arbitrary figures. *Alternative*: a bundled neural voice — tens of megabytes and CPU for a marginal gain.

### 10. Map screen: MapLibre Native, online tiles, only when visible

- **Library**: MapLibre Native for Android (BSD licence, no telemetry, same style format as the web's MapLibre GL JS), hosted in Compose through `AndroidView` with its lifecycle forwarded. The route, the done/ahead split, the trace and the position are GeoJSON sources with line and circle layers, updated at 1 Hz while visible.
- **Style**: the URLs come from the server (`map_style_url`, `map_style_url_dark`), cached by the sync. The phone therefore talks to exactly the tile host the owner configured for the web, and an empty value disables the basemap everywhere.
- **Online by the owner's decision**, with three guards: the "basemap during the run" setting (on by default, with the privacy sentence required by spec `route-navigation`); the `MapView` exists only while the map page is the visible page and the screen is on, so nothing is fetched otherwise; MapLibre's ambient cache (50 MB by default, kept) serves recently seen areas offline.
- With the basemap off the same screen draws the layers over an empty style — no second code path.
- **Mini-map** (owner's decision at the prototype approval, 2026-11-19): the mini-map of the main screen shows the basemap too, under the same setting. It is not a second live `MapView`: a `MapSnapshotter` renders a still image of the basemap for the extent of the trace (or the route), from the same style and the same ambient cache, and the existing canvas draws the route, the trace and the position over it at 1 Hz. A new snapshot is taken only when the extent grows beyond the current image, and only while the screen is on and the main page is visible — a few small requests per run, none with the screen off. With the basemap off or no style, no snapshot is taken and the canvas draws on the plain background as before.
- The run's cleartext-HTTP allowance is unchanged; tile hosts are HTTPS.

*Alternative*: pre-download tiles around the route at home — the privacy-preserving option, offered and declined by the owner for now; it can be added later without changing the screen. *Alternative*: osmdroid with raster tiles — lighter, but a second look to maintain and no dark style matching the web.

### 11. Start from the wrist

- The watch sends a new `START_NEW` command when no session exists. On the phone a `WearableListenerService` (woken by the Data Layer) asks `RunController` to create a session and starts `RecordingService` from the background.
- This needs two things, requested together when the runner enables the option: `ACCESS_BACKGROUND_LOCATION` ("allow all the time"), without which a `location` foreground service cannot be created from the background; and exemption from battery optimisation, which is the documented exemption that lets the app start a foreground service from the background at all.
- The phone acknowledges with the reason on failure (permission revoked, optimisation re-enabled); the watch waits 10 s. On failure the phone posts a notification that opens the app on the ready screen — a notification tap is itself an allowed start.
- The phone advertises the option in the state message (`canStartFromWatch`) so the watch only offers what will work.
- **Verified on the devices first** (task group 15 opens with it): whether a Data Layer message plus the two grants reliably starts the service on the owner's Pixel 8 build. If it does not, the fallback is a full-screen-intent notification the runner confirms on the phone; the spec scenario "phone in the pocket, app closed" would then be re-discussed with the owner before going further.

*Alternative*: Companion Device Manager association, which carries its own background-start permission — designed for accessory apps and heavier to set up; kept as the second thing to try if the first fails.

### 12. Watch protocol version 2

- The `state` payload gains a version-2 layout appended after the version-1 fields: flags (`canStartFromWatch`, session active, pacer active, route active, off route, position searching), step kind, step index and count, step remaining (seconds or metres, with its unit), target low/high, and pacer delta in seconds. The decoder accepts versions 1 and 2, so a phone and a watch updated one after the other keep working; the version-1 tests stay.
- A new path `/wellbeing/cue` (phone → watch) carries the cue kind; the watch maps it to the shared vibration pattern. It is sent as a message, received by the already running `HrService`, so it works with the watch screen off and adds no wake-up of its own.
- The coaching page is a third page of the existing pager, present only when the flags say so. Nothing is persisted on the watch.
- Battery: at most one extra small message per cue, and the state message grows by about 20 bytes at an unchanged rate.

### 13. Activities learn what they were run with

`activities` gains `workout_id`, `workout_snapshot` (JSON: name and steps as run), `route_id`, `route_name`, `pacer` (JSON: target, strategy, final gap) and `exclude_from_records`. `PATCH /activities/{id}` accepts them as three optional objects — `workout` (id, name, steps), `route` (id, name) and `pacer` (target, value, strategy, final gap), `null` removing one — validated like the journal; the phone sends them in the same patch as title, effort and note, reusing the `journal_dirty` retry. Ids are plain references without cascade: deleting a session or a route leaves the snapshot and the name in place (specs `workout-library`, `route-library`). The activity page labels laps with steps by position — step *n* is lap *n* — which holds because step changes are the only laps of a guided run.

### 14. Web structure

Navigation becomes Activités · Progression · Segments · Planifier · Réglages, with Importer moved to a button on the activity list (the bottom bar on phones fits five items). "Progression" has three tabs — Statistiques, Records, Charge — and "Planifier" two — Séances, Parcours. Routes: `/progress/[tab]`, `/segments`, `/segments/[id]`, `/plan/workouts`, `/plan/workouts/[id]`, `/plan/routes`, `/plan/routes/[id]`. Charts reuse uPlot; the heatmap, zone-style bars and session outline are plain CSS/SVG; maps reuse `ActivityMap`. This layout is a starting point that the prototype may change; the routes above follow whatever is approved.

### 15. Two prototypes as gates

Same mechanism as before: `prototype/insights-planner.html` (web: progress tabs, segment list and page, session editor, route page, additions to the activity page) and `prototype/mobile-coaching.html` (phone: ready screen with attachments, step screen, pacer, map screen with and without basemap, coaching settings, plan list; watch: start from the wrist, coaching page). Both are self-contained pages on the approved tokens, published as private Claude artifacts, with fictional data. The web prototype gates groups 9–10; the mobile prototype gates the interface tasks of groups 12–15. Server groups 2–7 and the `core` engines (group 11) do not wait.

### 16. Resume after stop: `STOPPED` is no longer final until the run is saved

- `SessionEngine` accepts `Resume` in phase `STOPPED`: the stop's timer event stays, a timer start is added, and the lap that the stop had closed is reopened (the lap record is produced only when the run is saved, so nothing has to be undone). In the file this is an ordinary stop/start pair, which the server already reads as a pause — no server change. Fixes received while stopped add no distance, like during a pause.
- The event log is kept until save or discard instead of being deleted at stop, so the summary survives the app being closed and a resumed run is replayable. `WorkoutRunner`, `RouteTracker` and `Pacer` simply see a pause.
- `RecordingService` stays in the foreground while the summary is shown, in a low-power state (location updates suspended, wake lock released), so that resuming needs no new foreground-service start; it stops at save or discard, and after 30 minutes on the summary the run is kept as interrupted and reopened by the existing resume-or-end prompt.
- The watch protocol's phase `STOPPED` now means "stopped, not saved"; a new phase value signals the end (saved or discarded). The watch keeps its exercise for 5 minutes in `STOPPED` (spec `watch-companion`).

*Alternative*: a confirmation step before stop instead of a resume — the hold-to-stop already is one; the owner wants to undo a stop after seeing the summary.

### 17. Watch tile and opening the other device's app

- **Tile**: a `TileService` built with the Wear Tiles library (protolayout). It reads a small snapshot (phase, moving time, distance, heart rate, time of the snapshot) that `HrService` writes to memory-backed preferences on each state message, and requests a tile update at most every 30 s while a run is joined; outside a run the tile is static. The tile's taps are launch actions on the watch activity, with an extra asking for a start. It therefore adds no sensor and no link traffic of its own. A tile cannot refresh every second: figures up to a minute old are the platform's limit, stated in the spec.
- **Start from the tile** opens the activity (required anyway to start the heart-rate exercise) and then follows the normal path: join the prepared session, or the start from the wrist of Decision 11.
- **Opening the other app**: `RemoteActivityHelper` (androidx.wear remote interactions) from the phone to the watch node and from the watch to the phone, with an explicit intent for our own package. Whether Wear OS 4 on the Galaxy Watch 4 brings the activity to the front from a remote request is **checked on the devices first**; the fallback on the watch side is a Data Layer message to a listener service that posts a high-priority notification the runner taps. On the phone side a locked phone shows the app after unlocking.

*Alternative*: a complication instead of a tile — too small for three figures and a start control. *Alternative*: a tile that itself talks to the phone — a second link client to keep alive for no gain.

### 18. Household activity list

- A new `GET /activities` lists across profiles with the existing cursor, tag and date filters plus an optional `profile_id`. `GET /profiles/{pid}/activities` is kept unchanged for the phone.
- Without `profile_id`, a copy whose original still exists is left out and the original carries `shared_with: [{profile_id, activity_id}]`, so a run done together is one row; with `profile_id` the profile's rows are returned as they are. Tags are matched by name across profiles.
- The web keeps the person filter in `localStorage` as `all`, `mine` or a profile id; `mine` resolves to the active profile at request time. Each card shows the owner's avatar (both for a shared run), and the activity page names the owner. Monthly totals above the list follow the filter.
- Everything else stays keyed by the active profile: importing, statistics, records, load, segments, planner.

*Alternative*: remove the notion of active profile from the list only on the client by fetching each profile's list and merging — pagination across several cursors is fragile and copies cannot be collapsed cleanly.

## Risks / Trade-offs

- **A remote request may not be allowed to bring an app to the front on one of the devices** → checked first on the devices; notification fallback; the feature is worded "offer to open" and reports failure.
- **A tile showing figures up to a minute old may look broken** → the tile shows the age of its figures and one tap opens the live screen.
- **A summary left open keeps the service and the watch exercise alive** → low-power state on the phone, 5-minute limit on the watch, 30-minute limit on the phone.

- **Automatic segments may be too many, too few, or oddly cut** → thresholds are constants in one place with fixture tests built from overlapping synthetic traces; the runner can hide and rename; a check on the owner's real history is a tracked task before the interface is finalised.
- **Grid matching misreads parallel paths less than 25 m apart, or dense GPS noise in town** → direction sectors and the 90 % in-order rule limit false matches; documented as a limit.
- **Predictions and recovery look authoritative** → labelled as estimates in the interface and explained in `docs/metrics.md`; long-distance predictions are marked indicative.
- **Backfill of a large history on the Pi takes minutes** → background worker, progress reported, activities usable meanwhile; measured on the Pi in the integration group.
- **Background start from the watch may be refused by the phone's system build** → checked first on the device; fallback and second approach described in Decision 11; the option is off by default.
- **"Allow all the time" location is a broad permission** → asked only when the runner enables the option, explained, and the app still uses location only while a run is prepared or recording.
- **Online tiles reveal the runner's surroundings to the tile host and use mobile data** → the owner's decision; setting with an explicit sentence, map loaded only while looked at, basemap can be disabled on the phone or on the server.
- **MapLibre Native adds roughly 10 MB to the APK and uses the GPU** → only instantiated on the map page; a free run that never opens it pays nothing.
- **Text-to-speech quality and availability vary with the installed engine** → checked in settings; sound and vibration always work.
- **Bluetooth headphones delay or swallow short tones** → tones are at least 150 ms and preceded by focus acquisition; checked on the owner's headphones in the field task.
- **Distance-based steps depend on GPS accuracy (a 200 m repeat can be off by 10–20 m)** → inherent to phone GPS; stated in `docs/android.md`; track sessions can use time or the lap button.
- **Protocol version skew between phone and watch** → the decoder accepts both versions; documented that both apps are installed together.
- **Deltas target capabilities that are not in `openspec/specs/` yet** → the two earlier changes are archived first (see proposal); recorded in the workflow follow-up of `tasks.md`.
- **The change is large** → phased groups with their own tests and documentation, each leaving the product working; analysis (groups 2–5, 9) is usable before any coaching exists.

## Migration Plan

- **Server**: migration `0002` adds tables and nullable columns only; it runs at startup like the first one. The derive worker then backfills existing activities in the background. Roll-back is the previous image with the data directory backed up beforehand, as `docs/deployment.md` already requires — migrations are forward-only.
- **Phone**: Room schema 1 → 2 with an automatic migration covered by a test; local runs are kept. New settings default to "off" except target alerts, off-route alerts, watch vibrations and the basemap.
- **Watch and phone** are updated together; either order works for a run thanks to the dual-version decoder.
- **Order of roll-out**: server and web first (analysis and planner usable at once), then the phone, then the watch.

## Open Questions

- Exact wording of the load statuses and of the French voice sentences (settled on the prototypes and during the field run).
- Whether the heatmap defaults to distance or to load (prototype).
- Tone design for each cue kind (tuned by ear with the owner's headphones).
