# CasaOS companion upgrade checklist

This guide is for an existing Allerflux companion deployment. It intentionally preserves the current Compose project, `.env` secrets and named `allerflux-data` volume.

## Before upgrading

1. Open the existing deployment directory:

   `/DATA/AppData/jellyfin_companion/deploy`

2. Back up the current SQLite database using the authenticated endpoint described in [Backup and recovery](BACKUP_RECOVERY.md).
3. Make a separate copy of the existing `.env` file.
4. Edit the existing `.env` file in place. Do not replace it with `.env.example`. Preserve the current Jellyfin URL/API key and Allerflux API key. Add only the new optional settings you intend to use:

   - `ALLERFLUX_WEBHOOK_KEY` — required only for Jellyfin playback event ingestion.
   - `IDLE_ALERT_THRESHOLD_SECONDS` and `ALERT_COOLDOWN_MINUTES` — idle alert threshold/cooldown.
   - `ALERT_WEBHOOK_URL` — optional external JSON notification receiver.
   - `SNAPSHOT_RETENTION_DAYS` and `EVENT_RETENTION_DAYS` — history retention.

## Upgrade

Run from the existing `deploy` directory:

```bash
docker compose config
docker compose up -d --build
docker compose ps
docker logs --tail=100 allerflux-companion
```

The Compose file builds from the repository's `companion` directory and mounts the existing `allerflux-data` volume at `/data`. Running `docker compose up -d --build` from the same project directory preserves that named volume.

**Do not run `docker compose down -v`, remove the volume, or create a new Compose project name.** Those actions can disconnect or delete the existing database volume.

## Verify

- `http://127.0.0.1:8787/health` returns `{"status":"ok","service":"allerflux-companion"}`.
- Authenticated `GET /api/v1/diagnostics` reports database connectivity and a recent successful poll.
- Open the Android app and verify Overview, Activity, Library, Reports and Alerts.
- Test CSV/PDF Save As and database backup download.
- If event analytics are enabled, configure the Jellyfin Webhook plugin using [the webhook setup guide](JELLYFIN_WEBHOOK_SETUP.md).

Do not paste API keys or webhook secrets into chat, shell output, screenshots or GitHub commits. Keep port 8787 on a trusted LAN/VPN or behind a trusted HTTPS reverse proxy.
