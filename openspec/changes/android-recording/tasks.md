# Tasks

Tasks marked **(device)** need the owner's Pixel 8 and/or Galaxy Watch 4; everything else can be done on a development machine with the Android emulator.

The current development machine (2 cores, 6 GB of memory) cannot run the emulator next to a build, so the checks worded "in the emulator" are done on the owner's Pixel 8 instead, following the checklist in `docs/android.md`. Code for 5.2–5.5, 6.1–6.6, 7.2, 7.3, 7.5, 8.1, 8.3, 8.4 and 9.3–9.6 is written and compiles; those tasks stay open until checked on the phone. The sync logic itself (7.1, 7.4) is covered by unit tests and by a run of the client against a real local server.

## 1. Phone and watch prototype (approval gate)

- [x] 1.1 Build `prototype/mobile-recording.html` as one self-contained page on the approved tokens, with a phone frame and a round watch frame, a Light/Dark switch and a simulated run that ticks; verify it opens from the file system with no build step and no console errors
- [x] 1.2 Implement the phone screens: ready (position and watch state), main metrics with zone colour, current lap, locked, paused and auto-paused, hold-to-stop, end-of-run summary (title, effort, note, save/discard), history with weekly and monthly totals and sync states, run page, settings (server, profile, fields, auto-pause, keep screen on, theme); verify every screen is reachable by clicking and renders in both themes without horizontal scroll at 412 px width
- [x] 1.3 Implement the watch screens: join / nothing prepared / phone unreachable, metrics, controls, paused, finished, and an ambient variant; verify nothing is clipped by the round mask at 396 px and 450 px
- [x] 1.4 Publish the prototype as a private Claude artifact and give the owner the link; verify the published page renders and the simulated run, lock and theme switch work there
- [x] 1.5 Apply the owner's requested adjustments (republishing each time) and record the explicit approval, the mobile-specific tokens and the ambient-screen decision in `docs/design-direction.md`; **do not start group 2 before this file records the approval**

## 2. Android project scaffolding

- [x] 2.1 Read current documentation (Context7) for the Android Gradle plugin, Kotlin, Compose, Compose for Wear OS, Room, WorkManager, DataStore, OkHttp, kotlinx.serialization, Play services location and wearable, and Wear Health Services, and pin versions in `android/gradle/libs.versions.toml`; verify every library named in `design.md` has a pinned version and any deviation from the design is noted in `docs/android.md`
- [ ] 2.2 Create the `android/` Gradle project with modules `core` (pure Kotlin), `phone` and `wear` sharing one application id; verify `./gradlew :core:test :phone:assembleDebug :wear:assembleDebug` succeeds and both apps launch to an empty screen in emulators
- [ ] 2.3 Add an Android job to `.github/workflows/ci.yml` running `core` and `phone` unit tests and lint, and uploading both debug APKs as artefacts; verify the job passes on a clean checkout
- [x] 2.4 Write `docs/android.md` (toolchain, build commands, installing on the phone and on the watch over Wi-Fi debugging, signing note) and link it from `README.md`; verify the documented build commands run as written

## 3. Session engine (core)

- [x] 3.1 Implement the session state machine (ready, running, paused, auto-paused, stopped) with start, pause, resume, lap and stop commands; verify unit tests for the pause-and-resume and manual-pause-not-auto-resumed scenarios of `run-recording`
- [x] 3.2 Implement fix filtering and distance accumulation; verify unit tests for the position-jump scenario and a scripted 5 km trace within 1 % of its known length
- [x] 3.3 Implement auto-pause with its thresholds, aligned with the moving constants in `docs/metrics.md`; verify unit tests for the traffic-light and auto-pause-disabled scenarios and that the constants are documented in `docs/android.md`
- [x] 3.4 Implement lap bookkeeping, smoothed current pace, average and lap pace, per-split pace history (for the pace bars), running elevation gain and the live metrics snapshot; verify unit tests for the lap scenarios of `run-recording` and the steady-running and standing-still scenarios of `live-run-screens`
- [x] 3.5 Implement state rebuilding by replaying stored events; verify a unit test that replays a truncated event log and obtains the same snapshot as the uninterrupted run up to that point

