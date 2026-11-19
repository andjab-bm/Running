# Checking the change `insights-and-coaching`

Every scenario of the change's 16 specs (242 in all) is covered either by an automated test or by a check on the devices. This page says which, and lists what remains to be checked by hand, in the order to do it. The device checks themselves are written out in [android.md](android.md) (phone checklist and watch checklist); this page points to them by number.

## Automated

Run them all before a field check:

```bash
cargo test --manifest-path server/Cargo.toml
```

```bash
npm --prefix web run check && npm --prefix web exec vitest run && npm --prefix web exec playwright test
```

```bash
cd android && ./gradlew :core:test :phone:testDebugUnitTest :phone:lintDebug :wear:lintDebug
```

| Spec | Scenarios | Where |
|---|---|---|
| `activity-library` | 11 | server `activities.rs`, `profiles.rs`; web `app.spec.ts`, `progress.spec.ts` (household list, person filter) |
| `training-statistics` | 13 | server `stats.rs` (Sunday week, two runs a day, deletion, other profile, heart-rate gap); web `progress.spec.ts` (calendar, day detail, empty year, volume weeks and months, curves and tag filter, imperial profile) |
| `personal-records` | 17 | server `records.rs`, `derive.rs`; web `progress.spec.ts`, `training.spec.ts` (record marks, exclusion) |
| `training-load` | 14 | server `load.rs`; web `progress.spec.ts` |
| `personal-segments` | 19 | server `segments.rs`, `derive.rs`; web `training.spec.ts` |
| `workout-library` | 16 | server `planner.rs` and the shared fixtures `server/tests/fixtures/workouts/`; web `workout.test.ts`, `training.spec.ts`; core `WorkoutTest` |
| `route-library` | 12 | server `planner.rs`; web `training.spec.ts` |
| `plan-delivery` | 12 | server `planner.rs` (plan, receipt, states); phone `SyncEngineTest` (delivered, edited, unmarked, away from home, sync during preparation, other profile, items in use kept); web `training.spec.ts` (waiting and delivered states) |
| `guided-workout` | 19 | core `WorkoutRunnerTest`, `CoachTest` (steps, pauses, skip, open-ended step, target alerts, end, replay after a kill), `FitEncoderTest` + server `planner.rs` (14 labelled laps); phone `CuePlayerTest` (all channels off); **device**: phone 15, 17 |
| `virtual-pacer` | 16 | core `PacerTest` (fixed pace, target time, grade-adjusted plan, preview, crossing, before the position, finish, shortcut, alerts), server `grade_factor.rs`; web `training.spec.ts` (result kept); **device**: phone 14, 16 |
| `route-navigation` | 17 | core `RouteTrackerTest` (halfway, figure-of-eight, joining partway, missed turn, back on route, inaccuracy, arrival); phone `MapGeometryTest` (basemap source, frames, done/ahead split, return after 10 s); **device**: phone 25–29 |
| `run-announcements` | 14 | core `AnnouncerTest` (third kilometre, disabled, imperial, every two kilometres), `SpeechTest` (sentences, distinct patterns), `CueQueue` in `SpeechTest` (order, stale, paused); phone `CuePlayerTest` (each channel, vibration only, phone call, no French voice, samples); **device**: phone 21–24 |
| `live-run-screens` | 16 | phone `FieldsTest` (combined field, time stops in a pause, pacer fields), `MapGeometryTest`; **device**: phone 3, 11, 15, 16, 19, 25, 27, 28 |
| `run-recording` | 16 | core `ResumeTest`, `SessionEngineTest`, server `android_fit.rs` (one activity after a resume); phone `WatchStartTest` (start on request, reasons); **device**: phone 14, 18; watch 12 |
| `run-sync` | 4 | phone `SyncEngineTest` (coaching context, session deleted meanwhile); **device**: phone 29 (network inspection) |
| `watch-companion` | 26 | core `WatchProtocolV2Test` (version 2 both ways, measuring after a stop), `WatchTileTest`; phone `WatchCuesTest`, `WatchStartTest`; **device**: watch 1–14 |

## To check by hand, in this order

1. **Push and let CI run** (tasks 9.5 and 10.6): the Playwright suites pass locally (41 passed, 4 skipped); they are ticked once CI has run them.
2. **Raspberry Pi** (task 16.1): back up the data directory, deploy the new image, note the migration and backfill duration and the idle memory (below 128 MB) in [deployment.md](deployment.md), and check the server makes no outbound request.
3. **Your real data on the web** (task 16.4): records, predictions, load status and detected segments look plausible; any constant tuned goes into [metrics.md](metrics.md) with a test.
4. **Phone, at home** — checklist items 13 to 20 (plan, ready screen, guided run, paced run, kill during a session, resume after a stop, Temps field, coaching context on the web), then 21 to 23 (coaching settings, no French voice, announcement) and 25 to 28 (map, route, lock, mini-map). Tasks 12.2–12.5, 12.7–12.9, 13.3, 14.1–14.3.
5. **Phone and watch together** — watch checklist items 7 and 9 to 11 (end with the session, coaching page, vibrations, tile), then **12 (start from the wrist) — task 15.1 first**: if the phone does not start with its app closed, stop there and tell me; the design's next option is the Companion Device Manager. Then 13 (open on the other device) and 14 (watch on version 1). Tasks 15.1–15.8.
6. **Outdoors** — phone item 24 (music, headphones, screen off; tune the tones), item 29 (traffic with screen off, map shown, airplane mode), then the two field runs: a guided interval session with the watch, voice and headphones (task 16.2: steps at the right moments, cues on both, watch battery −12 points or fewer, labelled laps on the web) and a paced run on a delivered route with the basemap (task 16.3: ahead/behind, an off-route alert on purpose, the arrival, the route and pacer result on the web).

Each check names the scenarios it covers. Note the outcome next to it in [android.md](android.md) (or in [deployment.md](deployment.md) for the Pi), and the task is ticked once its checks pass.
