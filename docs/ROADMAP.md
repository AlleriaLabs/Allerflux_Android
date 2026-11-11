# Allerflux completion roadmap

This roadmap covers the Jellyfin analytics product only. It does not include unrelated Alleria Labs monitoring products.

## Product principles

- Keep Jellyfin monitoring read-only: never stop, pause, disconnect, or modify user sessions.
- Use live Jellyfin APIs for current state and persisted companion data for history.
- Label snapshot-derived metrics as observations. Never present them as exact plays, completions, or watch time.
- Exact playback analytics must come from a supported event source, with deduplication and reconciliation.
- Preserve the existing Docker volume and deployment secrets during upgrades. Never overwrite `deploy/.env` or the `allerflux-data` volume.

## Phases and acceptance criteria

| Phase | Scope | Definition of done | Status |
|---|---|---|---|
| 1. Premium app foundation | Eight discoverable destinations, coherent navigation, design tokens, reusable cards, responsive layouts, loading/error/empty states | All eight screens reachable; consistent visual system; Android build and navigation QA pass | In progress — navigation, premium header, Overview quick actions, session cards, leaderboard bars, library grouping and settings sections implemented; physical-device QA remains |
| 2. Companion reliability | API validation, structured errors, polling health, DB migrations, retention controls, tests | Automated backend tests cover auth, polling, API contracts, persistence and failure paths | In progress — versioned additive schema bootstrap, existing-data preservation/idempotency tests, authenticated diagnostics/report contract tests, session/alert tests, poll diagnostics and retention controls implemented; polling failure-path coverage and a live upgrade drill remain |
| 3. Playback event intelligence | Supported Jellyfin webhook/event ingestion, immutable event records, idempotency, session reconciliation | Event-derived starts/stops/progress are deduplicated and clearly separated from snapshot estimates | In progress — webhook ingestion merged; live Jellyfin plugin configuration and server-side verification remain |
| 4. Analytics workspace | 7/30/90/365-day filters, date ranges, daily/weekly/monthly aggregation, comparison periods, richer viewer/title analytics | Aggregations validated against fixture data; UI labels distinguish events from observations | In progress — 7/30/90/365-day filters, daily/weekly/monthly rollups and previous-period comparisons implemented; custom date ranges and timezone controls remain |
| 5. Reports and exports | CSV and PDF exports, share/save flows, report metadata and timezone-aware date ranges | Exports match selected filters and totals; generated files validated | In progress — CSV/PDF Save As flows and export data-quality notes implemented; on-device file validation remains |
| 6. Alerts and notifications | Configurable idle-session and server-health alerts, cooldowns, recovery events, notification history | Alerts are opt-in, deduplicated, configurable and strictly read-only | In progress — idle/outage alert engine, recovery history, optional outbound webhook and Android Alerts screen implemented; live validation remains |
| 7. Security and resilience | Keystore-backed Android secret storage, API-key rotation guidance, TLS/VPN guidance, DB backup/restore, retention and recovery | Secrets are not stored in plaintext; backup/restore tested without losing existing data | In progress — Keystore storage, rotation guidance, retention and authenticated SQLite backup implemented; restore drill and device validation remain |
| 8. Release and operations | Docker upgrade guide, health diagnostics, multi-server readiness, release signing, versioning, install/upgrade QA | Reproducible release APK, verified upgrade path, deployment/rollback docs and end-to-end QA | In progress — signed release workflow, keystore instructions and CasaOS upgrade guide merged; signing secrets, signed release run and physical-device/server QA remain |

## Existing baseline

- Android app and FastAPI companion use Architecture A.
- Live overview, sessions and library inventory are read from Jellyfin.
- Companion persists periodic session snapshots in SQLite.
- Snapshot-based rankings remain explicitly labeled as observed samples; separate Jellyfin webhook event counts are available when configured.
- GitHub Actions builds a debug APK; a debug build is not a signed production release.
- Existing CasaOS deployment state is not changed by repository work. Deployment must be verified on the user's server before calling the companion upgrade complete.

## Current work

The primary destinations are Overview, Activity, Library and Reports; the More workspace provides entry points for Top Viewers, Top Videos, Alerts, Settings and About. PR #13 adds CSV/PDF exports; PR #14 adds idle-session and server-availability alerts.


## Multi-server expansion (new)

Target architecture: one Allerflux companion installation manages multiple Jellyfin server connections. The Android app connects to that companion once, then selects a server context. Each configured Jellyfin server receives a stable internal server ID.

### Required isolation rules
- Every snapshot, playback event, alert state/event and report query must be scoped by server ID.
- Never merge users or item IDs across servers; Jellyfin IDs are only unique within their own server.
- Server credentials must be encrypted at rest using a dedicated server-side encryption key, never returned by API responses, and never logged.
- Server create/update/delete operations require companion API authentication; deletion must require explicit confirmation and must not erase historical analytics by default.
- The existing single-server environment configuration must migrate as the default server without losing existing snapshots, events, alerts, or API credentials.
- Poll each server independently with per-server health, last-success and error status. One unavailable Jellyfin server must not stop polling other servers.
- Webhook ingestion must identify its source server using a per-server webhook token or signed source mapping; do not infer the source from user/item IDs.

### Delivery sequence
1. UI foundation: Allerion-inspired charcoal surfaces, rounded bordered cards, compact statistics, refined typography, animated active navigation; preserve Allerflux cyan `#39D5F2` and purple `#9B72FF`.
2. Server manager UI: server list, status, active-server switcher and floating Add Server action.
3. Companion schema and encrypted credential store; additive migration of the existing default server.
4. Per-server pollers, API response scoping, webhook routing, alerts and reports.
5. Android server switching, secure profile handling, empty/error states and integration tests.
6. Migration drill and multi-server end-to-end verification before release.

The initial UI theme commit does not claim multi-server backend support. Multi-server is complete only after the data-isolation and migration acceptance criteria above pass.
