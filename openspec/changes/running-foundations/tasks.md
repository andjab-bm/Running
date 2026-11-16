# Tasks

## 1. UI prototype (approval gate)

- [x] 1.1 Build `prototype/activity-detail.html` as one self-contained page (no external scripts or tiles; web fonts from Google Fonts only) with embedded fictional 10 km sample data (trace, elevation, HR, cadence, splits, zones); verify it opens from the file system with no build step and no console errors
- [x] 1.2 Implement the SVG map with pace/HR-coloured trace, legend and start/finish markers, and the pace-first chart (selectable measure, per-km averages, relief silhouette) with two-way hover synchronisation; verify by hovering the chart and the trace in the browser
- [x] 1.3 Implement the activity list, import and settings screens, profile switching, copy/move/delete dialogs, journal editing, splits table, HR zone chart and the Light/Dark/System switch in the pastel borderless direction; verify both themes render with no unstyled element and no horizontal page scroll at phone and 1440 px widths
- [x] 1.4 Publish the prototype as a private Claude artifact and give the owner the link; verify the published page renders and the theme switch and hover synchronisation work there
- [x] 1.5 Apply the owner's requested adjustments (republishing the artifact each time), and record the explicit approval and final design tokens in `docs/design-direction.md`; **do not start group 2 before this file records the approval**

## 2. Repository scaffolding

- [x] 2.1 Pin current versions of axum, sqlx, fitparser, gpx, zstd, SvelteKit static adapter, MapLibre GL JS and uPlot in `docs/architecture.md`, read current documentation (Context7) for the ones used by this group (axum, sqlx, SvelteKit), and note any deviation from `design.md`; verify each chosen library has a pinned version listed
- [x] 2.2 Create the `server/` Rust crate with axum, tokio and sqlx (SQLite) and a `/api/v1/health` route; verify `cargo run` answers the health request with status and version
- [x] 2.3 Create the `web/` SvelteKit app with the static adapter and import the approved tokens into `web/src/lib/tokens.css`; verify `npm run build` produces a static bundle
- [x] 2.4 Serve the built web bundle from the Rust server with SPA fallback; verify the app shell loads from the server's port and unknown client routes return the shell
- [x] 2.5 Add formatting, lint and test commands (`cargo fmt`, `clippy`, `cargo test`, web lint and unit test runner) with a CI workflow; verify the workflow passes on a clean checkout
- [x] 2.6 Write `docs/architecture.md` describing the watch → phone → server architecture from `design.md` Decision 1; verify it is linked from `README.md`

## 3. Parsing and metrics pipeline

- [x] 3.1 Assemble test data: synthetic runs and GPX documents built in `server/tests/pipeline.rs` (with and without HR, route without timestamps, unrecognised and corrupt content) and the owner's real FIT exports in the git-ignored `testdata/`; verify `testdata/README.md` lists each real file and its expected key figures
- [x] 3.2 Read current fitparser and gpx documentation (Context7), then implement format sniffing and the GPX and FIT parsers into a common sample model; verify unit tests parse every valid fixture and return typed errors for the corrupt and timestamp-less ones
- [x] 3.3 Implement normalisation (invalid fix removal, cumulative distance, cadence in steps/min) and the moving mask (recorded pauses, speed and gap thresholds); verify unit tests cover the recorded-pause, detected-stop and brief-slowdown scenarios of `activity-metrics`
- [x] 3.4 Implement elevation smoothing with gain/loss hysteresis and grade-adjusted pace; verify unit tests cover the noisy-flat, real-climb, uphill-split and flat-split scenarios
- [x] 3.5 Implement distance splits (metric and imperial) and laps with per-segment metrics; verify unit tests assert the 5.4 km case, split distances summing to total, pause exclusion and single-lap fallback
- [x] 3.6 Implement HR zone distribution and relative effort from a zone definition; verify unit tests assert percentages sum to 100 %, linearity in duration and the unavailable case
- [x] 3.7 Implement index-aligned stream downsampling to at most 2 000 points; verify a unit test on a synthetic 4-hour activity checks the bound and the preserved endpoints
- [x] 3.8 Document the algorithms and their constants in `docs/metrics.md`; verify every constant named in the code appears in the document

Task 3.9 (a real FIT recording with heart rate and several laps, and the test asserting its lap count and average heart rate) needs a run recorded with the phone and the watch: it was moved to [`android-recording/tasks-field.md`](../android-recording/tasks-field.md) on 2026-11-16 at the owner's request.

