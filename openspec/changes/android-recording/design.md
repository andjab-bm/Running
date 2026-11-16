# Design

## Context

See `proposal.md` for motivation and scope. The overall architecture (watch → phone → Raspberry Pi, FIT as interchange, plain LAN upload) was fixed in `running-foundations/design.md` Decision 1 and is not reopened here; this document decides how the phone and watch sides are built.

What exists today and is relied upon (observed in the code):

- `POST /api/v1/profiles/{pid}/imports` — multipart, per-file outcome `imported` / `duplicate` / `failed`, each with `activity_id` or `reason`; optional `tz_offset_minutes` query used to name untitled runs (`server/src/api/imports.rs`).
- Every state-changing request must carry an `X-Requested-With` header; requests without `Origin` / `Sec-Fetch-Site` (a non-browser client) are accepted (`server/src/api/guard.rs`).
- The FIT reader takes `record` (timestamp, position, enhanced altitude, distance, heart rate, cadence), `event` timer start/stop, `lap.start_time` and `session.sport`; a trailing stop is the end of the run, not a pause (`server/src/pipeline/parse.rs`). Cadence is normalised to steps/min by a heuristic on its median.
- `PATCH /activities/{id}` accepts title, description, RPE and tags; `GET /profiles/{pid}` returns units and zones; `GET /health` returns status and version.
- Approved tokens live in `docs/design-direction.md` and `web/src/lib/tokens.css`; the web interface is in French.

Constraints:

- **Devices**: Pixel 8 (recent Android), Galaxy Watch 4 (Wear OS, weak SoC, small battery). Only the owner's devices are targeted.
- **Platform rules** (checked in the Android documentation through Context7): a `location` foreground service can only be started while the app has a visible activity unless the app holds background-location permission; the same holds on the watch for a `health` service needing body sensors. `MeasureClient` is documented as unsuitable for workouts; `ExerciseClient` is the intended API and supports a `HEART_RATE_5_SECONDS` batching override on devices that declare it.
- **Privacy**: no third-party SDK that phones home; the server speaks plain HTTP on the LAN.
- **Owner's decisions for this change**: phone plus a light watch app; no coaching at all; an interactive prototype is approved before code.

## Goals / Non-Goals

**Goals:**

- A run recorded on the phone reaches the server with the same laps, pauses and (near) the same figures the runner saw, through the existing import path, with no server change.
- Recording logic that can be tested by replaying recorded sensor traces on a desktop JVM, without a device.
- A watch app that is a thin remote: sensors and a few composables, no database, no network.
- One set of design tokens for web, phone and watch, with drift detected by a test.

**Non-Goals:**

- Background-location permission, and therefore starting a run from a cold watch or from a widget.
- Offline basemap or any map during the run.
- Conflict resolution between phone and server edits: the phone pushes its own later edits of title, effort and note (last write wins, see Decision 7) and never pulls journal edits made on the web into its local runs.
- A real map in the live screen: the mini-map area only draws the recorded trace, without tiles.
- Supporting other watches, other phones, tablets, or a Play Store release (no store listing, no consent screens beyond runtime permissions).
- Server discovery on the network (the address is typed once).

## Decisions

### 1. One Gradle project, three modules

```
android/
  core/    pure Kotlin (JVM): session engine, FIT encoder, formatting,
           watch protocol messages, design tokens
  phone/   Android app: service, sensors, Room, sync, Compose UI
  wear/    Wear OS app: Health Services, Data Layer, Compose for Wear OS
```

`core` has no Android dependency, so its tests run on any machine and in CI in seconds. `phone` and `wear` share the application id and signing key, which the Wearable Data Layer requires for the two apps to talk.

*Alternative*: Kotlin Multiplatform shared module — no second platform is planned, so it only adds build complexity. *Alternative*: put everything in `phone` — the watch needs the protocol and tokens, and Android-free logic is what makes replay tests cheap.

### 2. The session is a pure state machine fed by events

