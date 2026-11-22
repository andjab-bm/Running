# Running

A private running tracker for a household, hosted at home. The phone records the run, the watch measures heart rate and coaches from the wrist, and a small server on a Raspberry Pi keeps every run and analyses it in a web app. No cloud, no account, no subscription: the data never leaves the house.

The interface is in French.

## What it does

**On the phone** (Android, built for the Pixel 8)

- Records runs with GPS, screen off or locked: time, distance, pace, cadence, laps, automatic pauses at crossings, and heart rate from the watch.
- Live screens with large figures, per-kilometre pace bars, a current-lap page, and a map with the route ahead, the trace and the position.
- **Guided sessions** prepared on the web: intervals, repeats, pace or heart-rate zone targets, a step page and a cue at each change of step.
- **Routes** to follow, with the distance left, off-route and arrival alerts, and a **virtual pacer** (a target pace or time, evened out over the hills).
- Cues by **voice** (French, spoken by the phone's own offline voice), **sound** and **vibration**, which lower the music for a moment; an optional announcement at each kilometre.
- Keeps every run on the phone and sends it to the home server when it is back on the home Wi-Fi.

**On the watch** (Wear OS, built for the Galaxy Watch 4)

- Joins the run of the phone: heart rate, glanceable pace, time and distance, start, pause, lap and stop.
- A coaching page (the step, what remains of it, its target; or ahead or behind the pacer) and vibrations for each cue.
- Can ask the phone to start a run from the wrist, and has a tile showing the run in progress.

**On the web** (served by the home server, for every device of the household)

- Imports `.fit` and `.gpx` files, from the phone or from any watch's export.
- Each run in detail: map, charts, splits, laps, heart-rate zones, and the session or route it followed.
- **Progression**: a calendar of the year, weekly and monthly volume, progress curves, personal records with predictions, and training load with its recovery advice.
- **Segments**: the portions you run often, found automatically, with every passage ranked.
- **Planifier**: write sessions and draw routes (from a GPX or from a past run), and mark them to be sent to the phone.
- Several **profiles** in one household; a run done together can be copied to the other person.

## How it fits together

```
Watch ── Bluetooth ──► Phone ── home Wi-Fi ──► Raspberry Pi ◄── browser on the home network
 heart rate,            records the run,         server + database,
 controls, coaching     coaching, map            web app
```

- **The phone records, the watch assists.** The watch's GPS and battery are spared: it measures heart rate and shows what the phone sends.
- **One `.fit` file per run** goes from the phone to the server, through the same import as a file dropped in the web app.
- **Sessions and routes go the other way**: marked on the web, they reach the phone at its next sync at home and stay on it, so a run needs no network.
- **The server** is one Rust program with its SQLite database and the web app built in, in one Docker container, using little memory. It makes no request to the internet.

More in [docs/architecture.md](docs/architecture.md).

## Installation

### 1. The server on the Raspberry Pi

You need a Raspberry Pi 4 or newer with a **64-bit** system (`uname -m` prints `aarch64`) and Docker:

```bash
curl -fsSL https://get.docker.com | sh
```

```bash
sudo usermod -aG docker $USER
```

Log out and in again, then create a directory for the install. The data stays next to the compose file, in a `data` subdirectory, so one folder holds everything:

```bash
mkdir -p ~/running/data && sudo chown 65532:65532 ~/running/data
```

```bash
mkdir -p ~/running && cd ~/running
```

Save this as `~/running/compose.yaml` (it is also attached to each release, and kept in [`deploy/pi/compose.yaml`](deploy/pi/compose.yaml)):

```yaml
services:
  running:
    image: ghcr.io/andjab-bm/running:latest
    container_name: running
    restart: unless-stopped
    ports:
      - "8080:8080"
    environment:
      # Largest accepted file, in megabytes.
      MAX_UPLOAD_MB: "50"
      # Map background (a MapLibre style URL). Commented out: the public OpenFreeMap styles.
      # Set both to "" for no background and no map request at all.
      # MAP_STYLE_URL: ""
      # MAP_STYLE_URL_DARK: ""
    volumes:
      - ./data:/data
    logging:
      driver: json-file
      options:
        max-size: "5m"
        max-file: "3"
```

Start it:

```bash
docker compose pull && docker compose up -d
```

Open `http://<address-of-the-pi>:8080` from a computer or phone on the home network, create a first profile, and import your history from **Activités › Importer**. `docker compose ps` shows the container as `healthy` once it answers.

The image is built for ARM64 and AMD64 by GitHub on every change to `main`. If the repository is private, the image is too: sign in once on the Pi with a GitHub token that has the `read:packages` scope (`docker login ghcr.io -u <github-user>`), or make the package public in its settings on GitHub.

**Upgrading**: back up `~/running/data`, then run `docker compose pull && docker compose up -d` again. The database is migrated at start-up.

**Backing up**: everything is in `~/running/data`. Stop the container, copy the directory off the Pi (an SD card is not a backup), start it again:

```bash
docker compose stop && sudo tar czf running-backup.tar.gz -C data . && docker compose start
```

> **There is no sign-in.** Anyone who can reach the port can read and change every run, including where you live and when you are out. Keep it on the home network: **never forward port 8080 from your router.** To use it away from home, go through a VPN (WireGuard, Tailscale). See [docs/deployment.md](docs/deployment.md) for remote access, other settings, restoring a backup and building the image yourself.

### 2. The apps on the phone and the watch

Download the APKs from the [latest release](https://github.com/andjab-bm/Running/releases/latest) (**Assets**):

- **Phone**: open `running-phone-<version>.apk` on the phone, allow the installation of unknown apps when asked, install. Then in **Réglages**, enter `http://<address-of-the-pi>:8080` and choose your profile.
- **Watch**: installed once from a computer with `adb` over Wi-Fi (a Wear OS watch cannot install an APK from the phone).

Step by step, including updates and what to do when coming from a build made on a computer: [docs/install-android.md](docs/install-android.md).

### 3. First run

1. On the web, in **Planifier**, mark a session or a route "sur le téléphone" if you want one; the phone fetches it at its next sync on the home Wi-Fi (the **Plan** tab shows what it holds).
2. On the phone, open **Courir**: wait for "Position trouvée", choose a session, a route or a pace target if you like, and open the app on the watch.
3. Run. Back home, the run is sent to the server and appears on the web with its analysis.

## Privacy

- The server makes **no outbound request**; runs, heart rate and places stay on the Pi.
- The only requests that leave the house are for the **map background**: the browser, and the phone while its map is shown, fetch tiles from the provider of the configured style (OpenFreeMap by default), which sees the area displayed, never the runs. Set the style to nothing on the server, or turn off "Fond de carte pendant la course" on the phone, and no map request is made at all.
- The phone talks to the server only on the home network, over its address.

## Documentation

- [Architecture](docs/architecture.md) — how the parts fit together, and how to run the code for development.
- [Deployment](docs/deployment.md) — Docker settings, backups, upgrades, remote access, building the image.
- [Installing the apps](docs/install-android.md) — phone and watch, updates, publishing a release.
- [Android apps](docs/android.md) — building them, how they work, checklists on the devices.
- [Metrics](docs/metrics.md) — how every figure is computed.
- [Coaching checks](docs/coaching-checks.md) — how each scenario of the coaching is tested, and the checks left to do on the devices.
- [Design direction](docs/design-direction.md) — the approved look and its tokens.
- [Specification](Specification.md) — the original project brief (French).
- Planning lives in `openspec/`: [running-foundations](openspec/changes/running-foundations/proposal.md) (server and web), [android-recording](openspec/changes/android-recording/proposal.md) (phone and watch), [insights-and-coaching](openspec/changes/insights-and-coaching/proposal.md) (analysis, planning and coaching).

## Status

The server, the web app and both apps are built and in use on the owner's devices. The checks of the coaching on the phone and the watch, the field runs and the measures on the Raspberry Pi are in progress ([docs/coaching-checks.md](docs/coaching-checks.md)).
