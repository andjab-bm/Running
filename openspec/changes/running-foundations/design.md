# Design

## Context

Greenfield repository: only `Specification.md`, `README.md` and `LICENSE` exist. See `proposal.md` for motivation and scope.

Constraints that shape the approach:

- **Hardware**: Pixel 8 (Android), Galaxy Watch 4 (Wear OS, ageing, battery- and CPU-constrained), Raspberry Pi home server (ARM64).
- **Privacy**: no activity data to third-party clouds; sync happens on the home LAN.
- **UX**: visual quality is a primary requirement; the art direction must be validated on a prototype before application code is written.
- **Owner preference**: Rust backend considered, open to alternatives; lowest possible resource usage.

`Specification.md` also asks for a recommendation on the overall watch ↔ phone ↔ server architecture. That recommendation is recorded here (Decision 1) even though only the server and web parts are built in this change, because the API and file-format choices made now are the contract the later Android and Wear OS changes depend on.

Library documentation was not looked up through Context7 while drafting (the server was unavailable at the time), so library choices below are named from general knowledge and each carries a verification step in `tasks.md`.

## Goals / Non-Goals

**Goals:**

- Fix the end-to-end architecture so follow-up changes do not reopen it.
- Validate the art direction with a throwaway-cheap but realistic prototype.
- Ship a server that is useful on its own: import history, analyse a run.
- Keep the processing pipeline deterministic and re-runnable so metrics can be recomputed when algorithms or profile settings change.

**Non-Goals:**

- Building any Android or Wear OS code.
- Authentication, accounts, per-profile privacy, or public exposure on the internet (trusted LAN/VPN use is assumed; no TLS termination in the container).
- Segments, records, predictions, training load, statistics dashboards, workout planning.
- Health Connect integration.

## Decisions

### 1. Overall architecture: native recorder → FIT file → LAN upload → server as system of record

```
Galaxy Watch 4 (Wear OS app, Kotlin)          [later change]
  HR + controls, thin client
        │  Wearable Data Layer (Bluetooth)
        ▼
Pixel 8 (native Android app, Kotlin/Compose)   [later change]
  GPS recording, live coaching, local DB,
  writes one .fit per activity
        │  HTTP on home Wi-Fi, target profile id,
        │  background job when on unmetered network
        ▼
Raspberry Pi (this change)
  Rust server + SQLite + web SPA, one container
```

- **Phone app is native Android, not a PWA.** Reliable background GPS with the screen off, a foreground service, vibration/voice cues and the Wear OS Data Layer are not available to a PWA. *Alternative*: PWA — rejected for those reasons. *Alternative*: Flutter/React Native — rejected because the Wear OS companion needs Kotlin anyway and sharing code between phone and watch is easier in one language.
- **The phone records GPS; the watch is a thin companion.** The Watch 4's GPS drains its battery quickly and its SoC is weak. The watch streams heart rate and shows metrics pushed by the phone. Standalone watch recording is a possible later extension.
- **FIT is the interchange format between phone and server.** It natively carries laps, timer pause events, HR and cadence, and is compact. The phone uploads through the same import endpoint this change builds, so manual upload and automatic sync share one code path. GPX is supported for import of history and export of routes/activities.
- **Sync is a plain upload over the LAN, naming the target profile**, triggered by the phone when on an unmetered network and the server answers its health check. No broker, no cloud relay. Duplicate detection (spec `activity-import`) makes retries safe.
- **Health Connect is not the primary path.** It would bring Samsung Health into the loop and its route data is restricted. It stays a possible optional fallback, not designed here.

### 2. Backend in Rust (axum + SQLite)

Rust is appropriate here: a single static binary with ~10–30 MB RSS, no runtime to install on the Pi, and fast binary/XML parsing for FIT and GPX. The owner's inclination is confirmed.

- HTTP: `axum` on `tokio`. Database: SQLite through `sqlx` with embedded migrations, WAL mode. Parsing: `fitparser` for FIT, `gpx` (or `quick-xml` directly if extensions are poorly supported) for GPX.
- *Alternative*: Go — comparable footprint and simpler, but the owner prefers Rust and FIT tooling is adequate in both. *Alternative*: Node/Python — two to five times the memory on a Pi and slower batch import of years of history. *Alternative*: PostgreSQL (+PostGIS) — unnecessary for one user; an extra container and ~100 MB RAM. Spatial queries needed later for segments can be done in-process with an R-tree.

### 3. Storage model: summary rows + compressed stream blob + original file