`core` holds a `SessionEngine`: `(state, event) → state + effects`, where events are `Location`, `HeartRate`, `Steps`, `Tick`, and commands `Start`, `Pause`, `Resume`, `Lap`, `Stop`. It owns distance accumulation, fix filtering, auto-pause, lap bookkeeping, smoothed pace and the live metrics snapshot. The Android service only translates sensor callbacks into events and persists effects.

- **Fix filtering**: fixes with reported accuracy worse than 25 m, or implying more than 12 m/s from the previous accepted fix, are stored but add no distance.
- **Auto-pause**: enters after 5 s below 0.6 m/s, leaves after 3 s above 1.2 m/s. Before implementing, these are aligned with the moving-mask constants in `docs/metrics.md` so that phone and server agree on moving time; the phone's pause is also written to the file as timer events, which the server prefers over its own detection.
- **Current pace**: derived from distance over a sliding 10 s window, blanked below the auto-pause speed.

*Alternative*: logic inside the Android service — untestable without instrumentation and the main source of bugs in recorders.

### 3. Foreground service started from the ready screen

The `location` foreground service starts when the runner opens the ready screen (activity visible), in a `Ready` state that acquires the position without recording. `Start` — from the phone or the watch — is then only an event sent to an already running service, which sidesteps the background-start restriction. Leaving the ready screen without starting stops the service.

Location comes from the fused location provider at 1 s interval, high accuracy; it runs on-device and gives better urban accuracy than the raw GPS provider. The service holds a partial wake lock while recording.

*Alternative*: request background-location permission so the watch can cold-start a run — a heavier permission and an extra system dialog for a marginal gain; rejected for now (recorded as a non-goal).

### 4. Elevation and cadence from the phone's own sensors

- **Elevation**: mean-sea-level altitude from the location fix as the absolute reference, corrected by the barometer for relative changes (the Pixel 8 has one); GPS-only when no barometer reading is available. The server smooths and applies hysteresis anyway, so the phone only shows a simple running gain.
- **Cadence**: the step-detector sensor, averaged over 10 s, needs the activity-recognition permission; if refused, cadence is absent. The file stores it the FIT way (strides per minute), which the server already converts.

*Alternative*: cadence from the watch — more Bluetooth traffic and battery for a metric the phone measures well when carried.

### 5. Local storage: Room, written as the run progresses

Tables: `runs` (state, summary, journal fields, sync state, server activity id, failure reason), `samples` (one row per second), `hr_samples` (time, bpm — kept separate because watch batches arrive late and out of order), `laps`, `timer_events`, `server_activities` (cached list for the history), plus DataStore for settings (server URL, profile, cached zones and units, field layout, theme).

While a run is in progress, what is stored is its **event log** (commands, fixes, step counts — an `events` table), flushed every 5 s inside one transaction, which bounds loss on a kill to under 10 s. A run in state `recording` found at app start triggers the resume-or-end prompt; the engine state is rebuilt by replaying the stored events, which gives exactly the run that was interrupted. `samples`, `laps` and `timer_events` are written from the engine when the run is saved, and the event log is then deleted.

Row-per-sample is right on the phone (unlike the server): a few thousand rows per run, simple recovery, and the data is needed to draw the trace and rebuild the file.

### 6. Our own minimal FIT encoder in `core`

Roughly 300 lines: file header, definition and data messages for `file_id`, `event` (timer start / stop_all), `record`, `lap`, `session`, `activity`, and the CRC. Heart rate is joined to records by nearest timestamp within 5 s when the file is written at save time (after a grace period of up to 5 s for the last watch batch).

The contract is enforced from both sides: a `core` test generates a deterministic file from a scripted run and compares it to the committed `server/tests/fixtures/android-recorder.fit`; a server test imports that fixture and asserts laps, pauses, distance, heart rate and cadence. A third-party decoder (the FIT SDK's validation tool or Garmin Connect's importer) is used once, by hand, to confirm the file is valid beyond our own parser.

