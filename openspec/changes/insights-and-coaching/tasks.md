# Tasks

Tasks marked **(device)** need the owner's Pixel 8 and/or Galaxy Watch 4. As in `android-recording`, the development machine cannot run the emulator next to a build, so phone and watch behaviours are checked on the devices with the checklists added to `docs/android.md`.

Phases: groups 2–7 (server) and 11 (`core`) can start at once; groups 9–10 wait for the approval recorded by 1.4; the interface tasks of groups 12–15 wait for the approval recorded by 8.4. After groups 2–5 and 9 the analysis part is usable on its own.

## 1. Web prototype (approval gate)

- [x] 1.1 Build `prototype/insights-planner.html` as one self-contained page on the approved tokens with fictional data and the new navigation (Activités, Progression, Segments, Planifier, Réglages; import as a button); verify it opens from the file system with no build step and no console errors, in both themes, without horizontal scroll at 360 px and 1440 px
- [x] 1.2 Implement the Progression screens: calendar heatmap with year and measure switch, weekly/monthly volume bars, progress curves with tag filter, records with history and predictions (including an indicative one and the "no recent effort" state), load curves with status and recovery, and the "how is this computed" panel; verify every state named in specs `training-statistics`, `personal-records` and `training-load` is reachable by clicking
- [x] 1.3 Implement the segment list and segment page, the session library and editor (steps, repeat block, targets, outline, totals, starter sessions), the route library and route page, the "on the phone" marks with waiting/delivered states, and the activity-page additions (record marks, segment efforts, session steps against targets, route and pacer result); verify each is reachable and renders in both themes
- [x] 1.4 Publish the prototype as a private Claude artifact, apply the owner's adjustments (republishing each time), and record the explicit approval, the final navigation and any new token in `docs/design-direction.md`; **do not start groups 9 and 10 before this file records the approval**

## 2. Server: derived-data foundations

- [x] 2.1 Write migration `0002`: `best_efforts`, `trace_cells`, `courses`, `course_efforts`, `workouts`, `routes`, `plan_receipts`, `plan_syncs`, and the new nullable `activities` columns (`load`, `load_estimated`, `derived_version`, `exclude_from_records`, `workout_id`, `workout_snapshot`, `route_id`, `route_name`, `pacer`); verify it applies on an empty database and on a copy of a version-1 database with activities, with a migration test
- [x] 2.2 Implement the derive worker (`server/src/derive/`): single-consumer queue with `Activity`, `Profile` and `Backfill` jobs, coalescing, `DERIVED_VERSION`, enqueue at startup for outdated activities, and enqueue hooks after import, delete, copy, move, journal patch and profile-settings change; verify integration tests show a job runs after each of those operations and that the import request returns before it completes
- [x] 2.3 Implement `GET /profiles/{pid}/derive-status` and add it to the router and the OpenAPI list; verify a test sees pending work during a batch import and none afterwards, and the OpenAPI freshness test passes

## 3. Server: statistics

- [x] 3.1 Implement `GET /profiles/{pid}/stats/calendar`, `/stats/volume` and `/stats/progress` as time-zone-aware aggregates with tag filter; verify integration tests reproduce the two-runs-one-day, late-Sunday, empty-year, switch-to-months and month-without-heart-rate scenarios of `training-statistics`
- [x] 3.2 Verify statistics follow the library: integration tests for delete, move, copy and profile isolation; add the three routes to the OpenAPI document and verify its freshness test passes
- [x] 3.3 Implement `GET /activities` (household list: cursor, tag and date filters, optional `profile_id`, copies collapsed into `shared_with` when no profile is given) and add it to the OpenAPI document; verify integration tests for the another-member, run-done-together, one-member and paging scenarios of `activity-library`, and that `GET /profiles/{pid}/activities` is unchanged

## 4. Server: records and predictions

