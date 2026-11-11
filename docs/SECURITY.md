# Allerflux security and key rotation

## Android credential storage

The Android client encrypts the companion URL and API key using AES-GCM with a key held in Android Keystore. Existing plaintext values from the legacy `allerflux` preferences are migrated into encrypted preferences on first launch after this update, then removed from the legacy preference store. If the Keystore key becomes unavailable, the app will ask you to enter the connection settings again rather than silently falling back to plaintext storage.

## Companion secrets

Keep these values in `deploy/.env` only:

- `JELLYFIN_API_KEY` — Jellyfin server API key.
- `ALLERFLUX_API_KEY` — Android-to-companion API authentication.
- `ALLERFLUX_WEBHOOK_KEY` — separate authentication for Jellyfin webhook ingestion.

Use distinct, long random values. Never commit the real `.env` file, paste keys into issue reports, or reuse the Jellyfin key as either Allerflux secret.

## Rotate the companion API key

1. Generate a new unique `ALLERFLUX_API_KEY`.
2. Update the existing `deploy/.env` file; do not replace the entire file with the example.
3. Recreate/restart the companion container with the existing `allerflux-data` volume preserved.
4. Open Allerflux Settings and save the new companion API key.
5. Verify Overview, Reports, Alerts and Settings diagnostics.

Requests using the old key will fail after the companion restarts with the new value. Rotate `ALLERFLUX_WEBHOOK_KEY` separately and update the Jellyfin Webhook plugin header at the same time.

## Network access

Keep port 8787 on a trusted LAN or VPN. For remote access, use HTTPS through a trusted reverse proxy or VPN. Do not port-forward the companion directly to the public internet. The companion API key is a bearer-style secret; anyone holding it can read server activity and download database backups.

## Backups

The authenticated database backup endpoint returns a consistent SQLite database copy. It does not contain `.env` secrets. Store backup files privately and follow [backup and recovery](BACKUP_RECOVERY.md) before replacing the live database.
