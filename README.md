# Running

A self-hosted, private running tracker for a household: import your runs, analyse them on a web app served from a Raspberry Pi, with no cloud and no account.

Status: the server, the web app and the phone and watch apps are built and in first use on the owner's devices. Training insights (statistics, records and predictions, load, segments) and coaching (guided sessions, routes with a map, a pace target, voice and vibration cues on the phone and the watch, a watch tile) are built; their checks on the phone and the watch, the field runs and the Raspberry Pi deployment are in progress.

- [Architecture](docs/architecture.md) — how the watch, phone and home server fit together, and how to run the code.
- [Deployment](docs/deployment.md) — installing at home with Docker, backups, upgrades, and why the port must stay off the internet.
- [Android apps](docs/android.md) — building and installing the phone and watch apps.
- [Coaching checks](docs/coaching-checks.md) — how each scenario of insights-and-coaching is tested, and the checks left to do on the devices.
- [Design direction](docs/design-direction.md) — the approved look and its tokens.
- [Specification](Specification.md) — the original project brief (French).
- Planning lives in `openspec/`: [running-foundations](openspec/changes/running-foundations/proposal.md) (server and web), [android-recording](openspec/changes/android-recording/proposal.md) (phone and watch), [insights-and-coaching](openspec/changes/insights-and-coaching/proposal.md) (analysis, planning and coaching).
- Privacy: the server makes no outbound request. With the basemap on, the phone's map loads tiles from the map provider configured on the server, which then sees the area shown on screen (never the run itself); switching "Fond de carte pendant la course" off, or leaving the server's map style empty, means no map request at all.