- [x] 4.1 Implement best-effort search (two-pointer over full-resolution streams, moving time with pauses excluded, interpolated window end, world-record sanity bound) in the pipeline; verify unit tests for the fast-middle-kilometre, shorter-than-distance, pause-inside-the-stretch (28:40 on the clock gives 28:00) and same-rule-as-the-run scenarios and for an injected GPS jump being discarded
- [x] 4.2 Store best efforts from the derive worker and implement `GET /profiles/{pid}/records` (record, next two, history per distance) and the record marks in `GET /activities/{id}`; verify integration tests for the records-page, never-run, beaten-later, delete-the-record-run and copied-activity scenarios
- [x] 4.3 Accept `exclude_from_records` on `PATCH /activities/{id}`; verify a test for the faulty-GPS scenario, including the restored previous record
- [x] 4.4 Implement predictions (Riegel, 12-week window, fastest projection, indicative flag) in `GET /profiles/{pid}/predictions`; verify unit tests for the recent-5-km, no-recent-effort, never-slower-than-a-recent-result and marathon-for-a-5-km-runner scenarios
- [x] 4.5 Document best efforts, the sanity bound and the prediction formula with their constants in `docs/metrics.md` and add the routes to the OpenAPI document; verify every new constant named in the code appears in the document and the freshness test passes

## 5. Server: load and recovery

- [x] 5.1 Implement activity load (effort, else session-RPE, else duration default, with the estimated flag) stored by the derive worker (the daily series is aggregated on read, see `design.md` Decision 4); verify unit tests for the three load scenarios and an integration test that entering RPE later updates the load
- [x] 5.2 Implement short- and long-term load, status and recovery time in `GET /profiles/{pid}/load`; verify unit tests for the heavy-week, two-weeks-off, steady, risky-jump, new-profile, hard-session, recovered and second-run-before-recovery scenarios of `training-load`
- [x] 5.3 Verify load follows settings: an integration test changes the profile's maximum heart rate and sees past loads and the status recomputed; document the formulas and constants in `docs/metrics.md` and add the route to the OpenAPI document, verifying the freshness test passes

## 6. Server: recurring segments

- [x] 6.1 Implement trace resampling, the cell grid with direction sectors and the `trace_cells` index, maintained by the derive worker on import, delete, move and copy; verify unit tests for cell assignment across a cell border and an integration test that deleting an activity removes its cells
- [x] 6.2 Implement matching of an activity against known courses (first cell, 90 % in order, refinement to stream samples, several matches) producing `course_efforts`; verify unit tests on synthetic traces for the known-segment, turning-back, opposite-direction, two-laps and other-side-of-the-road scenarios of `personal-segments`
- [x] 6.3 Implement detection of new courses (three traversals, 300 m minimum, overlap rejection, cut at diverging habits) and the back-match of history; verify unit tests for the riverside, only-twice and third-passage scenarios, and that a hidden course is not recreated
- [x] 6.4 Implement `GET /profiles/{pid}/segments` (sorting, hidden list), `GET /segments/{id}` (trace, profile, ranked efforts with gaps), `PATCH /segments/{id}` (rename, hide, restore) and the segment efforts in `GET /activities/{id}`; verify integration tests for the list, page, best-time mark, hide, restore and delete-a-run scenarios and per-profile isolation
- [x] 6.5 Run detection on the owner's real history in `testdata/` (when present) and tune the constants; verify the resulting segment count and three examples are recorded in `docs/metrics.md` together with every constant, and that a one-hour activity is matched in under a minute on the development machine
- [x] 6.6 Add the segment routes to the OpenAPI document; verify the freshness test passes

## 7. Server: sessions, routes and delivery

- [x] 7.1 Implement session validation (step kinds, end conditions and ranges, targets, one-level repeats) and `GET|POST /profiles/{pid}/workouts`, `GET|PUT|DELETE /workouts/{id}`, `POST /workouts/{id}/copy`, with `version` bumped on change; commit valid and invalid fixtures under `server/tests/fixtures/workouts/` (`testdata/` is git-ignored); verify integration tests for every create, validation, duplicate, delete and copy scenario of `workout-library`
- [x] 7.2 Implement route building from GPX `<rte>`/`<trk>` without requiring times and from an activity (whole or portion), with simplification, figures and loop detection; verify unit tests for the 9.4 km import, unusable-file, several-routes, from-activity, loop and no-elevation scenarios of `route-library`
- [x] 7.3 Implement the route endpoints (list, create by upload or from an activity, detail, points, rename, reverse, copy, delete, `export.gpx`) and make the activity import's "no timestamps" refusal name the route alternative; verify integration tests for reverse, export round trip within 0.5 %, copy and the import refusal message
- [x] 7.4 Implement the `on_phone` mark, `GET /profiles/{pid}/plan` (documents, route metadata, map style URLs), `PUT /profiles/{pid}/plan/receipt` and the delivery state returned with sessions and routes; verify integration tests for the waiting, delivered and edited-since-delivery scenarios of `plan-delivery`
- [x] 7.5 Accept `workout` (id, name and steps as run), `route` (id and name) and `pacer` on `PATCH /activities/{id}`, store them in the `workout_id`, `workout_snapshot`, `route_id`, `route_name` and `pacer` columns, and return them, with step labels on laps, in `GET /activities/{id}`; verify integration tests for the session-review, session-cut-short, session-deleted-meanwhile and route-followed scenarios
- [x] 7.6 Add all routes of this group to the OpenAPI document and describe sessions, routes and delivery in `docs/architecture.md`; verify the freshness test passes