- `profiles` holds each athlete's name, colour and settings. `activities` holds summary metrics and journal fields and carries a `profile_id`; `splits`, `laps`, `tags` (per profile) and `activity_tags` are ordinary tables.
- **Copy to another profile** creates a new `activities` row for the target profile with its own journal and a `copied_from` reference (used only to detect an existing copy). The stream blob is re-encoded for the copy — without the HR channel unless the user keeps it — and metrics are recomputed with the target profile's zones. The original file is shared by hash and reference-counted, so it is removed from disk only when the last activity using it is deleted. *Alternative*: a many-to-many link between one activity and several profiles — rejected because journal, RPE, HR presence and zone-based metrics all differ per person, so a shared row would need per-profile overrides for nearly every field.
- **Move** only rewrites `profile_id` and triggers recomputation of zone-based metrics.
- Per-sample streams (lat, lon, distance, time, elevation, speed, HR, cadence, moving flag) are stored as **one column-oriented compressed blob per activity** (parallel arrays, zstd), plus a pre-computed reduced-resolution blob for charts and a simplified polyline for list previews.
- The original upload is kept on disk under `data/originals/<sha256>.<ext>`; the SHA-256 doubles as the exact-duplicate key.
- *Alternative*: one row per sample — ~3 600 rows per hour of running, slower reads and a much larger database for no query benefit at this stage.
- Each activity records a `metrics_version`. A change in algorithms or profile triggers recomputation from the stored streams (or from the original file if the stream schema itself changed).

### 4. Processing pipeline

One pure function chain, `parse → normalise → derive → summarise`, with no I/O, so it is unit-testable against fixture files:

