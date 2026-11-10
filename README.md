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
