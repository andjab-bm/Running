# Android apps (phone and watch)

The phone app records runs and syncs them to the home server; the watch app sends heart rate and acts as a remote. The reasoning is in the OpenSpec change [`android-recording/design.md`](../openspec/changes/android-recording/design.md); the approved look is in [design-direction.md](design-direction.md).

```
android/
  core/    pure Kotlin (JVM): session engine, FIT encoder, formatting,
           watch protocol, design tokens — no Android dependency
  phone/   Android app (Pixel 8): recording service, storage, sync, interface
  wear/    Wear OS app (Galaxy Watch 4): heart rate, controls, metrics
```

Both apps use the application id `dev.wellbeing.app`: the Wearable Data Layer only connects a phone app and a watch app that share the id and the signing key.

## Toolchain

- **Android Studio** (any recent version) provides everything: its bundled JDK (`<Android Studio>/jbr`, JDK 17 or newer) and the SDK manager. Install SDK platform **37** and build-tools **36.0.0**.
- The Gradle wrapper in `android/` downloads Gradle itself (9.9.1, checksum pinned in `gradle/wrapper/gradle-wrapper.properties`).
- `android/local.properties` (not committed) must point at the SDK, e.g. `sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk`. Android Studio writes it when it opens the project; otherwise set `ANDROID_HOME`.

