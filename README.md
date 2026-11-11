# Allerflux
**Your Media Server Intelligence** — an Android companion for Jellyfin server analytics.

Allerflux uses Architecture A:
- **Android client:** Kotlin, Jetpack Compose, Material 3.
- **Server companion:** Python 3.12 + FastAPI, deployed beside Jellyfin with Docker Compose.
- **Persistence:** SQLite initially, with a repository layer that can move to PostgreSQL later.
- **Access:** local LAN and remote HTTPS/VPN. Never expose the companion directly to the public internet without TLS and authentication.

## Current capabilities (Android 0.6.0)
- Premium Overview, Activity, Library and Reports destinations, with quick actions and an organized More workspace for Top Viewers, Top Videos, Alerts, Settings and About.
- Live Jellyfin session details, including device, client, app version, last activity and idle duration when supplied by Jellyfin.
- Live media inventory for movies, series, episodes, songs, albums and artists.
- 7-day, 30-day, 90-day and 1-year reports with daily, weekly and monthly rollups.
- Previous-period comparisons for observed snapshots and webhook playback events.
- Optional Jellyfin Webhook plugin ingestion with deduplication and event summaries.
- Top observed viewers and titles, CSV/PDF Save As exports, and explicit accuracy notes.
- Read-only idle-session and server-availability alerts, with recovery history and optional outbound JSON webhook notifications.
- Android Keystore-backed encrypted companion credentials, companion diagnostics, configurable history retention and authenticated SQLite database backups.
- Versioned additive companion schema bootstrap, preserving existing snapshot history.
- Signed Android release workflow (requires owner-managed GitHub Actions signing secrets).

## Analytics accuracy
Periodic session snapshots support observed activity trends and rankings, but they are not exact play counts or watch duration. When the Jellyfin Webhook plugin is configured, Allerflux separately records received playback start, progress and stop notifications; a stop counts as completed only when Jellyfin explicitly supplies a completion flag. Missing webhook events are not inferred, and total watch duration is not estimated.

## Repository layout
- `android/` — native Android application.
- `companion/` — FastAPI service and database.
- `deploy/` — Docker Compose and deployment examples.
- `docs/` — setup, API, analytics notes, [alert configuration](docs/ALERTS.md), [webhook setup](docs/JELLYFIN_WEBHOOK_SETUP.md), [security](docs/SECURITY.md), [backup/recovery](docs/BACKUP_RECOVERY.md), [signed release setup](docs/RELEASE.md), [CasaOS upgrade checklist](docs/DEPLOYMENT_CASAOS.md), and [completion roadmap](docs/ROADMAP.md).

## Security
Keep Jellyfin credentials and the Allerflux API secret in server-side environment variables. Do not commit secrets. Use a strong unique companion secret and expose the API only through a trusted LAN, VPN, or HTTPS reverse proxy. The Android client currently permits cleartext HTTP for trusted home-LAN connections; do not forward port 8787 directly to the public internet.

## Companion deployment (CasaOS / Docker)
1. Confirm the Jellyfin URL reachable from the companion container.
2. Create a Jellyfin API key in Jellyfin Dashboard → API Keys.
3. Copy `deploy/.env.example` to `deploy/.env` and set `JELLYFIN_URL`, `JELLYFIN_API_KEY`, and a strong `ALLERFLUX_API_KEY`.
4. Deploy with Docker Compose and confirm `http://SERVER-LAN-IP:8787/health` returns HTTP 200.
5. Configure the Android app with the companion URL and the same Allerflux API key.
6. For event-based playback analytics, follow [Jellyfin webhook setup](docs/JELLYFIN_WEBHOOK_SETUP.md) and configure a separate `ALLERFLUX_WEBHOOK_KEY`.

## Android build
GitHub Actions builds a debug APK when Android or companion source changes are pushed to `main`, or on manual dispatch. Download the `allerflux-debug-apk` artifact from a successful workflow run. Debug APKs are for direct testing. Signed release builds require the one-time GitHub Actions signing secrets documented in [Release setup](docs/RELEASE.md).
