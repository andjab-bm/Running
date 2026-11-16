# Running

A self-hosted, private running tracker for a household: import your runs, analyse them on a web app served from a Raspberry Pi, with no cloud and no account.

Status: the server, the web app and the phone and watch apps are built and in first use on the owner's devices; field validation on real runs and the Raspberry Pi deployment are in progress.

- [Architecture](docs/architecture.md) — how the watch, phone and home server fit together, and how to run the code.
- [Deployment](docs/deployment.md) — installing at home with Docker, backups, upgrades, and why the port must stay off the internet.
- [Android apps](docs/android.md) — building and installing the phone and watch apps.
- [Design direction](docs/design-direction.md) — the approved look and its tokens.
- [Specification](Specification.md) — the original project brief (French).
- Planning lives in `openspec/`: [running-foundations](openspec/changes/running-foundations/proposal.md) (server and web), [android-recording](openspec/changes/android-recording/proposal.md) (phone and watch).