## 4. FIT encoder and server contract

- [x] 4.1 Implement the FIT encoder (`file_id`, timer events, records, laps, session, activity, CRC) with heart rate joined by nearest timestamp; verify unit tests check the header, CRC and message counts of a scripted run
- [x] 4.2 Generate `server/tests/fixtures/android-recorder.fit` from a scripted run (three laps, one manual pause, one auto-pause, heart rate, cadence) with a `core` test that fails when the committed file differs from the encoder's output; verify the test passes and the fixture is committed
- [x] 4.3 Add a server integration test importing that fixture; verify it asserts three laps, moving time excluding both pauses, distance within 1 %, and average heart rate and cadence within 1 % of the scripted values, and a second fixture without heart rate reports it unavailable
- [ ] 4.4 Validate one generated file with a third-party FIT decoder; verify the result (tool, version, outcome) is recorded in `docs/android.md`

## 5. Recording on the phone

- [x] 5.1 Create the Room database (runs, samples, heart-rate samples, laps, timer events, cached server activities) and the settings store; verify a migration/schema test and a DAO round-trip test pass
- [ ] 5.2 Implement the permission flow (precise location, notifications, activity recognition) with explanations; verify in the emulator the permission-refused scenario of `run-recording`
- [ ] 5.3 Implement the location foreground service started from the ready screen, feeding fused location, barometer and step-detector readings to the engine and flushing samples every 5 s; verify with emulator route playback that a run records with the screen off and the notification shows its state
- [ ] 5.4 Implement saving: write the FIT file at stop, store the summary and journal fields, discard with confirmation, and the short-run default; verify in the emulator the save, discard and very-short-run scenarios, and that the saved file imports into a local server
- [ ] 5.5 Implement the resume-or-end prompt for an interrupted run; verify by killing the app process mid-run in the emulator that both choices behave as specified and at most 10 s of samples are missing

## 6. Phone interface

- [ ] 6.1 Implement the Compose theme from the approved tokens (colours, type, shapes, Light/Dark/System, bundled fonts) with the test comparing `core` tokens to `web/src/lib/tokens.css`; verify the test passes and the theme scenarios of `mobile-theming` in the emulator
- [ ] 6.2 Build the ready screen and the main metrics screen with its approved default layout (average pace with per-kilometre bars, cadence, heart rate, combined total time / distance / mini-map area drawing the trace so far), zone colour, placeholders and units; verify in the emulator the default-layout, pace-bars, mini-map, no-heart-rate, imperial and zone scenarios of `live-run-screens` (heart rate injected through a debug control)
- [ ] 6.3 Build the current-lap screen, the pause/lap/stop controls with hold-to-stop, and the paused and auto-paused states; verify the lap-screen, accidental-stop and auto-paused scenarios
- [ ] 6.4 Build the touch lock, keep-screen-on setting, display over the lock screen and the run notification; verify the rain, unlock, waking-the-phone and notification scenarios
- [ ] 6.5 Build the field-selection setting (combined field offered for the bottom slot only); verify a replaced field is used on the next run and survives an app restart
- [ ] 6.6 Build the end-of-run summary screen; verify a saved run shows its title, effort and note in the history
- [ ] 6.7 Check live-screen contrast in both themes; verify every figure reaches 7:1 and the measured ratios are added to `docs/design-direction.md`

## 7. Sync

- [x] 7.1 Implement the server client (health check with product signature, profiles, profile settings, import, journal patch, activity list); verify unit tests against a mock server for success, duplicate, failed file, unknown profile and unreachable host
- [ ] 7.2 Build server pairing and profile selection in the settings, caching units and zones; verify against a local server the valid-address, wrong-address and choose-a-profile scenarios of `run-sync`
- [ ] 7.3 Implement the background sync work (unmetered constraint, enqueue on save, periodic check while runs are pending, oldest first, journal fields applied after import, later journal edits sent as a patch without re-upload); verify against a local server the coming-home, several-pending-runs, effort, no-title and both carry-later-edits scenarios, and that nothing is sent on a metered emulator network
- [x] 7.4 Implement retry and failure handling and the manual sync action; verify the connection-lost, already-on-the-server, file-refused, profile-deleted and server-unreachable scenarios
- [ ] 7.5 Document pairing, the home-network rule and the cleartext-HTTP caveat in `docs/android.md`; verify the pairing steps work as written on a fresh install