## 4. Storage and import

- [x] 4.1 Write SQLite migrations for profiles, activities (with `profile_id`, `copied_from`, `metrics_version`), splits, laps, tags and activity_tags; verify migrations apply to an empty database at startup and a migration test passes
- [x] 4.2 Read current zstd documentation (Context7), then implement stream blob encoding/decoding (column arrays, compressed) and reference-counted original-file storage keyed by SHA-256 under the data directory; verify a round-trip unit test, that a stored original is byte-identical, and that a file shared by two activities survives deletion of one
- [x] 4.3 Implement `POST /api/v1/profiles/{pid}/imports` accepting multiple files with per-file `imported`/`duplicate`/`failed` results, size limit and bounded concurrency; verify integration tests reproduce the mixed-batch, active-profile and unknown-profile scenarios of `activity-import`
- [x] 4.4 Implement per-profile duplicate detection by content hash and by start time/duration proximity; verify integration tests cover same-file, cross-format, same-file-in-two-profiles and re-import-after-deletion scenarios
- [x] 4.5 Implement GPX 1.1 export and original-file download endpoints; verify an integration test validates the exported file parses back within 0.5 % distance and that the original download matches the upload hash

## 5. Activity API

- [x] 5.1 Implement `GET /profiles/{pid}/activities` with cursor pagination, tag and date filters and preview polyline, and `GET /activities/{id}` with summary, splits and laps; verify integration tests for ordering, paging, filtering and 404
- [x] 5.2 Implement `GET /activities/{id}/streams` with `low` and `full` resolutions; verify an integration test checks series alignment and the low-resolution bound
- [x] 5.3 Implement `PATCH /activities/{id}` for title, description, RPE and tags (predefined tags seeded, custom tags created on use) with validation, and default title generation at import; verify integration tests for save, invalid RPE, cleared RPE and custom tag suggestion
- [x] 5.4 Implement `DELETE /activities/{id}` removing rows, blobs and the original file when no other activity references it; verify an integration test confirms the file is gone from the data directory
- [x] 5.5 Describe the API as an OpenAPI 3.1 document in `server/src/api/openapi.rs` and commit it to `docs/api/openapi.json` (regenerated at the end of group 6); verify a test fails when the committed file is out of date or a listed route is not served

## 6. Profiles and trusted-network access

- [x] 6.1 Implement `GET|POST /profiles` and `GET|PUT|DELETE /profiles/{pid}` (name, colour, max/resting HR, default and custom zones, units, time zone) with validation, unique names, the default-HR fallback flag, and deletion refusing the last profile; verify integration tests cover every scenario of `athlete-profile`
- [x] 6.2 Recompute zones, effort and splits for a profile's activities when its settings change; verify an integration test shows updated zone times after changing max HR
- [x] 6.3 Implement `POST /activities/{id}/copy` (target profile, keep-HR option, existing-copy detection) and `POST /activities/{id}/move` with metrics recomputed for the target profile; verify integration tests cover every copy and move scenario of `activity-library`
- [x] 6.4 Reject cross-site state-changing requests (Origin / Sec-Fetch-Site check, required custom header, no permissive CORS); verify integration tests refuse a forged cross-origin delete and accept a same-origin write and a credential-less non-browser upload
- [x] 6.5 Regenerate the OpenAPI document with the profile, copy and move endpoints; verify the CI freshness check passes

## 7. Web app shell, theming and library

Browser checks in groups 7 and 8 were first done by hand against a server loaded with real exports; since task 8.8 they are also covered by the Playwright suite in `web/e2e/`.

- [x] 7.1 Build the app shell (navigation, first-run profile creation, profile switcher with remembered active profile, API client with SI-to-display unit and time zone formatting helpers); verify unit tests for the pace, distance, elevation and duration formatters in both unit systems
- [x] 7.2 Implement Light/Dark/System theming with tokens, persistence and the pre-paint inline script; verify in the browser that it confirms no theme flash on reload and live switching on system change
- [x] 7.3 Build the activity list with paging, tag/date filters, trace previews and empty state; verify in the browser against a seeded server: browsing, filtering and the empty state
- [x] 7.4 Build the import screen (multi-file picker and drag-and-drop, progress, per-file results linking to activities or duplicates); verify in the browser that it uploads a mixed batch and sees the three outcome types
- [x] 7.5 Build the settings screen for the active profile (name, colour, HR, zones, units, time zone) and profile management (create, delete with activity-count confirmation); verify in the browser that it changes max HR and sees zone ranges update, creates a second profile, switches to it and sees an empty list