*Alternative*: Garmin's official FIT SDK for Java — complete, but under a proprietary licence that sits badly in an open-source repository, and far more than is needed. *Alternative*: upload GPX — loses laps and pause events, and contradicts the architecture decision.

### 7. Sync with WorkManager over plain HTTP

- A unique work chain runs under an "unmetered network" constraint: enqueued when a run is saved, plus a 15-minute periodic check that exists only while runs are pending (a one-shot retry alone would back off for hours after failing on a foreign Wi-Fi).
- Each attempt: `GET /health` (must return this product's payload, 3 s timeout) → for each pending run, oldest first, `POST …/imports` with the FIT file, `X-Requested-With`, and `tz_offset_minutes` → on `imported` or `duplicate`, store `activity_id`, then `PATCH` the journal fields that were filled in → mark synced. `failed` marks the run failed with the reason and stops retrying it. A 404 on the profile pauses sync and raises the "choose a profile" prompt.
- Editing the title, effort or note of a synced run sets a `journal_dirty` flag on the run; the next attempt sends only a `PATCH` with the three fields (no re-upload) and clears the flag. A run with the flag set is shown as pending.
- Being on the home network is inferred from "unmetered and the server answers", as decided in `running-foundations`; the SSID is never read.
- HTTP with OkHttp and kotlinx.serialization. Cleartext traffic must be allowed app-wide in the network security configuration, because the server address is user-defined and the server has no TLS.
- The idempotence of retries rests on the server's duplicate detection (content hash, then start time and duration).

*Alternative*: sync only while the app is open — defeats "automatic". *Alternative*: a persistent connection or push from the server — the server cannot reach a sleeping phone and nothing needs to be real-time.

### 8. Phone UI: Compose, tokens shared with the web

Single-activity Compose app with four destinations: Record (ready → live → summary), History, Run page, Settings. The live screens are a horizontal pager (metrics, lap); the lock overlay swallows all input and unlocks on a long press with a progress ring; stop uses the same hold-to-confirm pattern. The activity is shown over the keyguard while a run is active.

Default main screen, as approved on the prototype: average pace with per-kilometre bars (top), cadence and heart rate (middle), and a combined field with total time, distance and the mini-map area (bottom). The bars come from split times kept by the engine; the mini-map area is a canvas drawing of the stored positions, fitted to its bounds, with no tile source — it is the slot a real map will take in a later change. The combined field is only offered for the bottom slot.

Tokens are declared once in `core` as plain constants mirroring `web/src/lib/tokens.css`; a `core` test parses that CSS file and fails on any difference. Bricolage Grotesque and Manrope are bundled in the apps (both under the Open Font License). Material 3 components are used with a custom colour scheme and shapes; dynamic colour is disabled so the palette stays the approved one. Mobile-only additions (live-figure sizes, 7:1 contrast variants if a pastel ink falls short) are recorded in `docs/design-direction.md` after the prototype is approved.

Interface strings are in French, in resource files.

### 9. Watch: Health Services exercise, Data Layer messages, two pages

- **Heart rate**: `ExerciseClient` with a running exercise, heart rate as the only data type, GPS off, and the 5-second heart-rate batching override when the watch declares it. Started from the watch app's visible activity (same platform restriction as the phone), wrapped in a `health` foreground service with an ongoing-activity indicator so the runner can return to it.
- **Link**: `MessageClient` on four paths — `state` (phone → watch, live snapshot, 1 Hz and only while the watch reports its screen interactive; on ambient, one update per minute), `hr` (watch → phone, batches of timestamped samples), `cmd` (watch → phone, with an id) and `ack` (phone → watch). The watch vibrates on `ack`, and shows "not applied" if none arrives within 2 s. The phone is found through a declared capability.
- **Dropouts**: unsent heart-rate batches stay in memory (capped at 30 minutes) and are re-sent on reconnection; the phone stores them by timestamp. Without `state` for 5 s the watch shows "téléphone injoignable"; after 10 minutes it ends the exercise.
- **UI**: Compose for Wear OS, a two-page pager (metrics — time on top, pace large in the centre, distance and heart rate below, zone arc on the rim; controls), ambient-mode aware, black background. No settings, tile or complication; nothing persisted.

*Alternative*: raw `SensorManager` heart-rate listener with a wake lock — keeps the CPU awake and costs noticeably more battery than Health Services' batching. *Alternative*: `DataClient` synced items for state — built for durable state, slower and heavier than messages for a 1 Hz snapshot.

### 10. Prototype first, as a hard gate

Same mechanism as for the web: one self-contained page, `prototype/mobile-recording.html`, published as a private Claude artifact, showing the phone screens in a phone frame and the watch screens in a round frame, in both phone themes, with a simulated run that ticks so the live screens, lock, pause, lap and summary can be exercised. Approval and mobile-specific tokens are recorded in `docs/design-direction.md`. No task in groups 2+ starts before that.

### 11. Build, versions and distribution

- Phone: minimum SDK 34 (the Pixel 8's original release), which gives mean-sea-level altitude and current foreground-service rules without compatibility branches. Watch: minimum SDK 30 (Wear OS 3), with the heart-rate permission chosen at runtime (body sensors, or the newer granular permission on Wear OS 6).
- Library and plugin versions are pinned in `android/gradle/libs.versions.toml` after reading current documentation, as was done for the server.
- Installation is by sideloading debug-signed builds: `adb install` on the phone, `adb` over Wi-Fi debugging on the watch; documented in `docs/android.md`. CI builds both APKs and runs `core` and `phone` unit tests; the APKs are attached as workflow artefacts.

## Risks / Trade-offs

- **The Galaxy Watch 4 may not support the 5-second heart-rate batching, or Samsung's Health Services may behave differently from the documentation** → checked on the real watch as the first watch task; fallback is default batching (samples keep their own timestamps, so the file is unaffected, but the live heart rate on the phone lags while the watch screen is off).
- **The watch must be opened by hand for each run** → the ready screen shows the watch state prominently so a missing heart rate is noticed before starting; the run is valid without it.
- **Starting an exercise on the watch ends any exercise tracked by another app (e.g. Samsung Health)** → acceptable and intended; documented.
- **Android may still kill a long-running service on some builds** → foreground service + wake lock + 5 s flushes + resume prompt; a field test of at least one hour is a tracked task.
- **Home-made FIT encoder may produce files other tools reject** → fixture cross-checked by the server's parser and once by a third-party decoder; only standard messages and fields are used.
- **Phone and server compute slightly different distance, pace or elevation** → the file carries the phone's cumulative distance, which the server prefers; a 1 % tolerance is specified; elevation gain may differ more and the server's value is authoritative after sync.
- **Cleartext HTTP is allowed app-wide** → the app only ever contacts the configured server; stated in `docs/android.md` together with the VPN advice for remote use.
- **An unmetered network that is not home, with some host answering at the same address** → the health payload is checked for this product's signature before anything is sent.
- **Debug-signed sideloaded builds: reinstalling with another key wipes local runs** → runs are synced and shareable as FIT; the signing setup is documented.
- **No instrumented tests on a real watch in CI** → watch logic is kept thin; the protocol and buffering live in `core` and are unit-tested; device behaviour is covered by tracked manual checks.
- **Fused location and the Data Layer depend on Google Play services** → both run on-device and send nothing to the project's knowledge beyond what the phone already does; accepted for accuracy and because the Data Layer has no alternative.

## Migration Plan

Nothing to migrate: new apps, no server schema or API change. Roll-out: start the server on the home network, install both APKs, pair the phone with the server and pick a profile, record a short test run. Roll-back: uninstall the apps; runs already synced stay on the server.

## Open Questions

- Whether the watch's ambient screen should show figures at all or only the time and state (decided on the prototype; no effect on specs or tasks).
- Exact hold durations for unlock and stop (tuned during the field test).
