# Analytics data quality

The Jellyfin `/Sessions` endpoint describes sessions that exist now. It does not provide a complete playback event history. Allerflux stores periodic snapshots as observational data.

Snapshot records can support:
- current session status (from the live endpoint);
- approximate observed activity over time;
- trend visualizations with clear "observed" or "estimated" labels.

Snapshot records alone cannot reliably establish:
- exact play counts or distinct playback starts;
- exact stop times or total watch duration;
- whether a user finished an item;
- unique viewers over an arbitrary period when users are missing or identities change.

For accurate event analytics, implement a supported event ingestion path (for example, Jellyfin webhook notifications or a compatible playback reporting source), persist immutable play-start/progress/stop events, and reconcile event identifiers to avoid duplicates. Completion should be computed only when a trustworthy stop/progress event and media runtime are available. Do not silently convert snapshots into exact playback events.