## 8. Activity detail page

- [x] 8.1 Build the header and summary metrics with placeholders for unavailable values, loading skeletons and the not-found state; verify in the browser that it on fixtures with and without heart rate
- [x] 8.2 Read current MapLibre GL JS documentation (Context7), then build the MapLibre map component: fitted trace, start/finish markers, pace/HR gradient colouring with legend, themed basemap from `MAP_STYLE_URL`, plain-background fallback; verify in the browser with tiles enabled, disabled and blocked
- [x] 8.3 Read current uPlot documentation (Context7), then build the uPlot chart showing pace by default with selectable measure (pace, HR, cadence, elevation), per-km averages, relief silhouette, and two-way hover/touch synchronisation with the map through a shared cursor store; verify in the browser that it hovers the chart and asserts the map marker position changes, and switches measure
- [x] 8.4 Build the splits/laps tables with pace bars, table switch and row-to-map/profile highlighting; verify in the browser that it on a multi-lap fixture switches tables and highlights a row
- [x] 8.5 Build the HR zone chart with relative effort and the default-zone notice; verify in the browser that it checks five labelled bars and the notice on an unconfigured profile
- [x] 8.6 Build in-place journal editing (title, description, RPE, tags) and the export/original/copy-to-profile/move-to-profile/delete actions with confirmation; verify in the browser that it edits, saves, exports, copies an activity to a second profile (checking it appears there without heart rate) and deletes the original
- [x] 8.7 Compare the built page with the approved prototype in both themes at 360 px and 1440 px and fix deviations; verify side-by-side screenshots are attached to `docs/design-direction.md` and contrast checks pass at 4.5:1
- [x] 8.8 Add an automated browser test suite (Playwright) covering first start, import, list filters, the activity page (hover synchronisation, measure switch, journal, copy/move/delete) and settings, and run it in CI; verify the suite passes against a seeded server

## 9. Packaging and deployment

Verified on 2026-11-16 with the image the CI built from `main` (`ghcr.io/andjab-bm/running:latest`, run 43859950046): `docker buildx imagetools inspect` lists `linux/amd64` and `linux/arm64`; started on an AMD64 machine through `deploy/compose.yaml` with an empty `.env`, it answers the health route, serves the app shell, runs as user 65532, is reported healthy, keeps a profile across `down` / `up`, and stops in under 3 s. The backup and restore commands of `docs/deployment.md` (9.4) have not been run: they are left for the Raspberry Pi install.

- [x] 9.1 Write the multi-stage `deploy/Dockerfile` (web build, Rust build with embedded assets, minimal non-root runtime with `/data` volume and healthcheck); verify the image builds and runs locally on AMD64
- [x] 9.2 Add multi-arch build (`linux/arm64`, `linux/amd64`) with cross-compilation for ARM64; verify `docker buildx imagetools inspect` lists both platforms
- [x] 9.3 Add `deploy/compose.yaml` and `.env.example` covering port, data directory, upload size and map style with working defaults; verify `docker compose up` starts with an empty `.env`
- [ ] 9.4 Write `docs/deployment.md` (Raspberry Pi install, first-run profile creation, a prominent warning that there is no authentication and the port must never be exposed to the internet, backup/restore of the data directory, upgrades, self-hosted PMTiles tiles, remote access via VPN or authenticating reverse proxy); verify the documented commands run as written on a clean machine

## 10. Integration checks

- [ ] 10.1 Deploy the ARM64 image on the Raspberry Pi and batch-import the owner's real history; verify import outcomes are reported for every file and a one-hour activity imports in under 5 seconds
- [ ] 10.2 Measure idle memory on the Pi after ten minutes with the imported history; verify it is below 128 MB and record the figure in `docs/deployment.md`
- [ ] 10.3 Recreate the container on the same volume and confirm profiles, activities, journal and originals persist; verify by reopening a previously annotated activity
- [ ] 10.4 Inspect server and browser network traffic during import and viewing; verify the server makes no outbound request and the browser contacts only the server and the configured tile host
- [ ] 10.5 Run `openspec validate running-foundations --strict` and walk every spec scenario against the deployed instance; verify no scenario is left unchecked

## Workflow follow-up

- Archive this change once the owner has reviewed the deployed result.
- Propose the next change (`android-recording`) against the committed OpenAPI document.
