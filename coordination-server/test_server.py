#!/usr/bin/env python3
"""Integration tests for the roof-moss coordinator using a real local HTTP server."""

from __future__ import annotations

import json
import tempfile
import threading
import time
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen

import server


class CoordinatorTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.temp_dir = tempfile.TemporaryDirectory()
        server.DB_PATH = Path(cls.temp_dir.name) / "test.sqlite3"
        server.TOKEN = "test-secret"
        server.MAX_LEASE_SECONDS = 60
        server.initialize()
        cls.httpd = server.Server(("127.0.0.1", 0), server.ApiHandler)
        cls.thread = threading.Thread(target=cls.httpd.serve_forever, daemon=True)
        cls.thread.start()
        cls.base = f"http://127.0.0.1:{cls.httpd.server_port}"

    @classmethod
    def tearDownClass(cls) -> None:
        cls.httpd.shutdown()
        cls.httpd.server_close()
        cls.thread.join(timeout=5)
        cls.temp_dir.cleanup()

    def setUp(self) -> None:
        with server.connect() as db:
            db.execute("DELETE FROM chunks")

    def request(self, method: str, path: str, body: dict | None = None, authorized: bool = True):
        headers = {"Accept": "application/json"}
        if authorized:
            headers["Authorization"] = "Bearer test-secret"
        data = None
        if body is not None:
            headers["Content-Type"] = "application/json"
            data = json.dumps(body).encode("utf-8")
        request = Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with urlopen(request, timeout=3) as response:
                return response.status, json.load(response)
        except HTTPError as error:
            with error:
                return error.code, json.load(error)

    def test_claim_expiry_reassignment_and_permanent_completion(self) -> None:
        path = "/v1/chunks/test/minecraft%3Aoverworld/4/-2"

        code, claim = self.request("POST", path + "/claim", {"worker": "bot-a", "leaseSeconds": 900})
        self.assertEqual(code, 200)
        self.assertEqual(claim["status"], "claimed")
        self.assertEqual(claim["leaseSeconds"], 60)

        code, conflict = self.request("POST", path + "/claim", {"worker": "bot-b", "leaseSeconds": 60})
        self.assertEqual(code, 409)
        self.assertEqual(conflict["owner"], "bot-a")

        with server.connect() as db:
            db.execute("UPDATE chunks SET lease_until=0")

        code, stale = self.request("PUT", path + "/complete", {"worker": "bot-a"})
        self.assertEqual(code, 409)
        self.assertEqual(stale["status"], "available")

        code, reclaimed = self.request("POST", path + "/claim", {"worker": "bot-b", "leaseSeconds": 60})
        self.assertEqual(code, 200)
        self.assertEqual(reclaimed["owner"], "bot-b")

        code, complete = self.request("PUT", path + "/complete", {"worker": "bot-b"})
        self.assertEqual(code, 200)
        self.assertEqual(complete["status"], "complete")

        code, permanent = self.request("POST", path + "/claim", {"worker": "bot-a", "leaseSeconds": 60})
        self.assertEqual(code, 200)
        self.assertEqual(permanent["status"], "complete")

        code, unauthorized = self.request("GET", path, authorized=False)
        self.assertEqual(code, 401)
        self.assertEqual(unauthorized["message"], "unauthorized")

    def test_public_stats_are_aggregated_without_exposing_locations(self) -> None:
        now = int(time.time())
        rows = [
            (server.STATS_PROJECT, server.STATS_DIMENSION, 1, 2, "complete", "<builder>", None, now, now),
            (server.STATS_PROJECT, server.STATS_DIMENSION, 12, -8, "claimed", "bot-live", now + 60, now, None),
            (server.STATS_PROJECT, server.STATS_DIMENSION, 99, 99, "claimed", "bot-expired", now - 1, now, None),
        ]
        with server.connect() as db:
            db.executemany(
                """
                INSERT INTO chunks(project, dimension, chunk_x, chunk_z, status, owner,
                                   lease_until, updated_at, completed_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                rows,
            )

        code, stats = self.request("GET", "/v1/stats", authorized=False)
        self.assertEqual(code, 200)
        self.assertEqual(stats["completedChunks"], 1)
        self.assertEqual(stats["activeWorkers"], 1)
        self.assertEqual(stats["activeClaims"], 1)
        self.assertEqual(stats["contributors"], 1)
        self.assertEqual(stats["leaderboard"], [{"name": "<builder>", "completedChunks": 1}])

        with urlopen(self.base + "/stats", timeout=3) as response:
            page = response.read().decode("utf-8")
        self.assertIn("Overall roof progress", page)
        self.assertIn("Coordination API online", page)
        self.assertIn("&lt;builder&gt;", page)
        self.assertNotIn("<builder>", page)
        self.assertNotIn("bot-live", page)
        self.assertNotIn("12,-8", page)


if __name__ == "__main__":
    unittest.main()
