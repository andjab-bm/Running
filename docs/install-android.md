# Installing the phone and watch apps

Each release of the repository carries three files, under **Assets**:

- `running-phone-<version>.apk` — the phone app (Android 14 or newer; built for the Pixel 8);
- `running-watch-<version>.apk` — the watch app (Wear OS 3 or newer; built for the Galaxy Watch 4);
- `compose.yaml` — the Docker Compose file that runs the server on the Raspberry Pi (see the README).

Releases are listed at https://github.com/andjab-bm/Running/releases; the latest one is always at https://github.com/andjab-bm/Running/releases/latest. Install the phone and the watch app **from the same release**: they talk to each other only when they come from the same build key, and a new release of one is meant to go with the same release of the other.

## The phone

1. On the phone, open the release page and tap `running-phone-<version>.apk` to download it.
2. Open the downloaded file. The first time, Android asks to allow your browser (or Files) to **install unknown apps**: allow it, go back, and tap **Install**.
3. Open **Running**. Allow location (precise) when asked, and notifications, so a run keeps recording with the screen off and shows its progress.
4. In **Réglages**, enter the address of the home server, `http://<address-of-the-pi>:8080`, and choose your profile. Runs, sessions and routes then sync whenever the phone is on the home Wi-Fi.

**Updating**: download the APK of the newer release and install it over the current app; runs and settings stay.

## The watch

A Wear OS watch cannot install an APK opened from the phone. It is installed once from a computer, over Wi-Fi, with `adb` (Android's [platform tools](https://developer.android.com/tools/releases/platform-tools), a zip to unpack on Windows, macOS or Linux):

1. On the watch: **Settings › About watch › Software information**, tap **Software version** seven times to turn on the developer options.
2. **Settings › Developer options**: turn on **ADB debugging** and **Wireless debugging**, then open Wireless debugging › **Pair new device**. The watch shows an address with a pairing port and a six-digit code; keep the computer on the same Wi-Fi.
3. On the computer, in the platform-tools folder:

   ```bash
   adb pair <watch-ip>:<pairing-port>
   ```

   (type the six-digit code), then, with the address and port shown on the Wireless debugging screen itself:

   ```bash
   adb connect <watch-ip>:<debug-port>
   ```

   ```bash
   adb -s <watch-ip>:<debug-port> install -r running-watch-<version>.apk
   ```

4. Open **Running** on the watch and allow access to the body sensors (heart rate). Turn Wireless debugging off again; it drains the battery.

**Updating**: the same `adb connect` and `adb install -r` with the newer APK.

To start a run, open **Courir** on the phone, then the app on the watch (or touch "Ouvrir sur la montre" on the phone): the watch joins, shows the heart rate, and both can start, pause and stop the run.

## Coming from a build made on a computer

An app built and installed from Android Studio or `./gradlew` is signed with that computer's debug key; the release APKs are signed with the project's key. Android refuses to install one over the other: **sync your runs first** (or share them as `.fit` from Historique), then uninstall the old app from the phone and the watch, and install both release APKs. Settings must be entered again.

## Publishing a release

For the owner of the repository. Once:

1. Create the signing key on your computer (keep the file and its passwords safe: a release signed with another key cannot update the installed apps):

   ```bash
   keytool -genkeypair -v -keystore running-release.jks -alias running -keyalg RSA -keysize 4096 -validity 10000
   ```

2. Encode it for GitHub:

   ```bash
   base64 -w0 running-release.jks > running-release.jks.txt
   ```

   (on Windows, in PowerShell: `[Convert]::ToBase64String([IO.File]::ReadAllBytes("running-release.jks")) > running-release.jks.txt`).

3. In the repository on GitHub, **Settings › Secrets and variables › Actions › New repository secret**, add `ANDROID_KEYSTORE_BASE64` (the content of `running-release.jks.txt`), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS` (`running` above) and `ANDROID_KEY_PASSWORD`. Then delete `running-release.jks.txt`.

Then, for each release, tag a commit of `main` and push the tag:

```bash
git tag v0.2.0
```

```bash
git push origin v0.2.0
```

The **Release** workflow runs the tests, builds both apps signed with that key, and publishes them with `compose.yaml` and this guide on the Releases page. The server image is published separately, on every push to `main`, as `ghcr.io/andjab-bm/running:latest`.