## 8. Phone and watch prototype (approval gate)

- [x] 8.1 Build `prototype/mobile-coaching.html` on the approved mobile tokens, reusing the frames and the ticking simulated run of `prototype/mobile-recording.html`; verify it opens from the file system with no console errors in both phone themes at 412 px
- [x] 8.2 Implement the phone screens: ready screen with session, route and pacer attachments and strategy preview; step screen (in target, too fast, open-ended step, countdown, session complete); main screen with the pacer figure; map screen with and without basemap, on and off route, arrival; summary with steps, pacer result and the resume action; the bottom field labelled "Temps"; "open on the watch" on the ready screen; plan list with empty state; coaching settings (channels with test buttons, announcement interval, alerts, basemap with its privacy sentence, start from the watch); verify every screen is reachable by clicking
- [x] 8.3 Implement the watch screens: start from the wrist (offered, starting, refused), coaching page for a step and for the pacer, off-route indication, the stopped-not-saved state, "open on the phone", and the tile in its three states (run in progress, no run, run not joined); verify nothing is clipped by the round mask at 396 px and 450 px
- [x] 8.4 Publish the prototype as a private Claude artifact, apply the owner's adjustments (republishing each time), and record the explicit approval and any mobile token in `docs/design-direction.md`; **do not start the interface tasks of groups 12–15 before this file records the approval**

## 9. Web: progress section

- [x] 9.1 Restructure the navigation as approved (new sections, import reachable from the activity list) and add the API client functions for groups 3–5; verify the existing Playwright suite passes after updating its navigation steps
- [ ] 9.2 Build the statistics tab (heatmap with year and measure switch and day detail, volume bars with week/month switch, progress curves with tag filter and gaps); verify in the browser against a seeded server the scenarios of `training-statistics`, in both unit systems
- [x] 9.3 Build the records tab (records with next two, history chart, predictions with basis, indicative mark and empty state, processing notice from the derive status); verify in the browser the records-page, never-run and no-recent-effort scenarios
- [x] 9.4 Build the load tab (daily and weekly load, short/long curves, status, recovery remaining, estimated marks, explanation panel); verify in the browser the status and recovery scenarios against seeded data
- [ ] 9.5 Add Playwright coverage for the three tabs and extend `e2e/design.spec.ts` contrast checks to them in both themes at 360 px and 1440 px; verify the suite passes in CI
- [x] 9.6 Make the activity list show the whole household with the person filter (everyone, mine, one member) remembered on the device, owner avatars on cards (both for a shared run), totals following the filter, and the owner named on the activity page; verify in the browser every scenario of `activity-library` "List activities" and "Filter activities" and the household-list-personal-statistics scenario, with Playwright coverage

## 10. Web: segments, planner and activity page

