import os
import sqlite3
import tempfile
import unittest

from fastapi import HTTPException

import companion.app.main as main


class DatabaseBackupTests(unittest.TestCase):
    def setUp(self):
        self.original_url = main.settings.database_url
        self.temp_dir = tempfile.TemporaryDirectory()
        self.database_path = os.path.join(self.temp_dir.name, "allerflux.db")
        connection = sqlite3.connect(self.database_path)
        connection.execute("CREATE TABLE backup_fixture (value TEXT)")
        connection.execute("INSERT INTO backup_fixture(value) VALUES ('preserved')")
        connection.commit()
        connection.close()
        main.settings.database_url = "sqlite:///" + self.database_path

    def tearDown(self):
        main.settings.database_url = self.original_url
        self.temp_dir.cleanup()

    def test_backup_is_a_consistent_sqlite_copy(self):
        response = main.download_database_backup()
        backup_path = response.path
        try:
            connection = sqlite3.connect(backup_path)
            value = connection.execute("SELECT value FROM backup_fixture").fetchone()[0]
            connection.close()
            self.assertEqual(value, "preserved")
            self.assertTrue(os.path.getsize(backup_path) > 0)
        finally:
            if os.path.exists(backup_path):
                os.unlink(backup_path)

    def test_non_sqlite_database_backup_is_rejected(self):
        main.settings.database_url = "postgresql://example.invalid/database"
        with self.assertRaises(HTTPException) as error:
            main.download_database_backup()
        self.assertEqual(error.exception.status_code, 501)


if __name__ == "__main__":
    unittest.main()