From a terminal, `JAVA_HOME` must point at a JDK:

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
```

If Gradle fails at start-up with `Unable to establish loopback connection` (seen on Windows with JDK 25 when the temp directory is not usable for local sockets), give the JVM another directory:

```powershell
$env:JAVA_TOOL_OPTIONS = "-Djdk.net.unixdomain.tmpdir=C:\some\writable\dir"
```

## Build and test

Run from `android/`:

```bash
./gradlew :core:test
```

```bash
./gradlew :phone:testDebugUnitTest :phone:lintDebug
```

```bash
./gradlew :phone:assembleDebug :wear:assembleDebug
```

The APKs land in `phone/build/outputs/apk/debug/` and `wear/build/outputs/apk/debug/`. CI runs the same commands and attaches both APKs to the workflow run.

`./gradlew :core:test -PupdateFixtures=true` rewrites the FIT fixture shared with the server tests (`server/tests/fixtures/`) after an intentional change to the encoder.

## Libraries and pinned versions

Versions were taken from Google Maven, Maven Central and services.gradle.org on 2026-11-15 and are pinned in `android/gradle/libs.versions.toml`. Current documentation was read through Context7 for the build setup (Android Gradle plugin 9 built-in Kotlin, `compileSdk` DSL), foreground-service start restrictions and Wear Health Services; the others are read when first used.

| Library | Version | Role |
|---|---|---|
| Gradle | 9.9.1 | build tool (wrapper) |
| Android Gradle plugin | 9.5.1 | Android build, with built-in Kotlin |
| Kotlin | 2.4.21 | `core` module, Compose compiler and serialization plugins |
| KSP | 2.3.14 | Room code generation |
| Compose BOM | 2026.09.00 | phone interface (Material 3) |
| activity-compose / lifecycle / navigation-compose | 1.13.0 / 2.11.0 / 2.10.2 | app shell |
| core-ktx | 1.19.1 | Android helpers |
| Room | 2.8.5 | local database of runs and samples |
| WorkManager | 2.12.0 | background sync |
| DataStore (preferences) | 1.3.0 | settings |
| OkHttp | 5.5.1 | HTTP client to the home server |
| kotlinx.serialization (JSON) | 1.13.0 | API payloads |
| kotlinx.coroutines | 1.11.0 | concurrency |
| Play services location | 21.4.0 | fused location (on-device) |
| Play services wearable | 20.0.1 | phone ↔ watch Data Layer |
| Compose foundation (from the BOM) | — | watch interface |
| androidx.wear | 1.4.0 | ambient mode on the watch |
| wear-ongoing | 1.1.0 | ongoing-activity indicator on the watch |
| Health Services client | 1.1.0 | heart rate on the watch |
| JUnit | 4.13.2 (Android modules), JUnit Platform via `kotlin-test` (`core`) | tests |

### Deviations from the design document

- **Target SDK is 36, compile SDK 37.** The apps compile against the newest platform but keep the behaviour of the previous release until the Android 17 behaviour changes have been checked on the owner's phone.
- **The watch interface uses plain Compose, not Wear Compose Material.** Its few screens are text, three round buttons and an arc; drawing them directly keeps the app small and exactly as approved on the prototype. The Wear Compose libraries stay pinned in the catalog but are not linked.
- **No `org.jetbrains.kotlin.android` plugin.** Android Gradle plugin 9 compiles Kotlin itself; only `core` applies a Kotlin plugin.

## Installing on the devices

Debug builds are signed with the local debug key of the machine that built them. Installing a build signed by another machine requires uninstalling first, which deletes the runs stored on the phone — sync or share them before.

**Phone**: enable developer options and USB debugging, plug the phone in, then from `android/`:

```bash
adb install -r phone/build/outputs/apk/debug/phone-debug.apk
```

**Watch**: on the watch, enable developer options (Settings → About watch → Software → tap the software version seven times), then Developer options → ADB debugging and Wireless debugging → Pair new device. With the watch on the same Wi-Fi as the computer:

```bash
adb pair <watch-ip>:<pairing-port>
```

```bash
adb connect <watch-ip>:<debug-port>
```

```bash
adb -s <watch-ip>:<debug-port> install -r wear/build/outputs/apk/debug/wear-debug.apk
```

## How a run is recorded

The recording logic lives in `core` (`SessionEngine`): it is driven only by events and their timestamps — commands, position fixes, step counts — so the same events always give the same run. The phone keeps the events of the run in progress; if the app is killed, replaying them restores the run exactly.

| Constant | Value | Meaning |
|---|---|---|
| `MAX_ACCURACY_M` | 25 m | a less accurate fix is ignored |
| `READY_ACCURACY_M` | 20 m | accuracy at which the ready screen shows the position as found |
| `MAX_SPEED_MPS` | 12 m/s | a fix implying more than this is a jump and adds no distance |
| `MAX_CONSECUTIVE_JUMPS` | 3 | after this many jumps in a row the new position is trusted, without adding distance |
| `AUTO_PAUSE_ENTER_MPS` | 0.6 m/s | below this the runner is standing: no distance is added |
| `AUTO_PAUSE_EXIT_MPS` | 1.2 m/s | above this the runner is moving again |
| `AUTO_PAUSE_ENTER_HOLD_MS` | 5 s | time standing before the run auto-pauses |
| `AUTO_PAUSE_EXIT_HOLD_MS` | 2 s | time moving before it resumes |
| `AUTO_PAUSE_WINDOW_MS` | 3 s | window over which that speed is measured |
| `PACE_WINDOW_MS` | 10 s | window of the displayed current pace |
| `CADENCE_WINDOW_MS` / `CADENCE_WARMUP_SLACK_MS` | 10 s / 2 s | cadence is the step count over the window, shown once steps span most of it |
| `ELE_HYSTERESIS_M` / `ELE_SMOOTHING` | 2 m / 0.2 | climb counted on the phone, same hysteresis as the server |
| `STALE_MS` | 15 s | a live pace or heart rate older than this is blanked |
| `HrJoin.MAX_GAP_MS` | 5 s | a heart-rate sample is attached to a point at most this far in time |

**Relation to the server's figures** ([metrics.md](metrics.md)). The server detects stops with `STOP_SPEED_MPS` (1.5 km/h) held for `STOP_MIN_S` (10 s), but only for files without timer events. The phone writes every pause — manual or automatic — as timer events, and the server then uses those and infers nothing else, so both agree on moving time whatever their thresholds. The phone's thresholds are higher because a standing phone's position wanders at up to about 0.5 m/s. An automatic pause is dated from the moment the runner stopped, not from its detection five seconds later. Distance is written in the file and preferred by the server; elevation gain is recomputed by the server and is the reference after sync.

**The file.** `FitEncoder` writes one FIT activity: `file_id`, timer `event`s, one `record` per second, `lap`s, a `session` (sport: running) and an `activity`. Cadence is stored the FIT way, per leg; the server doubles it back. `server/tests/fixtures/android-recorder*.fit` are produced by the encoder from a scripted run and imported by `server/tests/android_fit.rs`: a change on either side that breaks the contract fails a test.

### Deviation from the design document

- **The run in progress is stored as its event log**, not as sample rows written every five seconds. Samples, laps and timer events are derived from the engine when the run is saved. This is what makes the rebuilt run identical to the interrupted one.

## Trying the phone app without going for a run

In the emulator (or on the phone with developer options), a debug build can be driven from a terminal:

```bash
adb shell pm grant dev.wellbeing.app android.permission.ACCESS_FINE_LOCATION
```

```bash
adb emu geo fix 2.3522 48.8566 35
```

```bash
adb shell am broadcast -p dev.wellbeing.app -a dev.wellbeing.DEBUG_HR --ei bpm 150
```

`geo fix` takes longitude, then latitude, then altitude, and only works in the emulator; sending a sequence of fixes one second apart simulates movement. The `DEBUG_HR` broadcast stands in for the watch and exists only in debug builds.

## How the phone app is organised

- `record/RunController` — the one run that can exist at a time. The interface, the foreground service and the watch link all go through it. It owns the engine (main thread only) and writes the event log through a single background thread.
- `record/RecordingService` — the `location` foreground service: fused location at 1 Hz, step detector, barometer, wake lock and the notification. It is started from the ready screen, which is the only way Android allows without background-location permission.
- `data/` — Room database (`runs`, `events`, `hr_samples`, `samples`, `laps`, `timer_events`, `server_activities`) and the settings store.
- `ui/` — Compose screens. Colours come from `core`'s `Tokens`, which a test compares with the web app's `tokens.css`. Bricolage Grotesque and Manrope are bundled (`res/font`, SIL Open Font License) so nothing is fetched at run time.

Altitude is the fix's mean-sea-level altitude corrected by the barometer: the barometer gives the shape of the climb, and its offset from GPS is tracked slowly (`OFFSET_TRACKING`, 2 % per fix).

## Checklist on the phone

The development machine cannot run the emulator, so the behaviours below are checked on the Pixel 8 with a debug build. Each line names the spec scenario it covers.

1. **First opening** — the Courir tab explains why location is needed; refusing keeps the Start button away; accepting shows "Recherche de la position…" then "Position trouvée" outdoors. (`run-recording`: permission refused, good fix)
2. **Start, screen off** — start, lock the phone, walk or run a few hundred metres, wake it: the live screen shows over the lock screen with time and distance still counting, and the notification shows the same figures. (`run-recording`: screen off; `live-run-screens`: waking the phone, notification)
3. **Main screen** — average pace with one bar per kilometre, cadence, heart rate placeholder (no watch yet), total time and distance beside the trace. (`live-run-screens`: default layout, pace bars, mini-map, no heart rate)
4. **Auto-pause** — stop at a crossing: "Pause auto" within about ten seconds, resumes a few seconds after leaving. (`run-recording`: traffic light)
5. **Lap** — press Tour, swipe to the lap screen: lap 2 from zero, lap 1 time and pace below. (`live-run-screens`: after a lap mark)
6. **Lock** — press Verrouiller, tap and swipe everywhere: nothing changes; hold the pill to unlock. (`live-run-screens`: rain, unlock)
7. **Stop** — pause, tap the stop button briefly: nothing; hold it: the summary appears. (`run-recording`: accidental and deliberate stop)
8. **Save** — set a title, an effort and a note, save: the run is in Historique as "En attente" with its laps and trace. (`run-recording`: save; `phone-history`: run list, run page)
9. **Edit, share, delete** — on the run page, change the effort with the pencil; share the `.fit` to Files; delete with the confirmation. (`phone-history`: edit, share, delete)
10. **Kill mid-run** — start a run, swipe the app away from the recent apps, reopen: "Course interrompue" offers to resume or to end and keep. (`run-recording`: app killed, end an interrupted run)
11. **Settings** — replace a field, switch theme to Sombre: the next run and the whole app follow. (`live-run-screens`: replace a field; `mobile-theming`: forced theme)
12. **The file** — import the shared `.fit` in the web app: same distance, laps and pauses. (`run-recording`: server reads back a recorded run)

## Pairing with the home server and syncing

1. Start the server on the home network (see [architecture.md](architecture.md)) and note its address, for example `http://192.168.1.20:8080`. The phone must be on the same Wi-Fi.
2. In the app: **Réglages → Serveur à la maison**, type the address (`192.168.1.20:8080` is enough) and press **Connecter**. The app checks that the address answers as this server — status, version and database fields of `/api/v1/health` — before keeping it; a wrong address leaves the previous setting untouched.
3. Choose the **profile** your runs go to. Units and heart-rate zones come from that profile and are refreshed at every sync.

