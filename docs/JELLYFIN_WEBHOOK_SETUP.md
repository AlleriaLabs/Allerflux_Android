# Jellyfin playback webhook setup

Allerflux accepts event notifications from the official Jellyfin Webhook plugin. The plugin must be installed and enabled on the Jellyfin server; the companion does not install it automatically.

## 1. Configure the companion secret

Add a new, separate secret to `deploy/.env`:

```env
ALLERFLUX_WEBHOOK_KEY=use_a_different_long_random_secret
```

Do not reuse `ALLERFLUX_API_KEY` or the Jellyfin API key. Recreate the companion container after changing its environment. Keep the existing `allerflux-data` volume and existing `.env` values.

## 2. Add a Webhook destination in Jellyfin

- Destination URL: `http://YOUR-CASAOS-LAN-IP:8787/api/v1/webhooks/jellyfin`
- HTTP method: `POST`
- Header: `X-Allerflux-Webhook-Key: YOUR_ALLERFLUX_WEBHOOK_KEY`
- Content type: `application/json`
- Enable only Playback Start, Playback Progress and Playback Stop notifications.

Use the address reachable from the Jellyfin server. For remote deployments, use a trusted VPN or HTTPS reverse proxy; do not expose port 8787 directly to the public internet.

## 3. JSON body template

Paste this into the Webhook plugin's request body/template field:

```json
{
  "NotificationType": "{{NotificationType}}",
  "UtcTimestamp": "{{UtcTimestamp}}",
  "UserId": "{{UserId}}",
  "NotificationUsername": "{{NotificationUsername}}",
  "ItemId": "{{ItemId}}",
  "Name": "{{Name}}",
  "ItemType": "{{ItemType}}",
  "PlaybackPositionTicks": "{{PlaybackPositionTicks}}",
  "RunTimeTicks": "{{RunTimeTicks}}",
  "PlayedToCompletion": "{{PlayedToCompletion}}",
  "PlayMethod": "{{PlayMethod}}",
  "DeviceName": "{{DeviceName}}",
  "DeviceId": "{{DeviceId}}",
  "ClientName": "{{ClientName}}"
}
```

The template uses the documented variable names from the Jellyfin Webhook plugin. Allerflux requires the UTC timestamp, user ID and item ID. Optional playback fields may be empty.

## 4. Verify ingestion

- Check `GET /health` for companion availability.
- Use the Jellyfin Webhook plugin's test/notification function if available.
- Start a test playback, then call authenticated `GET /api/v1/events/recent?limit=10` from a trusted client.
- Confirm `GET /api/v1/events/summary?days=7` increments.

The webhook endpoint responds with `duplicate: true` when an identical notification is delivered again. Event records are separate from the companion's periodic session snapshots.

## Data interpretation

- Playback Start is an event count, not a count of completed watches.
- Playback Stop is not automatically a completion.
- Completed stops are counted only when the plugin supplies `PlayedToCompletion=true`.
- Allerflux does not infer total watch duration from sparse progress notifications.
