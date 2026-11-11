# Analytics data quality and API

## Snapshot data
Jellyfin's `/Sessions` endpoint describes sessions that exist now. Allerflux records periodic observations. These support:
- current session status from the live endpoint;
- approximate observed activity over time;
- daily snapshot trends and observed user/title counts.

Snapshots do not reliably establish exact playback starts, stop times, watch duration, completion, or unique plays. A single long playback may contribute many snapshots. The app labels these as observations. Top-title and top-user rankings are likewise sample-based: longer sessions and shorter polling intervals can produce more samples.

## Event-based playback analytics

For higher-fidelity playback records, configure the official Jellyfin Webhook plugin to send a JSON body to `POST /api/v1/webhooks/jellyfin`. Set `ALLERFLUX_WEBHOOK_KEY` to a separate long random secret and send it in the `X-Allerflux-Webhook-Key` header. The companion accepts `PlaybackStart`, `PlaybackProgress`, and `PlaybackStop`, validates the timestamp/user/item fields, stores immutable event records, and deduplicates identical deliveries.

Use the plugin's JSON body template with these fields: `NotificationType`, `UtcTimestamp`, `UserId`, `NotificationUsername`, `ItemId`, `Name`, `ItemType`, `PlaybackPositionTicks`, `RunTimeTicks`, `PlayedToCompletion`, `PlayMethod`, `DeviceName`, `DeviceId`, and `ClientName`. Configure the plugin to send only the three playback notification types. The timestamp is required for stable event identity.

- `GET /api/v1/events/summary?days=30` — event-based starts, stops, explicit completion flags, distinct users and titles.
- `GET /api/v1/events/recent?limit=100` — recent immutable playback event records.

Event counts represent notifications received by the companion. They are distinct from periodic snapshot observations. A stop is not automatically a completion; completion is counted only when Jellyfin explicitly supplies `PlayedToCompletion=true`. Allerflux does not infer total watch duration from sparse progress events.

## Companion endpoints
All endpoints except `/health` require the `X-Allerflux-Key` header.

- `GET /health` — service health.
- `POST /api/v1/webhooks/jellyfin` — authenticated Jellyfin playback event ingestion (separate webhook secret).\n- `GET /api/v1/events/summary?days=30` — event-derived playback counts.\n- `GET /api/v1/events/recent?limit=100` — recent event records.\n- `GET /api/v1/overview` — registered users and live session counts.
- `GET /api/v1/sessions` — current Jellyfin sessions and playback state.
- `GET /api/v1/library` — Jellyfin movie, series, episode, song, album, artist, and box-set counts.
- `GET /api/v1/history?limit=200` — recent stored snapshots.
- `GET /api/v1/reports/summary?days=30` — observed snapshot, user, title, playing, and paused counts with a continuous daily series (zero-filled dates).
- `GET /api/v1/reports/top-titles?days=30&limit=10` — titles ranked by observed snapshot count, with observed users and playing/paused samples.
- `GET /api/v1/reports/top-users?days=30&limit=20` — users ranked by observed snapshot count, with distinct observed titles and playing/paused samples.
- `GET /api/v1/items/{item_id}/analytics` — observed snapshots and observed users for an item.

## Future exact playback analytics
For exact plays and watch time, implement a supported event ingestion path such as Jellyfin webhooks or a compatible playback reporting source. Persist immutable start/progress/stop events, deduplicate by event/session identifiers, and only calculate completion when trustworthy stop/progress data and media runtime are available. Never silently convert snapshots into exact playback events.