From then on a saved run is uploaded without any action:

- **Only on an unmetered network.** Being home is inferred from "unmetered and the server answers within 3 s"; the Wi-Fi name is never read. On mobile data nothing is sent, not even by the manual button.
- **When** — as soon as the phone is on such a network after a run is saved or edited, then every 15 minutes while something is still pending (so joining another Wi-Fi first does not postpone the upload for hours), and at each start of the app. The manual button in Historique does the same thing immediately.
- **What** — the run's `.fit` file through `POST /profiles/{id}/imports`, then its title, effort and note through `PATCH /activities/{id}`. An empty title leaves the server's generated title. Editing a synced run later sends only the notes.
- **Retries are safe.** The server refuses a run it already holds and names the existing activity; the phone then marks the run as synced. A file the server rejects is marked "Échec" with the reason and is not retried by itself.
- **Profile deleted on the server** — nothing is uploaded and the app asks for another profile.

**Plain HTTP.** The server has no TLS, so the app allows cleartext traffic. It only ever contacts the address typed in the settings. Away from home, reach the server through a VPN (WireGuard, Tailscale); never expose its port to the internet.

To try the client against a real server from a development machine, start a server with an empty data directory, create a profile, then run from `android/`:

```bash
WELLBEING_TEST_SERVER=http://127.0.0.1:8090 ./gradlew :phone:testDebugUnitTest --tests "*LiveServerTest*"
```

