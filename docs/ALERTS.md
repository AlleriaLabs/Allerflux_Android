# Allerflux alert configuration

Allerflux alert evaluation runs in the companion's existing read-only session poller. It never pauses, stops, disconnects, or modifies a Jellyfin session.

## Idle-session alerts

Set these values in `deploy/.env`:

```env
IDLE_ALERT_THRESHOLD_SECONDS=1800
ALERT_COOLDOWN_MINUTES=60
ALERT_WEBHOOK_URL=
```

The default idle threshold is 30 minutes. Alerts require Jellyfin to provide a usable last-activity timestamp. Sessions without a timestamp are not assigned a guessed idle duration. An alert is created once when a session crosses the threshold and a recovery record is created when the session is no longer above the threshold or disappears.

## Server availability

The companion records an alert when Jellyfin polling fails and a recovery event when polling succeeds again. The error history stores the exception type, not credentials or request headers.

## Optional outbound notifications

If `ALERT_WEBHOOK_URL` is set, the companion sends a JSON POST containing `alert_key`, `kind`, `title`, `message`, `status`, and `created_at` for trigger and recovery events. Use a trusted HTTPS endpoint or a private LAN/VPN receiver. Leave it blank to keep the alert history available through the authenticated API without outbound delivery.

## API

- `GET /api/v1/alerts/summary` — active count, total history count, threshold and notifier status.
- `GET /api/v1/alerts/history?limit=100` — newest trigger and recovery records.

Both endpoints require the companion API key. Alert history is informational; it does not provide session-control actions.