1. **Parse** GPX/FIT into a common sample list; content-sniff the format (FIT header `.FIT` magic vs XML).
2. **Normalise**: drop invalid fixes, compute cumulative distance (haversine; prefer the device's distance field when present), convert cadence to steps/min.
3. **Moving mask**: FIT timer events when present, otherwise the speed/gap thresholds from spec `activity-metrics`.
4. **Elevation**: median + moving-average smoothing over distance, then gain/loss with a hysteresis threshold (~2 m) to suppress noise.
5. **Grade-adjusted pace**: Minetti energy-cost polynomial applied per smoothed segment, with grade clamped to ±30 %.
6. **Splits / laps**: interpolate at exact unit boundaries so split distances are exact.
7. **HR zones / relative effort**: time in zone over moving samples; effort = Σ(minutes in zone × zone number) (Edwards TRIMP). It is linear in duration, which matches the spec scenarios, and is the natural input for the later training-load change.
8. **Downsample** to ≤ 2 000 points with largest-triangle-three-buckets on the elevation/pace series, keeping all series index-aligned.

Import runs on a blocking worker pool with a small concurrency limit (2) so a batch import cannot exhaust Pi memory.

### 5. REST API `/api/v1`

JSON over HTTP; SI units and UTC timestamps on the wire, conversion to display units in the client. Main resources: `GET|POST /profiles`, `GET|PUT|DELETE /profiles/{pid}`, `POST /profiles/{pid}/imports` (multipart, multi-file, per-file results), `GET /profiles/{pid}/activities` (cursor pagination, tag/date filters), `GET|PATCH|DELETE /activities/{id}`, `POST /activities/{id}/copy` and `/move` (target profile, keep-HR option), `GET /activities/{id}/streams?resolution=low|full`, `GET /activities/{id}/export.gpx`, `GET /activities/{id}/original`, `GET /health`. An OpenAPI document is generated from the handlers and committed, as the reference for the future Android client.

**No authentication** (owner's decision): there are no users, sessions or tokens; the profile is an explicit path parameter and the active profile is a client-side choice kept in `localStorage`. Because any page open in a household browser could otherwise issue requests to the LAN service, the server rejects state-changing requests whose `Origin`/`Sec-Fetch-Site` indicates another site, sends no permissive CORS headers, and accepts writes only as JSON or multipart with a custom header (which forces a CORS preflight that is never granted). If authentication is wanted later, it can be added in front (reverse proxy) or as an optional shared password without changing the data model.

### 6. Web front end: SvelteKit static SPA, MapLibre GL JS, uPlot

- **SvelteKit with the static adapter**, built at image build time and embedded/served by the Rust binary — no Node process on the Pi. Small bundles and fine-grained reactivity suit the hover-synchronised map/chart. *Alternative*: React — larger runtime, no advantage here. *Alternative*: server-rendered HTML (htmx) — poor fit for the heavily interactive map/chart page.
- **MapLibre GL JS** for the map: vector rendering, smooth zoom, line gradients for the pace/HR-coloured trace, and style switching for dark/light. *Alternative*: Leaflet — lighter, but gradient traces and theme-matched basemaps are much harder to make look good, which conflicts with the UX priority.
- **uPlot** for the pace-first chart: tiny, canvas-based, fast cursor callbacks for map synchronisation. Zone bars and split bars are plain CSS/SVG.
- **Map tiles** are configurable (`MAP_STYLE_URL`): default to a public OpenStreetMap-based vector style with light and dark variants; a self-hosted PMTiles regional extract served by the same server is the documented privacy-maximal option; an empty value disables the basemap. Tile requests reveal viewed areas to the tile host, which is why this is configurable (spec `self-hosted-deployment`).
- **Theming**: design tokens as CSS custom properties on `:root[data-theme]`; an inline script in `index.html` sets the attribute from `localStorage`/`prefers-color-scheme` before first paint. Chart and map colours read the same tokens.

### 7. Prototype first, as a hard gate

The prototype is published as a **Claude artifact** (owner's choice) so it can be reviewed and iterated directly in the conversation; its source is kept in the repository as `prototype/activity-detail.html`. It is a single self-contained page with realistic embedded, fictional sample data (one real-looking 10 km run), built on the intended tokens. Artifact pages cannot rely on loading external scripts or map tiles, so the prototype draws the map as a stylised inline SVG basemap with the real trace geometry; the pannable MapLibre map arrives with the real page (task group 8). It covers the four screens of this change — activity list (monthly totals, tag and period filters, cards with trace thumbnails), activity detail (coloured trace with pace/HR switch, pace-first chart with selectable measure and hover synchronisation, zones, journal editing, splits/laps, copy/move/delete dialogs), import (drop zone, per-file outcomes) and settings (profile, heart-rate zones, appearance, household profiles) — plus profile switching and the dark/light switch, on phone and desktop layouts. Art direction set by the owner: pastel palette, borderless tonal surfaces, icons with tooltips, visible hover states. It is reviewed by the owner; requested adjustments are applied until explicit approval. The approved tokens (colours, type scale, spacing, radii) are then extracted to `web/src/lib/tokens.css` and become the source of truth. No task in groups 2+ starts before approval.

*Alternative*: static images or a Figma-like mock — rejected because hover synchronisation and theme switching are central to the judgement and need a live page.

### 8. Repository layout and packaging

```
prototype/   static mock-up (gate)
server/      Rust crate (api, pipeline, storage)
web/         SvelteKit app
deploy/      Dockerfile, compose.yaml, .env.example
docs/        architecture.md, api (OpenAPI), deployment.md
testdata/    GPX/FIT fixtures
```

Multi-stage Dockerfile: Node stage builds `web/`, Rust stage builds the server (static assets embedded), final stage is a minimal distroless/scratch-style image running as non-root with `/data` as the volume. Multi-arch build (`linux/arm64`, `linux/amd64`) via `docker buildx`; ARM64 compiled with cross-compilation rather than QEMU emulation to keep build times reasonable.

## Risks / Trade-offs

- **FIT parsing gaps (developer fields, vendor quirks from Samsung/Garmin/Strava exports)** → build the fixture set from the owner's real exports early (task group 3); unknown messages are ignored, never fatal.
- **GAP and effort scores will not match Strava/Garmin numbers exactly** → documented as estimates; algorithms are versioned so they can be refined and recomputed.
- **Elevation from phone GPS is noisy** → smoothing + hysteresis now; DEM-based correction is a possible later improvement, deliberately not included (would need external or large local data).
- **Auto-pause thresholds may misjudge very slow walking breaks** → thresholds are constants in one place and covered by fixture tests; can become profile settings later.
- **Public tile provider leaks viewed locations and may rate-limit** → configurable source, self-hosted PMTiles documented, basemap can be disabled.
- **No authentication: anyone on the network (guests, compromised IoT devices) can read, alter or delete all data, including location history** → accepted by the owner for a home LAN; cross-site request protection covers the browser vector; docs state it must never be port-forwarded and recommend a VPN (e.g. WireGuard/Tailscale) or an authenticating reverse proxy for remote access, and regular backups of the data directory.
- **Prototype map is an SVG stand-in, not MapLibre** → art direction (colours, type, layout, trace colouring, hover sync) is still fully judged; basemap styling is re-checked against the approved look in task 8.7.
- **Copies duplicate stream data** → a few hundred kB per shared run; negligible, and it keeps copies independent.
- **MapLibre is heavy (~200 KB gzipped) and needs WebGL** → loaded only on pages with a map; trace falls back to a plain background when tiles fail.
- **Prototype approval may take several iterations** → the prototype is one file with no build step, so iteration is cheap; it is the only blocking dependency.

## Migration Plan

Nothing to migrate: first deployment. Roll-out is `docker compose up -d` with a fresh data volume, then first-run profile creation and a batch import of existing history. Roll-back is stopping the container; the data volume is untouched. Schema migrations are forward-only and run at startup; the deployment guide instructs backing up the data directory before upgrading.

## Open Questions

- Which public vector tile style to use as the default (decided when building the prototype; does not affect specs or tasks since the source is configurable).
- Whether to publish the image to a registry or build locally on the Pi (packaging detail; the compose file supports both).
