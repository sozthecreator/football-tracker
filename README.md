# Football Tracker

Live football scores, goal sounds and notifications for your teams, on Android and on your computer.

## Downloads

Get the latest version from the [Releases page](https://github.com/sozthecreator/football-tracker/releases/latest).

| Platform | File | Notes |
|---|---|---|
| Android | `football-tracker.apk` | Allow installs from this source when Android asks. |
| Windows | `football-tracker-<version>.msi` | Installer with Start menu and desktop shortcuts. Java is bundled. |
| Linux | `football-tracker_<version>_amd64.deb` | Installs a Sports menu entry. |
| Linux, portable | `football-tracker-<version>.zip` | Unzip and run `bin/football-tracker`. |

The apps check for new versions when they open and offer the update.

## All versions

| Version | Release |
|---|---|
| 1.3.2 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.3.2) |
| 1.3.1 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.3.1) |
| 1.3.0 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.3.0) |
| 1.2.6 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.6) |
| 1.2.5 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.5) |
| 1.2.4 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.4) |
| 1.2.3 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.3) |
| 1.2.2 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.2) |
| 1.2.1 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.1) |
| 1.2.0 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.2.0) |
| 1.1.1 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.1.1) |
| 1.1.0 | [Release](https://github.com/sozthecreator/football-tracker/releases/tag/v1.1.0) |

## Building from source

- `app/` is the Android app, `desktop/` is the Windows/Linux app, `worker/` is the Cloudflare Worker that serves scores and accounts.
- Setting up the Worker is described in [worker/README.md](worker/README.md).
- `release.sh` builds the APK, the Linux package and the zip, and creates the GitHub Release. Pushing a `v*` tag makes GitHub build the Windows `.msi`.
