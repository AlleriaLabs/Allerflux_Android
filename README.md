# Allerflux
**Your Media Server Intelligence** — an Android companion for Jellyfin server analytics.

Allerflux uses Architecture A:
- **Android client:** Kotlin, Jetpack Compose, Material 3.
- **Server companion:** Python 3.12 + FastAPI, deployed beside Jellyfin with Docker Compose.
- **Persistence:** SQLite initially, with a repository layer that can move to PostgreSQL later.
- **Access:** local LAN and remote HTTPS/VPN. Never expose the companion directly to the public internet without TLS and authentication.

## Product scope
- Server overview, registered users, active sessions, and current playback state.
- Playback history and per-item analytics.
- Daily, weekly, and monthly reports.
- Per-item plays, unique viewers, active/paused sessions, completion estimates, and watch time.

## Accuracy note
Jellyfin's live Sessions endpoint is a snapshot, not a complete historical event log. The companion records periodic snapshots and session transitions, but exact start/stop/completion history requires a reliable event source such as Jellyfin webhooks or a supported playback-reporting integration. Allerflux must label inferred metrics as estimates and must not present snapshot-derived data as exact history.

## Repository layout
- `android/` — native Android application.
- `companion/` — FastAPI service and database.
- `deploy/` — Docker Compose and deployment examples.
- `docs/` — setup, API, and analytics notes.

## Status
Project foundation. Implementation is being built in stages; do not treat the initial scaffold as a production release.

## Security
Keep Jellyfin credentials and the Allerflux API secret in server-side environment variables. Do not commit secrets. Use a strong unique companion secret and expose the API only through a trusted LAN, VPN, or HTTPS reverse proxy.

## Companion deployment (CasaOS / Docker)

1. Confirm the Jellyfin URL from the Docker host or companion container. The previously used server address was `http://192.168.68.107:8097`; verify it is still correct.
2. Create a Jellyfin API key in the Jellyfin dashboard (Dashboard → API Keys).
3. On the server, clone this repository and enter `deploy/`.
4. Copy `.env.example` to `.env`, then set `JELLYFIN_URL`, `JELLYFIN_API_KEY`, and a long random `ALLERFLUX_API_KEY`.
5. Start the service with `docker compose up -d --build`.
6. Check `http://SERVER-LAN-IP:8787/health`. Configure the Android app with `http://SERVER-LAN-IP:8787` and the same Allerflux API key.

The companion's container port is 8787. The sample Compose file uses Docker's default bridge network and reaches Jellyfin through the configured server URL; it does not assume a Jellyfin-specific Docker network name.

**Network safety:** the Android client currently permits cleartext HTTP so it can connect to a trusted home-LAN companion. Keep this port LAN/VPN-only. For access over the public internet, put it behind HTTPS or use a VPN; do not forward port 8787 directly to the internet.

## Android build

The GitHub Actions workflow builds a debug APK on pushes that touch Android/build files and on manual dispatch. Download the `allerflux-debug-apk` artifact from the successful workflow run. This is a debug build, not a Play Store release.
