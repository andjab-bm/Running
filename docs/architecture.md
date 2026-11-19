# Architecture

How the watch, the phone and the home server fit together, and what this repository contains today. The reasoning behind each choice is in the OpenSpec change [`running-foundations/design.md`](../openspec/changes/running-foundations/design.md).

## The whole system

```
Galaxy Watch 4 — Wear OS app (Kotlin)                 built (android/wear)
  heart rate, start/pause/lap/stop, start from the wrist,
  coaching page and vibrations, tile
        │  Wearable Data Layer over Bluetooth
        ▼
Pixel 8 — native Android app (Kotlin, Compose)        built (android/phone)
  records GPS, stores runs locally, writes one .fit per run;
  guided sessions, routes and map, pace target,
  voice, sound and vibration cues
        │  plain HTTP upload on the home Wi-Fi,
        │  naming the target profile
        ▼
Raspberry Pi — server and web app                     built (server/, web/)
  one container: Rust server + SQLite + web app
```

- **The phone records, the watch assists.** The Watch 4 is old; using its GPS and CPU for recording would drain it. It sends heart rate and shows what the phone pushes to it.
- **Native Android, not a PWA.** Background GPS with the screen off, voice and vibration cues, and talking to the watch all require a native app.
- **One `.fit` file per run is the contract** between phone and server. FIT carries laps, pauses, heart rate and cadence. The phone uploads through the same import endpoint as a manual upload from the browser, so there is a single ingestion path. `.gpx` is accepted for history and produced for export.
- **No cloud, no account.** Sync happens only on the home network. The server has no authentication: anyone on the network can use it, which is why it must never be exposed to the internet (use a VPN for remote access).
- **Profiles instead of users.** A household has a few athlete profiles; each run belongs to one. A run done together can be copied to the other person's profile, without the recorder's heart rate by default.
- **Health Connect is not used.** It would route data through Samsung Health and restricts route access.

## The repository

```
prototype/   approved design prototypes (static pages)
server/      Rust crate: HTTP API, processing pipeline, storage
web/         SvelteKit single-page app
android/     phone and watch apps (one Gradle project)
  core/      pure Kotlin: session engine, FIT encoder, watch protocol, design tokens
  phone/     Android app: recording service, local storage, sync, interface
  wear/      Wear OS app: heart rate, controls, metrics
deploy/      Dockerfile, compose file, .env.example
docs/        this file, design direction, API, Android and deployment guides
testdata/    real FIT exports (private, not committed)
```

The phone and watch apps are described in [android.md](android.md); installing the server at home is described in [deployment.md](deployment.md).

## Coaching on the phone and the watch

The reasoning is in [`insights-and-coaching/design.md`](../openspec/changes/insights-and-coaching/design.md); the details, and the checks still to make on the devices, in [android.md](android.md).

