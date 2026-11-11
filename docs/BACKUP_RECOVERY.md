# Allerflux backup and recovery

## Automated database backup

The companion exposes an authenticated SQLite backup endpoint:

`GET /api/v1/admin/backup`

Send the companion API key in the `X-Allerflux-Key` header. The endpoint uses SQLite's online backup API to produce a consistent database copy, including committed snapshots, playback events and alert history. It returns a timestamped `.sqlite3` file.

This endpoint backs up the database only. It does not export `deploy/.env`, Jellyfin API keys, webhook secrets, Docker configuration or other host files. Store the backup securely because it contains user and media activity history.

## Retention

The companion automatically removes old rows once per day:

- `SNAPSHOT_RETENTION_DAYS=365` (minimum effective retention: 30 days)
- `EVENT_RETENTION_DAYS=730` (minimum effective retention: 30 days)

Alert history is retained separately and is not currently pruned automatically.

## Restore procedure

Restore is deliberately an operator-controlled maintenance action; the API does not overwrite a live database.

1. Stop the `allerflux-companion` container.
2. Make a copy of the current database from the existing `allerflux-data` volume before replacing anything.
3. Use the exact existing Docker volume mounted at `/data` and replace `/data/allerflux.db` with the selected backup file.
4. Start the companion container and verify `/health`, authenticated `/api/v1/diagnostics`, event history and alert history.
5. Keep the original backup until the restored data is verified.

Do not delete or recreate the existing Docker volume. Preserve the current `deploy/.env` file and its API/webhook secrets during upgrades and restores. If the deployment uses a non-SQLite database, use that database's native backup tooling; the API endpoint returns HTTP 501 for non-SQLite backends.

## Diagnostics

`GET /api/v1/diagnostics` reports database connectivity, poll interval, last poll attempt/success, the latest poll error type and retention settings. It requires the companion API key.
