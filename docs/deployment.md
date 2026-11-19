# Deployment

How to run the tracker at home, on a Raspberry Pi or any 64-bit machine with Docker. One container serves the web app and the API on one port and keeps everything it stores in one volume.

> **There is no sign-in.** Anyone who can reach the port can read, change and delete every profile and every run, including where you live and when you are out. Keep the service on your home network. **Never forward its port from your router and never publish it on the internet.** To use it away from home, see [Remote access](#remote-access).

## What you need

- A Raspberry Pi 4 or newer (or any ARM64 / AMD64 machine) with a **64-bit** system. Check with `uname -m`: it must print `aarch64` or `x86_64`.
- Docker with the Compose plugin: `curl -fsSL https://get.docker.com | sh`, then `sudo usermod -aG docker $USER` and log in again.
- This repository on the machine: `git clone https://github.com/andjab-bm/Running.git`.

## Install

From the `deploy/` directory of the repository:

```bash
docker compose up -d
```

The first start builds the image on the machine itself. On a Raspberry Pi 4 this takes a long time (allow an hour) and needs about 2 GB of free memory; it is quicker to [use the image built by GitHub](#using-the-image-built-by-github) or to [build it on a faster computer](#building-the-image-on-another-machine).

Then open `http://<address-of-the-pi>:8080` from a phone or computer on the same network. The app asks for a first **profile** (a name is enough); after that, **Importer** takes `.fit` and `.gpx` files, several at a time.

To check the service from the Pi itself:

```bash
curl http://localhost:8080/api/v1/health
```

It answers with a status, the version and the state of the database. `docker compose ps` shows the container as `healthy` once that check passes.

## Settings

Every setting has a default, so no file is needed. To change one, copy `deploy/.env.example` to `deploy/.env`, edit it, and run `docker compose up -d` again.

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | port of the web app and API on the network |
| `DATA_VOLUME` | `running-data` | where the data is kept: a Docker volume name, or a directory on the host |
| `MAX_UPLOAD_MB` | `50` | largest accepted file |
| `MAP_STYLE_URL` | OpenFreeMap "positron" | map background (a MapLibre style URL); set to nothing to disable it |
| `MAP_STYLE_URL_DARK` | OpenFreeMap "dark" | background used with the dark theme; follows `MAP_STYLE_URL` when that one is changed |
| `IMAGE` | `running:local` | image to run instead of the one built here |

The server itself (outside Docker) reads `PORT`, `DATA_DIR`, `MAX_UPLOAD_MB`, `MAP_STYLE_URL` and `MAP_STYLE_URL_DARK`; in the image `DATA_DIR` is fixed to `/data`.

**Data in a directory of the host.** The container does not run as root but as user `65532`. A directory given as `DATA_VOLUME` must belong to that user:

```bash
sudo mkdir -p /srv/running && sudo chown 65532:65532 /srv/running
```

## Backup and restore

Everything is under the data volume: the database (`running.db` and its `-wal` / `-shm` companions) and the original files (`originals/`). Backing up that volume backs up everything.

Stop the service so the database is at rest, archive the volume, start again:

```bash
docker compose stop
```

```bash
docker run --rm -v running_running-data:/data:ro -v "$PWD":/backup busybox tar czf /backup/running-backup.tar.gz -C /data .
```

```bash
docker compose start
```

The volume is named after the Compose project: `running_running-data` when the project is started from a directory called `running`, `deploy_running-data` when started from `deploy/` as above — `docker volume ls` shows the exact name. With a host directory as `DATA_VOLUME`, archive that directory instead.

To restore on a fresh install, start once so the volume exists, stop, then unpack over it:

```bash
docker run --rm -v deploy_running-data:/data -v "$PWD":/backup busybox sh -c "rm -rf /data/* && tar xzf /backup/running-backup.tar.gz -C /data && chown -R 65532:65532 /data"
```

Copy the archive off the Pi (another disk, another machine): an SD card is not a backup.

## Upgrading

Back up first: the database is migrated automatically at start-up and migrations only go forward.

```bash
git pull
```

```bash
docker compose up -d --build
```

Recreating the container never touches the volume: profiles, runs, notes and original files are still there.

## Using the image built by GitHub

The quickest install on a Raspberry Pi needs no clone of the repository: [`deploy/pi/compose.yaml`](../deploy/pi/compose.yaml), also attached to every release, runs `ghcr.io/andjab-bm/running:latest` with the data in `/srv/running`. The README walks through it. The steps below do the same with the compose file of `deploy/`.

The repository's CI builds the image for ARM64 and AMD64 on every pull request, and publishes it to the GitHub Container Registry on every push to `main` (and when the workflow is started by hand from the Actions tab): `ghcr.io/andjab-bm/running:latest`, plus one `sha-<commit>` tag per build. Nothing then has to be compiled on the Pi.

In `deploy/.env`:

```
IMAGE=ghcr.io/andjab-bm/running:latest
```

```bash
docker compose pull
```

```bash
docker compose up -d --no-build
```

The package is private as long as the repository is: on the Pi, sign in once with a GitHub personal access token that has the `read:packages` scope (`docker login ghcr.io -u <github-user>`), or make the package public in its settings on GitHub. To upgrade, run the two commands again.

## Building the image on another machine

The image can be built for the Pi on a regular computer; the server is cross-compiled, so nothing runs under emulation. From the repository root:

```bash
docker buildx build -f deploy/Dockerfile --platform linux/arm64 -t running:local --load .
```

```bash
docker save running:local | ssh pi@<address-of-the-pi> docker load
```

On the Pi, `docker compose up -d --no-build` then uses that image. To publish one image for both architectures to a registry of your own, build with `--platform linux/arm64,linux/amd64 -t <registry>/running:<version> --push` and set `IMAGE` in `.env`; `docker buildx imagetools inspect <registry>/running:<version>` lists the platforms it holds.

## Map background and privacy

The server never contacts another machine. The only requests that leave your network are made by the **browser**, for the map background: tiles come from the host of `MAP_STYLE_URL` (by default `tiles.openfreemap.org`), which therefore sees which areas are being looked at, not the runs themselves.

- **No background at all**: put `MAP_STYLE_URL=` (nothing after the sign) in `.env`. Traces are drawn on a plain background and the browser talks to no host but the Pi.
- **Self-hosted tiles**: download a regional extract in [PMTiles](https://docs.protomaps.com/pmtiles/) format, serve the file and a style that points to it from a web server on your network (the `pmtiles` tool has a `serve` command; any static file server that supports range requests works too), and set `MAP_STYLE_URL` to the address of that style. Tile requests then stay at home.

## Remote access

The port must stay closed to the internet. Two ways to reach the tracker from outside:

- **A VPN into the home network** — WireGuard, or Tailscale for the least set-up. The phone then reaches the Pi at its usual address as if it were at home; the Android app syncs through it as well.
- **A reverse proxy that authenticates** — for example Caddy or nginx with a password or single sign-on in front, and TLS. The tracker has no notion of users, so the proxy is the only gate: without authentication on it, this is the same as publishing the port.

## Measured on the Raspberry Pi

To be filled in when the image runs on the Pi (tasks 10.1 and 10.2 of `running-foundations`): import time of a one-hour run (target under 5 s) and idle memory after ten minutes with the imported history (target under 128 MB, read with `docker stats --no-stream`).
