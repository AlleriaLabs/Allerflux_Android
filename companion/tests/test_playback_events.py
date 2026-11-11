import unittest
from companion.app.events import normalize_playback_event


class PlaybackEventNormalizationTests(unittest.TestCase):
    def setUp(self):
        self.payload = {
            "NotificationType": "PlaybackStart",
            "UtcTimestamp": "2026-11-11T02:00:00Z",
            "UserId": "user-123",
            "NotificationUsername": "Viewer",
            "ItemId": "item-456",
            "Name": "Example Movie",
            "ItemType": "Movie",
            "PlaybackPositionTicks": "0",
            "RunTimeTicks": "72000000000",
            "PlayMethod": "DirectPlay",
            "DeviceName": "Living Room",
            "ClientName": "Jellyfin Android TV",
        }

    def test_normalizes_start_event_and_ticks(self):
        event = normalize_playback_event(self.payload)
        self.assertEqual(event["event_type"], "PlaybackStart")
        self.assertEqual(event["user_id"], "user-123")
        self.assertEqual(event["item_id"], "item-456")
        self.assertEqual(event["runtime_ticks"], 72000000000)
        self.assertEqual(event["play_method"], "DirectPlay")
        self.assertEqual(event["occurred_at"].isoformat(), "2026-11-11T02:00:00+00:00")

    def test_same_payload_has_stable_deduplication_key(self):
        first = normalize_playback_event(self.payload)
        second = normalize_playback_event(dict(self.payload))
        self.assertEqual(first["event_key"], second["event_key"])

    def test_different_event_timestamp_has_different_key(self):
        first = normalize_playback_event(self.payload)
        changed = dict(self.payload, UtcTimestamp="2026-11-11T02:00:10Z")
        second = normalize_playback_event(changed)
        self.assertNotEqual(first["event_key"], second["event_key"])

    def test_stop_completion_is_parsed(self):
        payload = dict(self.payload, NotificationType="PlaybackStop", PlayedToCompletion="true")
        event = normalize_playback_event(payload)
        self.assertIs(event["played_to_completion"], True)

    def test_rejects_unsupported_or_incomplete_events(self):
        with self.assertRaises(ValueError):
            normalize_playback_event(dict(self.payload, NotificationType="ItemAdded"))
        with self.assertRaises(ValueError):
            normalize_playback_event(dict(self.payload, UserId=""))
        with self.assertRaises(ValueError):
            normalize_playback_event(dict(self.payload, UtcTimestamp="invalid"))


if __name__ == "__main__":
    unittest.main()
