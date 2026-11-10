# Allerflux
**Your Media Server Intelligence** — an Android companion for Jellyfin server analytics.

Allerflux uses Architecture A:
- **Android client:** Kotlin, Jetpack Compose, Material 3.
- **Server companion:** Python 3.12 + FastAPI, deployed beside Jellyfin with Docker Compose.
- **Persistence:** SQLite initially, with a repository layer that can move to PostgreSQL later.
- **Access:** local LAN and remote HTTPS/VPN. Never expose the companion directly to the public internet without TLS and authentication.

## Current capabilities
- Server overview, registered users, active sessions, and current playback state.
- Live Jellyfin library inventory counts for movies, series, episodes, songs, albums, and artists.
- Activity screen backed by live Jellyfin sessions.
- 30-day observed activity summary and recent snapshot history.
- Automatic dashboard refresh every 60 seconds.
- Companion API health and authenticated endpoints.

## Analytics accuracy
The companion stores periodic session snapshots. These support observed activity trends but are not a complete playback event log. Snapshot records cannot establish exact play counts, playback starts, stop times, completion, or total watch duration. Those metrics require reliable Jellyfin webhook/playback event ingestion and reconciliation. Allerflux labels snapshot-derived values as observations and does not report them as exact watch analytics.

## Repository layout
- `android/` — native Android application.
- `companion/` — FastAPI service and database.
- `deploy/` — Docker Compose and deployment examples.
- `docs/` — setup, API, and analytics notes.

## Security
Keep Jellyfin credentials and the Allerflux API secret in server-side environment variables. Do not commit secrets. Use a strong unique companion secret and expose the API only through a trusted LAN, VPN, or HTTPS reverse proxy. The Android client currently permits cleartext HTTP for trusted home-LAN connections; do not forward port 8787 directly to the public internet.

## Companion deployment (CasaOS / Docker)
1. Confirm the Jellyfin URL reachable from the companion container.
2. Create a Jellyfin API key in Jellyfin Dashboard → API Keys.
3. Copy `deploy/.env.example` to `deploy/.env` and set `JELLYFIN_URL`, `JELLYFIN_API_KEY`, and a strong `ALLERFLUX_API_KEY`.
4. Deploy with Docker Compose and confirm `http://SERVER-LAN-IP:8787/health` returns HTTP 200.
5. Configure the Android app with the companion URL and the same Allerflux API key.

## Android build
GitHub Actions builds a debug APK when Android or companion source changes are pushed to `main`, or on manual dispatch. Download the `allerflux-debug-apk` artifact from a successful workflow run. Debug APKs are for direct testing, not Play Store release.