- [x] 10.1 Build the segment list (sorting, empty state, hidden list, processing notice) and the segment page (map, profile, ranked efforts with gaps, chronological chart, rename, hide); verify in the browser the list, page, hide and restore scenarios of `personal-segments`
- [x] 10.2 Build the session library and editor (steps, reorder, repeat block with skip-last-recovery, targets in the profile's units and zones, outline, totals as exact / minimum / unknown, duplicate, delete, copy to profile, starter sessions); verify in the browser every scenario of `workout-library` except the activity-page ones
- [x] 10.3 Build the route library and route page (GPX upload, create from an activity with optional portion, map with hover-synchronised profile, activities run with it, rename, reverse, copy, export, delete); verify in the browser every scenario of `route-library` except the activity-page one
- [x] 10.4 Build the "on the phone" mark with waiting/delivered states and last phone sync on sessions and routes; verify in the browser, using a script that plays the phone's calls, the mark, delivered and edited-since-delivery scenarios
- [x] 10.5 Add to the activity page: record marks with the highlighted stretch, segment efforts with rank, gap and map/chart highlight, session steps against targets, route and pacer result, the exclude-from-records action, and "create a route from this activity"; verify in the browser on seeded activities with and without each element
- [ ] 10.6 Add Playwright coverage for segments, the session editor, routes and the activity-page additions, with contrast checks; verify the suite passes in CI and update the `web/` section of `docs/architecture.md`

## 11. Android `core`: coaching engines

- [x] 11.1 Read current documentation (Context7) for MapLibre Native Android, Android text-to-speech, audio focus and background location, pin new versions in `android/gradle/libs.versions.toml`; verify each new library has a pinned version and deviations from `design.md` are noted in `docs/android.md`
- [x] 11.2 Implement the session document parser and step unfolding in `core`; verify unit tests read every fixture of `testdata/workouts/` with the same accept/reject outcome as the server
- [x] 11.3 Implement `WorkoutRunner` (progress by moving time and distance, lap ends a step, steps issue laps, countdown, completion, target in/out with the 15 s and 30 s rules, auto-pause suppression in short efforts); verify unit tests replay scripted runs for every scenario of `guided-workout` requirements "Step progression", "Pauses do not consume a step", "Target feedback" and "End of the session"
- [x] 11.4 Implement `RouteTracker` (windowed projection, re-acquisition, off-route hysteresis, arrival, joining partway); verify unit tests for the halfway, self-crossing, joining-partway, missed-turn, back-on-route, position-inaccuracy and arrival scenarios of `route-navigation`
- [x] 11.5 Implement `Pacer` (fixed pace, even and gradient-adjusted route plans, ahead/behind on moving time, current target pace, approximate mode off route, final result); verify unit tests for every scenario of `virtual-pacer` requirements "Ahead or behind", "Gradient-adjusted strategy on a route", "Strategy preview" and "Off the route or past its end", and that the Minetti port matches values generated by the server within 0.1 %
- [x] 11.6 Implement cue values, `CueQueue` (priority, staleness, quiet while paused), the shared vibration patterns and the French sentence formatter; verify unit tests for the no-pile-up and paused scenarios of `run-announcements` and for the spoken form of paces, times and distances in both unit systems
- [x] 11.7 Add `Plan` events to `EventCodec` and wire the three engines into replay; verify a unit test replays a truncated log of a guided, paced, routed run and obtains the same step, pacer and route state as the uninterrupted run
- [x] 11.8 Implement watch protocol version 2 (extended state, `START_NEW` command, cue path, a phase telling "stopped, not saved" from "ended") with a decoder accepting versions 1 and 2; verify round-trip unit tests for both versions and that version-1 tests still pass
- [x] 11.9 Make `SessionEngine` accept a resume after a stop (stop/start timer pair, lap reopened, no distance while stopped) with the coaching engines seeing a pause; verify unit tests for the stopped-by-mistake, one-activity, resume-in-a-session and moved-while-stopped scenarios of `run-recording` "Resume after stopping", a replay test of a stopped-then-resumed log, and a server test importing the regenerated FIT fixture that reports the stop as a pause

## 12. Phone: plan delivery, guided session and pacer

- [ ] 12.1 Add `workouts` and `routes` to Room (schema 2) with the migration, the event log's `text` column for the run's plan, and the plan step of `SyncEngine` (fetch manifest, fetch changed route points, apply, pin items in use, send receipt, cache map style URLs, clear on profile change); verify a migration test keeps existing runs, and unit tests against a mock server for the prepared-the-evening-before, edited, unmarked, away-from-home, sync-while-preparing and changing-profile scenarios of `plan-delivery`
- [ ] 12.2 Build the plan list (sessions with outline and totals, routes with trace and figures, empty state) and extend the manual sync outcome; verify on the phone against a local server the nothing-delivered and just-marked scenarios
- [ ] 12.3 Extend the ready screen with the session, route and pacer attachments (distance to the route start, target pace or target time, strategy choice and preview, pacer unavailable with a session), writing the plan to the event log at start; verify on the phone the scenarios of `run-recording` "Prepare a run with a plan" and `virtual-pacer` "Set a pace target" and "Pacer and guided session"
- [ ] 12.4 Build the step screen as first live page of a guided run, the dot indicator, and the steps in the end-of-run summary; verify on the phone, with a debug control that replays a scripted run, the scenarios of `guided-workout` "Live step screen" and of `live-run-screens` "Live screens adapt to the run"
- [ ] 12.5 Show the pacer on the main screen and add ahead/behind and target pace to the selectable fields; verify on the phone the scenarios of `live-run-screens` "Pacer on the live screens" and `virtual-pacer` "Current target pace"
- [ ] 12.6 Write steps as laps in the saved run, and send the session, route and pacer context in the journal patch with the existing retry flag; verify a `core` test regenerates a guided-run FIT fixture, a server test imports it and finds fourteen labelled laps, and `SyncEngine` unit tests cover the two scenarios of `run-sync` "Carry the coaching context"
- [ ] 12.7 Kill the app during a guided run and resume it **(device)**; verify the session continues in the same step with at most 10 s of progress lost, and add the plan, ready-screen and guided-run checks to the checklist in `docs/android.md`
- [ ] 12.8 Add the resume action to the end-of-run summary: event log kept until save or discard, service kept in a low-power foreground state on the summary with its 30-minute limit, summary restored after the app is closed, no resume on a saved run; verify on the phone the scenarios of `run-recording` "Resume after stopping" and "End-of-run summary", and add them to the checklist in `docs/android.md`
- [ ] 12.9 Make the combined bottom field show moving time, label it "Temps" and name it "Temps, distance et mini-carte" in the field choice; verify unit tests of the field model and on the phone the time-stops-during-a-pause and name-of-the-combined-field scenarios of `live-run-screens`

## 13. Phone: voice, sound and vibration

- [ ] 13.1 Implement `CuePlayer` in the recording service: text-to-speech in French, transient ducking audio focus, tones, vibration waveforms, call-state check, routing to headphones; verify instrumented or Robolectric tests that each cue kind calls the enabled channels only, and nothing but vibration during a call
- [ ] 13.2 Implement the per-kilometre announcement with its interval and optional contents, off by default; verify unit tests for the third-kilometre, disabled, imperial and every-two-kilometres scenarios of `run-announcements`
- [ ] 13.3 Build the coaching settings (three channels, test buttons, announcement options, target, pacer and off-route alert switches, French-voice availability notice); verify on the phone the vibration-only, try-the-cues and no-French-voice scenarios
- [ ] 13.4 **(device)** Check cues with the screen off and music playing through the owner's headphones; verify the podcast-lowered, screen-off and distinct-patterns scenarios, tune tone lengths, and record the outcome and the voice-installation steps in `docs/android.md`

## 14. Phone: map screen

- [ ] 14.1 Add MapLibre Native and build the map page (empty style with route, done/ahead split, trace and position layers; controls and key figures overlaid; north-up / heading-up, zoom, whole-route view, return to position after 10 s; locked state), created only while visible; verify on the phone the scenarios of `route-navigation` "Map screen", "Follow a route" and "Map orientation and zoom", and of `live-run-screens` "Locked on the map"
- [ ] 14.2 Load the basemap from the cached server style with light and dark variants, the "basemap during the run" setting with its privacy sentence, and the plain-background fallback; verify on the phone the basemap-on, basemap-off, no-map-style and dark-theme scenarios
- [ ] 14.3 Wire off-route, back-on-route and arrival cues and the on-map distance to the route; make the mini-map open the map, show the route under the trace and the basemap under both through a `MapSnapshotter` refreshed only when the extent grows, while the main page is visible and the screen on; verify on the phone with a replayed scripted run the missed-turn, back-on-route and arrival scenarios the mini-map scenarios of `live-run-screens` "Mini-map area", including with the basemap on and off
- [ ] 14.4 **(device)** Observe the phone's traffic during a run with the screen off, then with the map shown, then in airplane mode over an area already seen; verify the screen-off-for-the-whole-run and no-coverage scenarios and both network-inspection scenarios of `run-sync`, and record the result and the tile-host caveat in `docs/android.md`

## 15. Start from the wrist and coaching on the watch

- [ ] 15.1 **(device)** Prove the background start on the owner's Pixel 8 with a minimal build: a Data Layer message starting the location foreground service with the app closed, with "allow all the time" location and battery optimisation disabled; verify the outcome is recorded in `docs/android.md`, and stop to consult the owner if it fails with both the direct approach and the Companion Device Manager approach
- [ ] 15.2 Implement the "start from the watch" option on the phone (explanation, the two grants, off when refused), the listener service, the start with acknowledgement and failure reason, the failure notification, and the `canStartFromWatch` flag; verify unit tests for the acknowledgement paths and on the phone the four scenarios of `run-recording` "Start on request from the watch"
- [ ] 15.3 Implement the start from the wrist on the watch (offer only when advertised, waiting state, 10 s timeout, position-searching indication, join after start); verify on the devices the three scenarios of `watch-companion` "Start a run from the wrist" and the three "nothing prepared" scenarios of "Join a session"
- [ ] 15.4 Send the extended state and cue messages from the phone and add the watch-vibrations setting; build the coaching page and cue vibrations on the watch, received by the running heart-rate service; verify on the devices the scenarios of `watch-companion` "Coaching on the wrist" and the free-run scenario of "Glanceable metrics"
- [ ] 15.5 Update the watch section and its checklist in `docs/android.md` (start from the wrist, permissions, coaching page, protocol version 2, installing both apps together); verify a phone on version 2 and a watch still on version 1 complete a run together
- [ ] 15.6 Keep the watch measuring for up to 5 minutes after a stop and show the stopped-not-saved state; verify on the devices the three scenarios of `watch-companion` "End with the session"
- [ ] 15.7 **(device)** Read current documentation (Context7) for Wear remote interactions, then prove on the devices that the phone can open the watch app and the watch the phone app; implement "open on the watch" on the ready screen and "open on the phone" on the watch, with the notification fallback where a direct opening is refused; verify the three scenarios of `watch-companion` "Open the app on the other device" and record what each device allows in `docs/android.md`
- [ ] 15.8 Read current documentation (Context7) for Wear Tiles, pin the library, then build the tile (run in progress with time, distance, heart rate and their age; start control outside a run; opens the app), fed by the snapshot the heart-rate service keeps and refreshed at most every 30 s; verify on the watch the five scenarios of `watch-companion` "Watch tile", that nothing runs when the tile is not added, and document adding the tile in `docs/android.md`

## 16. Integration checks

- [ ] 16.1 Deploy the new server image on the Raspberry Pi with the existing data directory backed up; verify the migration runs, the backfill completes, its duration and the idle memory afterwards (below 128 MB) are recorded in `docs/deployment.md`, and no outbound request is made by the server
- [ ] 16.2 **(device)** Run one guided interval session with the watch joined, voice on and headphones, then come home; verify steps changed at the right moments, cues were delivered on phone and watch, the watch battery dropped by 12 points or fewer over the hour, and the web activity shows the labelled laps against their targets
- [ ] 16.3 **(device)** Run one paced run following a delivered route with the basemap on; verify the ahead/behind figure, an intentional off-route alert, the arrival announcement, and the route and pacer result on the web activity
- [ ] 16.4 Check on the owner's real data that records, predictions, load status and detected segments are plausible; verify any constant tuned as a result is updated in `docs/metrics.md` and covered by a test
- [ ] 16.5 Update `README.md` and `docs/architecture.md` (coaching built, what the watch does, privacy note on the basemap), run `openspec validate insights-and-coaching --strict`, and walk every scenario of this change's specs; verify each is covered by an automated test or a recorded device check

## Workflow follow-up

- Archive `running-foundations` and `android-recording` first: this change's deltas for `watch-companion`, `run-sync`, `run-recording` and `live-run-screens` apply to the specs those archives create.
- Archive this change once group 16 is done and the owner has used coaching on a few real runs.
- Standalone recording on the watch with its own GPS, and pre-downloaded basemaps, remain candidates for a later change.
