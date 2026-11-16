# Proposal

## Why

The server and web app built by `running-foundations` can only show runs that were recorded elsewhere and uploaded by hand, so the product is not usable day to day and still depends on a third-party app to capture a run. Recording on the owner's own devices (Pixel 8, Galaxy Watch 4) and getting the run onto the home server without any cloud is the missing piece that makes everything already built useful.

The remaining `running-foundations` tasks (CI, Playwright suite, Docker packaging, Raspberry Pi checks) are deliberately postponed by the owner; this change does not depend on them — a server started from source on the home network is enough.

## What Changes

- **Interactive prototype of the phone and watch screens (gate)**: a clickable mock-up, published as a Claude artifact, of the phone screens (ready, live metrics, current lap, locked, paused, end-of-run summary, history, settings) and the watch screens, in the pastel borderless direction already approved for the web. It must be approved by the owner before any Android code is written.
- **Native Android phone app** (Kotlin, Jetpack Compose) that records a run: GPS trace, distance, instantaneous and average pace, elapsed and moving time, elevation, cadence, and heart rate received from the watch. Start, manual pause, auto-pause, manual lap, stop; touch lock; recording continues with the screen off and survives the app being killed.
- **Live screens on the phone**: a main metrics screen whose fields can be chosen, with the heart-rate zone colour of the active profile, and a current-lap screen.
- **One `.fit` file per run**, written on the phone, carrying laps, pauses, heart rate and cadence — the contract fixed in `running-foundations` design Decision 1.
- **Automatic local sync**: the phone uploads finished runs to the home server through the existing import endpoint as soon as it is on an unmetered network where the server answers; no cloud, no account. End-of-run title, perceived effort and note are applied to the imported activity.
- **Phone history**: list of runs with basic totals, a simple run page, sync state, and sharing of the `.fit` file.
- **Minimal Wear OS companion** for the Galaxy Watch 4: heart-rate capture sent to the phone, Start/Pause/Lap/Stop controls, and one glanceable metrics screen. The watch does no GPS, no storage of runs and no network access of its own.
- **Dark and light themes** on the phone, reusing the web design tokens; a dark-only, low-power look on the watch.

This change absorbs the planned `wear-os-companion` change, except for interval/pace vibration alerts, which belong to coaching.

### Out of scope

- All live coaching, by the owner's decision: voice announcements, virtual pacer, guided intervals (`live-coaching`), and structured sessions or routes pushed to the phone (`workout-planner`).
- A map screen during the run and GPX route following (they come with route support in `workout-planner` / `live-coaching`).
- Standalone recording on the watch without the phone; starting a session from the watch when nothing has been prepared on the phone.
- Health Connect, Play Store publication, iOS.
- Statistics, records, training load on the phone (`training-insights`); segments (`personal-segments`).
- The postponed `running-foundations` tasks (2.5, 3.9, 8.7, 8.8, groups 9 and 10), except that the multi-lap heart-rate FIT file wanted by task 3.9 becomes available as a by-product of this change.

## Capabilities

### New Capabilities

- `run-recording`: recording a run on the phone — session control (start, pause, auto-pause, lap, stop, discard), the data captured, robustness of the recording, and the activity file produced.
- `live-run-screens`: what the runner sees and touches on the phone during a run — metric screens, zone colouring, field selection, touch lock, lock-screen notification.
- `run-sync`: pairing the phone with the home server and a profile, automatic upload of finished runs on the home network, retries, and the privacy guarantees of that exchange.
- `phone-history`: consulting runs on the phone — list, basic totals, run page, sync state, file sharing, deletion of the local copy.
- `watch-companion`: the Wear OS app — heart-rate capture, session controls, glanceable metrics, behaviour when the link with the phone drops, and its resource envelope.
- `mobile-theming`: visual consistency of the phone and watch apps with the approved web direction, and theme selection on the phone.

### Modified Capabilities

None. `openspec/specs/` is still empty (the `running-foundations` specs are not archived yet). This change relies on the behaviour described in that change's `activity-import`, `activity-library` and `athlete-profile` specs without altering it.

## Impact

- **New code**: `android/` (Gradle project with a phone app, a Wear OS app and shared pure-Kotlin modules), `prototype/mobile-recording.html`. A FIT fixture produced by the Android encoder and a contract test are added under `server/tests/`.
- **Server and web**: no behaviour change intended. The phone uses existing endpoints only (`GET /health`, `GET /profiles`, `GET /profiles/{id}`, `POST /profiles/{id}/imports`, `PATCH /activities/{id}`, `GET /profiles/{id}/activities`). Any gap found while integrating is fixed in the server and recorded as a deviation.
- **Docs**: `docs/architecture.md` (phone and watch move from "planned" to built), new `docs/android.md` (build, install on phone and watch, pairing), `docs/design-direction.md` (mobile addendum and approval), `README.md`.
- **Dependencies**: Android SDK and Gradle toolchain (JDK), Jetpack Compose, Compose for Wear OS, Room, WorkManager, Google Play services (fused location, Wearable Data Layer), Wear Health Services. CI gains an Android build-and-unit-test job.
- **Systems**: the owner's Pixel 8 and Galaxy Watch 4, with apps installed by sideloading (developer mode on both devices). The server must be reachable over plain HTTP on the home network.
- **Assumptions recorded**: the phone is carried during the run (it is the GPS recorder); a run is always prepared on the phone, and the watch app is opened by hand to join it; the app targets recent Android only (the owner's devices); the interface is in French like the web app.
