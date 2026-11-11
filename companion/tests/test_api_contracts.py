import unittest

from fastapi.testclient import TestClient
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

import companion.app.database as database
import companion.app.main as main
from companion.app.database import Base


class WebhookApiContractTests(unittest.TestCase):
    def setUp(self):
        self.engine = create_engine(
            "sqlite://",
            connect_args={"check_same_thread": False},
            poolclass=StaticPool,
        )
        self.session_factory = sessionmaker(bind=self.engine, autoflush=False, autocommit=False)
        self.original_engine = main.engine
        self.original_main_session = main.SessionLocal
        self.original_db_engine = database.engine
        self.original_db_session = database.SessionLocal
        self.original_webhook_key = main.settings.allerflux_webhook_key
        self.original_api_key = main.settings.allerflux_api_key
        self.original_jellyfin_key = main.settings.jellyfin_api_key
        main.engine = self.engine
        database.engine = self.engine
        main.SessionLocal = self.session_factory
        database.SessionLocal = self.session_factory
        main.settings.allerflux_webhook_key = "test-webhook-secret"
        main.settings.allerflux_api_key = "test-api-secret"
        main.settings.jellyfin_api_key = ""

    def tearDown(self):
        main.engine = self.original_engine
        main.SessionLocal = self.original_main_session
        database.engine = self.original_db_engine
        database.SessionLocal = self.original_db_session
        main.settings.allerflux_webhook_key = self.original_webhook_key
        main.settings.allerflux_api_key = self.original_api_key
        main.settings.jellyfin_api_key = self.original_jellyfin_key
        self.engine.dispose()

    def test_webhook_requires_separate_secret(self):
        with TestClient(main.app) as client:
            response = client.post("/api/v1/webhooks/jellyfin", json={})
            self.assertEqual(response.status_code, 401)

    def test_health_endpoint_is_public_and_reports_are_authenticated(self):
        with TestClient(main.app) as client:
            health = client.get("/health")
            denied = client.get("/api/v1/reports/summary")
            allowed = client.get(
                "/api/v1/reports/summary",
                headers={"X-Allerflux-Key": "test-api-secret"},
            )
        self.assertEqual(health.status_code, 200)
        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)
        self.assertEqual(allowed.json()["snapshot_count"], 0)

    def test_diagnostics_requires_api_key_and_reports_database_state(self):
        with TestClient(main.app) as client:
            denied = client.get("/api/v1/diagnostics")
            allowed = client.get(
                "/api/v1/diagnostics",
                headers={"X-Allerflux-Key": "test-api-secret"},
            )
        self.assertEqual(denied.status_code, 401)
        self.assertEqual(allowed.status_code, 200)
        self.assertTrue(allowed.json()["database_connected"])

    def test_webhook_is_idempotent_and_summary_is_event_based(self):
        payload = {
            "NotificationType": "PlaybackStart",
            "UtcTimestamp": "2026-11-11T02:00:00Z",
            "UserId": "viewer-1",
            "NotificationUsername": "Viewer",
            "ItemId": "movie-1",
            "Name": "Example Movie",
            "ItemType": "Movie",
            "PlaybackPositionTicks": "0",
            "RunTimeTicks": "90000000000",
        }
        headers = {"X-Allerflux-Webhook-Key": "test-webhook-secret"}
        with TestClient(main.app) as client:
            first = client.post("/api/v1/webhooks/jellyfin", json=payload, headers=headers)
            duplicate = client.post("/api/v1/webhooks/jellyfin", json=payload, headers=headers)
            summary = client.get("/api/v1/events/summary?days=30", headers={"X-Allerflux-Key": "test-api-secret"})
        self.assertEqual(first.status_code, 200)
        self.assertFalse(first.json()["duplicate"])
        self.assertEqual(duplicate.status_code, 200)
        self.assertTrue(duplicate.json()["duplicate"])
        self.assertEqual(summary.status_code, 200)
        self.assertEqual(summary.json()["playback_starts"], 1)


if __name__ == "__main__":
    unittest.main()