Checklist additions on the phone: connect to the server and pick a profile (step 2–3 above); save a run at home and see it turn "Synchronisée" and appear in the web app with its effort and note; save one on mobile data and see it stay "En attente" until the phone is back on the home Wi-Fi; activities imported through the web appear in Historique with the "Serveur" tag.

## The watch app

The watch never records by itself. It joins the run prepared on the phone, measures heart rate, shows four figures and sends commands.

**Using it**

1. On the phone, open **Courir** (the ready screen). This is what creates the session.
2. On the watch, open **Well-being**. The first time, accept the sensor permission. The watch shows "Prêt" with your heart rate and a start button; the phone's ready screen shows "Montre connectée".
3. Start from either device. During the run the watch shows time, pace (large), distance and heart rate, with the zone lit on the rim. Swipe left for Tour, Pause/Reprendre and Terminer (hold).
4. The app must be opened by hand for each run: Android does not let a watch app start measuring from the background.

A short vibration means the phone applied the command; a double vibration and "Non appliqué" mean it did not (phone out of reach).

**How it works**

- `WatchSession` finds the phone through the `wellbeing_phone` capability and exchanges the messages defined in `core`'s `WatchProtocol`: `hello` and `bye`, `state` (phone → watch), `hr` (batches of timestamped samples), `cmd` and `ack`.
- While the app is on screen it says hello every three seconds; the phone answers with the state. No state for five seconds shows "Téléphone injoignable". While the screen is dimmed the phone sends the state once a minute, and the dimmed screen shows the four figures in grey.
- `HrService` runs a Health Services exercise with heart rate as its only data type and GPS off, asking for five-second batching where the watch supports it. Starting it ends any exercise tracked by another app, such as Samsung Health — only one exercise can run at a time on the watch.
- Heart rate that cannot be delivered waits in memory (up to thirty minutes) and goes out when the link returns; each sample carries the time it was measured, so it lands at the right place in the run.
- Measuring stops when the run is stopped or discarded on the phone, or after ten minutes without the phone. Nothing about the run is kept on the watch.

**Checklist on the watch** (with the phone app installed and a run prepared)

1. Opening the watch app with nothing prepared on the phone shows "Prépare la course". (`watch-companion`: nothing prepared)
2. With the ready screen open on the phone, the watch shows "Prêt" and a heart rate; the phone shows "Montre connectée". (session prepared on the phone)
3. Start from the watch: the run starts on the phone and the watch vibrates. (start from the wrist)
4. Lap, pause and resume from the watch are applied on the phone within a second. (lap from the wrist, paused)
5. Lower the wrist for a few minutes, then look: figures are current within two seconds. After saving, the run has heart rate throughout. (wrist down, glance)
6. Leave the phone and walk away for two minutes: the watch shows "Téléphone injoignable"; back in range, the saved run still has heart rate for those two minutes. (two-minute dropout)
7. Stop on the phone: the watch shows "Course terminée" and its heart-rate notification disappears within about ten seconds. (stop on the phone)
8. Note the watch battery before and after a one-hour run: the drop should be twelve points or fewer. (light on resources)