- **Prepared at home, used anywhere.** Sessions and routes are written on the web and marked "on the phone"; the phone fetches them when it syncs at home and keeps them, so a run needs no network. The session, route and pace target chosen on the ready screen are written first in the run's log.
- **Coaching is computed in `android/core`**, beside the session engine, from the same events: the steps of a session (each step a lap, so the file and the server follow it), route following with off-route and arrival, the pacer (even, or weighted by the grade with the server's formula), and the cues, ordered by importance and dropped when stale. A run killed mid-session is rebuilt from its log in the same step. All of it is tested on the JVM by replaying scripted runs.
- **The phone renders the cues** inside its recording service, so they come with the screen off: a vibration, a short tone, and a French sentence from the phone's own offline voice, lowering music meanwhile and giving only vibration during a call. A per-kilometre announcement is optional.
- **The map** (MapLibre Native) exists only while it is the page shown with the screen on. Its basemap comes only from the style configured on the server, behind a setting on the phone; without it, the route and trace are drawn on a plain background with no request. The mini-map of the main screen is a still image of the same basemap, taken again only when the trace grows out of it.
- **The watch** shows a coaching page (the step and its target, or the gap to the pace target) and vibrates at step changes and alerts through a message the phone sends; it keeps measuring heart rate up to five minutes after a stop so a resumed run keeps it. It can ask the phone to start a free run, when the runner enabled that on the phone and granted what Android requires, and each app can open the other. Its tile shows the run it has joined, or offers to start one. Version 2 of the watch protocol adds all this while a watch or phone still on version 1 keeps working.
- **Privacy.** The server still makes no outbound request. The only new connection is the phone's map tiles, from the provider configured on the server, while the map or mini-map is shown with the setting on; the provider sees the area displayed, not the run.

## The server

- **One process, one port.** The Rust binary serves the REST API under `/api/v1` and the web app everywhere else. The web app is built to static files and embedded in the binary, so nothing but the binary runs on the Pi. Unknown non-API paths return the app shell (`200.html`) so client-side routes survive a reload; unknown API paths return a JSON 404.
- **One data directory.** `DATA_DIR` holds the SQLite database (`running.db`, WAL mode) and the original uploaded files (`originals/`). Backing up that directory backs up everything.
- **Configuration by environment variable**, all optional: `PORT` (8080), `DATA_DIR` (`./data`), `MAX_UPLOAD_MB` (50), `MAP_STYLE_URL` (a public OpenStreetMap-based style; empty disables the basemap).
- **One container.** `deploy/Dockerfile` builds the web app, compiles the server with the web app embedded (cross-compiled for ARM64 or AMD64) and ships the binary alone in a minimal image that runs as an unprivileged user with `/data` as its volume. `running-server healthcheck` is the container's health probe, and the server shuts down cleanly on the termination signal.

### What the server computes and keeps

- **At import**, in the request: the metrics of the run itself ([metrics.md](metrics.md) §1–8).
- **After import**, in the background: best efforts, training load and recurring segments (`server/src/derive/`, [metrics.md](metrics.md) §9–13). One worker handles one activity at a time, so a batch import cannot exhaust the Pi; an activity is usable as soon as it is imported, and `GET /profiles/{id}/derive-status` says how much is left. Activities that predate a change in these computations are caught up at start-up.
- **On request**, never stored: statistics, records, predictions and the load series. They are aggregated from the activity rows, so they always match the current activities.
- **Household and profile.** The activity list (`GET /activities`) covers every profile, a run copied between two members appearing once. Statistics, records, load, segments, sessions and routes belong to one profile.

### Sessions, routes and their delivery to the phone

- A **session** is a JSON document of steps and repeated blocks (durations in seconds, distances in metres, paces in seconds per kilometre). The server validates it (`pipeline/workout.rs`); the phone reads the same document, and both are tested against the fixtures of `server/tests/fixtures/workouts/`.
- A **route** is a path with elevations, built from the `<rte>` or `<trk>` of a GPX file — timestamps are not needed — or from a past run. Its length and climb use the same code as an activity's. It is simplified to at most 5 000 points and exported as GPX.
- **Delivery is a pull.** The server cannot reach a sleeping phone. Sessions and routes marked "on the phone" form the profile's plan (`GET /profiles/{id}/plan`); the phone fetches it when it syncs at home, fetches the points of routes whose version is new, then states what it holds (`PUT /profiles/{id}/plan/receipt`). That statement is what lets the web show "on the phone since…", or "waiting" when an item was edited after delivery. The plan also carries the map style, so the phone's map uses the tile host configured here and no other.
- **An activity keeps what it was run with**: the session as it was that day (so later edits or deletion do not rewrite it), the name of the route, and the pace target with its final gap. In a guided run a change of step is a lap, so the server labels laps with steps by position.

## The web app

- **Sections.** Activités (the household list, import), Progression (statistics, records, load), Segments (the profile's recurring portions and every passage), Planifier (sessions and routes, with their state on the phone) and Réglages. Everything but the household list belongs to the active profile; an activity, segment, session or route page uses the units and time zone of the profile that owns it.
- **Session editor.** The fields hold what the runner types; `src/lib/workout.ts` reads them into the shared session document on every change, so the outline and totals follow, and checks the document with the server's limits before saving. Paces are typed in the profile's unit and stored per kilometre. Totals are exact, a minimum (a step ends on lap) or unknown (a distance without a pace target), never invented. Starter sessions are defined in the web app and added to the library only on request.
- **Delivery to the phone** is shown from what the server knows: not marked, waiting (marked, and no phone holds this version) or on the phone since the time of the phone's statement, with the time of the profile's last phone sync.
- **The activity page** adds, when they exist: the record marks with the stretch highlighted on the map and the chart, the session as run with each step against its target and the steps not done, the passages over segments with their rank, the route followed and the pacer result, the switch that leaves the run out of records, and the creation of a route from the run or a portion of it.

Browser tests: `e2e/progress.spec.ts` covers the Progression section and `e2e/training.spec.ts` segments, the session editor, routes, delivery states (playing the phone's calls through the API) and the activity-page additions, each with contrast and sideways-scroll checks in both themes at 360 px and 1440 px.

### Developing

```bash
# terminal 1 — API on :8080
cd server && cargo run

# terminal 2 — web app with hot reload on :5173, /api proxied to :8080
cd web && npm install && npm run dev
```

To run the way it ships, build the web app first, then the server: `cd web && npm run build`, then `cd server && cargo run`, and open http://localhost:8080.

Checks: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` in `server/`; `npm run lint`, `npm test`, `npm run build` in `web/`. CI runs the same commands.

Browser tests (Playwright) drive the built web app against the real server on an empty data directory: `npm run build`, then `npm run test:e2e` in `web/` (the first time, `npx playwright install chromium`). `e2e/app.spec.ts` walks through first start, import, list filters, the activity page (chart and map linked by hover, measure switch, journal, copy, move, delete) and settings; `e2e/design.spec.ts` checks text contrast (4.5:1) on every screen in both themes at 360 px and 1440 px, and rewrites the comparison screenshots of `docs/screenshots/` when run with `UPDATE_SCREENSHOTS=1 E2E_BASEMAP=1`. CI runs them in the `e2e` job, and the `image` job builds the container image for both architectures.

## Libraries and pinned versions

Versions were taken from crates.io and npm on 2026-11-15. Current documentation was read through Context7 for the libraries already in use; the others are pinned now and their documentation is read when they are first used.

| Library | Version | Role | Documentation checked |
|---|---|---|---|
| axum | 0.8.9 | HTTP routing | yes — nested router, state, fallback |
| tokio | 1.54.1 | async runtime | — |
| sqlx | 0.9.0 | SQLite access, embedded migrations | yes — connect options, WAL, `migrate!` |
| rust-embed | 8.14.0 | embeds the web build in the binary | — |
| fitparser | 0.12.0 | FIT parsing | to read in task group 3 |
| gpx | 0.10.0 | GPX reading and writing | to read in task group 3 |
| zstd | 0.14.4 | compression of stored sample streams | to read in task group 4 |
| @sveltejs/kit | 3.x (3.2.0 at install) | web framework | yes — single-page app mode |
| @sveltejs/adapter-static | 4.0.1 | static build with `200.html` fallback | yes |
| svelte | 5.57.x | UI components (runes mode) | — |
| vite | 8.3.x | build tool | — |
| vitest | 5.1.x | web unit tests | — |
| maplibre-gl | 6.18.0 | interactive map | to read in task group 8 |
| uplot | 1.6.32 | pace chart | to read in task group 8 |

### Deviations from the design document

- **SvelteKit 3 configures the adapter in `vite.config.ts`**; there is no `svelte.config.js` any more.
- **The fallback page is `200.html`, not `index.html`**, as the SvelteKit documentation recommends for single-page apps. The server maps unknown paths to it.
- **Fonts are self-hosted** through the `@fontsource-variable` packages, so the browser makes no request to Google (the prototype did).
- **The OpenAPI document is written by hand next to the router** (`server/src/api/openapi.rs`) instead of being derived from handler annotations. `cargo test` fails when `docs/api/openapi.json` differs from it or when a listed route is not served; refresh the file with `UPDATE_OPENAPI=1 cargo test --test openapi`. A route added to the router but not to the list would go unnoticed.
- **Splits and laps share one `segments` table**, told apart by a `kind` column.
- **Stored streams are JSON compressed with zstd**, about 15 kB per kilometre at one sample per second.
- **Reduced-resolution streams are evenly spaced samples**, not largest-triangle-three-buckets as first designed: that method kept the extremes of a noisy pace signal and made the curve look jagged.
- **Default map style**: OpenFreeMap "positron" is the placeholder default for `MAP_STYLE_URL`; the final choice is made when the map is built (task 8.2).