## 8. Phone history

- [ ] 8.1 Build the run list with local runs, cached server activities merged without duplicates, sync-state badges and the empty state; verify the offline, imported-history, synced-run and no-run scenarios of `phone-history`
- [x] 8.2 Implement weekly and monthly totals; verify a unit test for the three-runs-this-week scenario including a week crossing a month boundary
- [ ] 8.3 Build the run page (summary, laps, trace drawing, journal fields with editing of title, effort and note, failure reason with retry); verify on a recorded three-lap run and on a server-only activity, and the change-the-effort-later and cancel scenarios
- [ ] 8.4 Implement FIT sharing and local deletion with the two confirmation texts; verify the shared file is byte-identical to the stored one and that deleting a synced run leaves the server activity in place

## 9. Watch companion

- [ ] 9.1 **(device)** Read current Health Services documentation (Context7), then check on the Galaxy Watch 4 which exercise capabilities and heart-rate batching modes it declares; verify the findings and the chosen configuration are recorded in `docs/android.md`
- [x] 9.2 Implement the watch protocol in `core` (state, heart-rate batch, command, acknowledgement messages; unsent-batch buffer with its cap); verify unit tests for encoding round trips, re-sending after a dropout and the buffer cap
- [ ] 9.3 Implement the phone side of the link (capability, message listener, heart-rate storage by timestamp, state push rate following the watch's screen state, command handling with acknowledgement); verify with paired phone and watch emulators that commands change the session and injected heart rate reaches the live screen
- [ ] 9.4 Implement heart-rate capture on the watch (exercise with heart rate only, foreground service, ongoing activity, permission flow, end-of-session and 10-minute timeout); verify with the Health Services synthetic data provider in the emulator that samples reach the phone and capture stops when the run stops
- [ ] 9.5 Build the watch interface (join states, metrics page (time on top, pace large in the centre, distance and heart rate below), controls page with hold-to-stop, paused, finished, unreachable, ambient) per the approved prototype; verify every scenario of `watch-companion` except battery in paired emulators, and no clipping on a round 396 px screen
- [ ] 9.6 Document watch installation, joining a run and the Samsung Health interaction in `docs/android.md`; verify the install steps work as written on the watch

## 10. Field checks on the owner's devices

- [ ] 10.1 **(device)** Record a run of at least one hour with the phone screen off and the watch joined, with laps, a manual pause and a stop at a crossing; verify the trace is continuous, heart rate covers the whole run, and distance is within 2 % of a known route length
- [ ] 10.2 **(device)** Come home with the app closed; verify the run appears on the server within 15 minutes with the same laps and pauses, a distance within 1 % of the phone's, and the effort and note entered on the phone
- [ ] 10.3 **(device)** Measure watch battery over the one-hour run and walk away from the phone for two minutes during a run; verify the drop is 12 points or fewer, heart rate is present over the dropout, and both figures are recorded in `docs/android.md`
- [ ] 10.4 **(device)** Observe the phone's network traffic during a run and the following sync; verify the app connects only to the configured server
- [ ] 10.5 Copy the field-test FIT file into `testdata/` with its key figures in `testdata/README.md` (this is the file wanted by `running-foundations` task 3.9); verify the server's real-file test passes on it
- [ ] 10.6 Update `docs/architecture.md` (phone and watch built, module layout) and run `openspec validate android-recording --strict`; verify every spec scenario has been checked on the devices or by an automated test

## Workflow follow-up

- Archive this change once the owner has used the apps on a few real runs.
- Propose the next change (`live-coaching` or `training-insights`, at the owner's choice).
