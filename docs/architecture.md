# Architecture

How the watch, the phone and the home server fit together, and what this repository contains today. The reasoning behind each choice is in the OpenSpec change [`running-foundations/design.md`](../openspec/changes/running-foundations/design.md).

## The whole system

```
Galaxy Watch 4 — Wear OS app (Kotlin)                 planned
  heart rate, start/pause/lap/stop, vibration cues
        │  Wearable Data Layer over Bluetooth
        ▼
Pixel 8 — native Android app (Kotlin, Compose)        planned
  records GPS, guides the session, stores runs locally,
  writes one .fit file per run
        │  plain HTTP upload on the home Wi-Fi,
        │  naming the target profile
        ▼
Raspberry Pi — this repository                        in progress
  one container: Rust server + SQLite + web app
```

- **The phone records, the watch assists.** The Watch 4 is old; using its GPS and CPU for recording would drain it. It sends heart rate and shows what the phone pushes to it.
- **Native Android, not a PWA.** Background GPS with the screen off, voice and vibration cues, and talking to the watch all require a native app.
- **One `.fit` file per run is the contract** between phone and server. FIT carries laps, pauses, heart rate and cadence. The phone uploads through the same import endpoint as a manual upload from the browser, so there is a single ingestion path. `.gpx` is accepted for history and produced for export.
- **No cloud, no account.** Sync happens only on the home network. The server has no authentication: anyone on the network can use it, which is why it must never be exposed to the internet (use a VPN for remote access).
- **Profiles instead of users.** A household has a few athlete profiles; each run belongs to one. A run done together can be copied to the other person's profile, without the recorder's heart rate by default.
- **Health Connect is not used.** It would route data through Samsung Health and restricts route access.

## The server (this repository)

```
prototype/   approved design prototype (static page)
server/      Rust crate: HTTP API, processing pipeline, storage
web/         SvelteKit single-page app
deploy/      Dockerfile and compose file            (task group 9)
docs/        this file, design direction, API and deployment guides
testdata/    GPX and FIT fixtures                   (task group 3)
```

- **One process, one port.** The Rust binary serves the REST API under `/api/v1` and the web app everywhere else. The web app is built to static files and embedded in the binary, so nothing but the binary runs on the Pi. Unknown non-API paths return the app shell (`200.html`) so client-side routes survive a reload; unknown API paths return a JSON 404.
- **One data directory.** `DATA_DIR` holds the SQLite database (`running.db`, WAL mode) and, later, the original uploaded files. Backing up that directory backs up everything.
- **Configuration by environment variable**, all optional: `PORT` (8080), `DATA_DIR` (`./data`), `MAX_UPLOAD_MB` (50), `MAP_STYLE_URL` (a public OpenStreetMap-based style; empty disables the basemap).

### Developing

```bash
# terminal 1 — API on :8080
cd server && cargo run

# terminal 2 — web app with hot reload on :5173, /api proxied to :8080
cd web && npm install && npm run dev
```

To run the way it ships, build the web app first, then the server: `cd web && npm run build`, then `cd server && cargo run`, and open http://localhost:8080.

Checks: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` in `server/`; `npm run lint`, `npm test`, `npm run build` in `web/`. CI runs the same commands.

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
- **Default map style**: OpenFreeMap "positron" is the placeholder default for `MAP_STYLE_URL`; the final choice is made when the map is built (task 8.2).
