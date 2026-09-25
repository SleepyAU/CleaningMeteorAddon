#!/usr/bin/env python3
"""Small authenticated chunk-lease API for SleepyAddon RoofMosser."""

from __future__ import annotations

import hmac
import html
import json
import os
import sqlite3
import time
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


HOST = os.environ.get("MOSS_API_HOST", "127.0.0.1")
PORT = int(os.environ.get("MOSS_API_PORT", "8080"))
DB_PATH = Path(os.environ.get("MOSS_API_DB", "roof-moss.sqlite3")).resolve()
TOKEN = os.environ.get("MOSS_API_TOKEN", "")
MAX_LEASE_SECONDS = max(60, int(os.environ.get("MOSS_API_MAX_LEASE_SECONDS", "3600")))
STATS_PROJECT = os.environ.get("MOSS_API_STATS_PROJECT", "2b2t-spawn-roof")
STATS_DIMENSION = os.environ.get("MOSS_API_STATS_DIMENSION", "minecraft:overworld")
STATS_TOTAL_CHUNKS = max(1, int(os.environ.get("MOSS_API_STATS_TOTAL_CHUNKS", "390625")))
STATS_TITLE = os.environ.get("MOSS_API_STATS_TITLE", "2b2t Spawn Roof")
MAX_BODY_BYTES = 16 * 1024


def connect() -> sqlite3.Connection:
    db = sqlite3.connect(DB_PATH, timeout=5.0, isolation_level=None)
    db.row_factory = sqlite3.Row
    db.execute("PRAGMA busy_timeout = 5000")
    db.execute("PRAGMA journal_mode = WAL")
    return db


def initialize() -> None:
    DB_PATH.parent.mkdir(parents=True, exist_ok=True)
    with connect() as db:
        db.execute(
            """
            CREATE TABLE IF NOT EXISTS chunks (
                project TEXT NOT NULL,
                dimension TEXT NOT NULL,
                chunk_x INTEGER NOT NULL,
                chunk_z INTEGER NOT NULL,
                status TEXT NOT NULL CHECK (status IN ('claimed', 'complete')),
                owner TEXT,
                lease_until INTEGER,
                updated_at INTEGER NOT NULL,
                completed_at INTEGER,
                PRIMARY KEY (project, dimension, chunk_x, chunk_z)
            ) WITHOUT ROWID
            """
        )
        db.execute(
            "CREATE INDEX IF NOT EXISTS chunks_status_idx "
            "ON chunks(project, dimension, status, lease_until)"
        )


def public_status(row: sqlite3.Row | None, now: int) -> dict:
    if row is None:
        return {"status": "available", "owner": None, "leaseUntil": None}
    if row["status"] == "complete":
        return {
            "status": "complete",
            "owner": row["owner"],
            "leaseUntil": None,
            "completedAt": row["completed_at"],
        }
    if row["lease_until"] is None or row["lease_until"] <= now:
        return {"status": "available", "owner": None, "leaseUntil": None}
    return {"status": "claimed", "owner": row["owner"], "leaseUntil": row["lease_until"]}


def aggregate_stats(now: int | None = None) -> dict:
    now = int(time.time()) if now is None else now
    with connect() as db:
        row = db.execute(
            """
            SELECT
                SUM(CASE WHEN status='complete' THEN 1 ELSE 0 END) AS completed,
                COUNT(DISTINCT CASE WHEN status='complete' THEN owner END) AS contributors,
                SUM(CASE WHEN status='claimed' AND lease_until>? THEN 1 ELSE 0 END) AS active_claims,
                COUNT(DISTINCT CASE WHEN status='claimed' AND lease_until>? THEN owner END) AS active_workers,
                SUM(CASE WHEN status='complete' AND completed_at>=? THEN 1 ELSE 0 END) AS completed_24h,
                SUM(CASE WHEN status='complete' AND completed_at>=? THEN 1 ELSE 0 END) AS completed_7d,
                MAX(CASE WHEN status='complete' THEN completed_at END) AS last_completed_at
            FROM chunks
            WHERE project=? AND dimension=?
            """,
            (now, now, now - 86_400, now - 604_800, STATS_PROJECT, STATS_DIMENSION),
        ).fetchone()
        leaderboard_rows = db.execute(
            """
            SELECT owner, COUNT(*) AS completed_chunks
            FROM chunks
            WHERE project=? AND dimension=? AND status='complete'
              AND owner IS NOT NULL AND TRIM(owner)<>''
            GROUP BY owner
            ORDER BY completed_chunks DESC, MAX(completed_at) DESC, owner COLLATE NOCASE
            """,
            (STATS_PROJECT, STATS_DIMENSION),
        ).fetchall()

    completed = int(row["completed"] or 0)
    total = STATS_TOTAL_CHUNKS
    return {
        "status": "ok",
        "project": STATS_PROJECT,
        "dimension": STATS_DIMENSION,
        "completedChunks": completed,
        "remainingChunks": max(0, total - completed),
        "totalChunks": total,
        "progressPercent": round(min(100.0, completed * 100.0 / total), 6),
        "contributors": int(row["contributors"] or 0),
        "activeWorkers": int(row["active_workers"] or 0),
        "activeClaims": int(row["active_claims"] or 0),
        "completedLast24Hours": int(row["completed_24h"] or 0),
        "completedLast7Days": int(row["completed_7d"] or 0),
        "lastCompletedAt": row["last_completed_at"],
        "leaderboard": [
            {"name": contributor["owner"], "completedChunks": contributor["completed_chunks"]}
            for contributor in leaderboard_rows
        ],
        "updatedAt": now,
    }


def utc_time(timestamp: int | None, empty: str = "No completions yet") -> str:
    if timestamp is None:
        return empty
    return datetime.fromtimestamp(timestamp, timezone.utc).strftime("%d %b %Y, %H:%M UTC")


def stats_page(stats: dict) -> str:
    title = html.escape(STATS_TITLE)
    completed = stats["completedChunks"]
    remaining = stats["remainingChunks"]
    total = stats["totalChunks"]
    progress = stats["progressPercent"]
    progress_label = f"{progress:.4f}%"
    progress_width = max(0.0, min(100.0, progress))
    fill_class = " has-progress" if completed else ""
    leaderboard = stats["leaderboard"]
    if leaderboard:
        leaderboard_rows = "".join(
            f'<div class="contributor"><span class="rank">#{rank}</span>'
            f'<span class="worker">{html.escape(entry["name"])}</span>'
            f'<strong>{entry["completedChunks"]:,}<small> chunks</small></strong></div>'
            for rank, entry in enumerate(leaderboard, 1)
        )
    else:
        leaderboard_rows = '<div class="empty">No completed chunks yet.</div>'
    return f"""<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <meta name="theme-color" content="#07120c">
  <meta http-equiv="refresh" content="30">
  <title>{title} — Live Progress</title>
  <style>
    :root {{ color-scheme: dark; font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif; }}
    * {{ box-sizing: border-box; }}
    body {{
      margin: 0; min-height: 100vh; color: #edf8f0;
      background:
        radial-gradient(circle at 18% 8%, rgba(50, 190, 95, .16), transparent 35rem),
        radial-gradient(circle at 92% 86%, rgba(120, 255, 145, .08), transparent 30rem),
        #050a07;
    }}
    body::before {{
      content: ""; position: fixed; inset: 0; pointer-events: none; opacity: .18;
      background-image: linear-gradient(rgba(255,255,255,.025) 1px, transparent 1px), linear-gradient(90deg, rgba(255,255,255,.025) 1px, transparent 1px);
      background-size: 32px 32px; mask-image: linear-gradient(to bottom, black, transparent 80%);
    }}
    main {{ width: min(980px, calc(100% - 32px)); margin: 0 auto; padding: clamp(42px, 8vw, 88px) 0 48px; position: relative; }}
    header {{ display: flex; align-items: center; justify-content: space-between; gap: 24px; margin-bottom: 30px; }}
    .identity {{ display: flex; align-items: center; gap: 16px; }}
    .mark {{
      width: 52px; height: 52px; display: grid; place-items: center; border-radius: 15px; font-size: 27px;
      background: linear-gradient(145deg, #45d16f, #167b39); box-shadow: 0 12px 34px rgba(35, 203, 87, .22), inset 0 1px rgba(255,255,255,.35);
    }}
    h1 {{ margin: 0; font-size: clamp(1.45rem, 4vw, 2.15rem); letter-spacing: -.045em; }}
    .subtitle {{ color: #8fa99a; margin-top: 5px; font-size: .92rem; }}
    .online {{ display: inline-flex; align-items: center; gap: 8px; color: #a8c4b1; font-size: .86rem; white-space: nowrap; }}
    .dot {{ width: 9px; height: 9px; border-radius: 50%; background: #55ed7e; box-shadow: 0 0 0 5px rgba(85,237,126,.1), 0 0 18px #55ed7e; }}
    .panel {{
      border: 1px solid rgba(155, 220, 171, .13); border-radius: 24px; padding: clamp(20px, 4vw, 32px);
      background: linear-gradient(145deg, rgba(20, 34, 25, .92), rgba(9, 18, 12, .92));
      box-shadow: 0 28px 80px rgba(0,0,0,.34), inset 0 1px rgba(255,255,255,.035); backdrop-filter: blur(16px);
    }}
    .metrics {{ display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }}
    .metric {{ padding: 18px; border-radius: 16px; background: rgba(255,255,255,.035); border: 1px solid rgba(255,255,255,.045); }}
    .metric.primary {{ background: linear-gradient(145deg, rgba(63, 215, 108, .15), rgba(63, 215, 108, .045)); border-color: rgba(76, 226, 119, .18); }}
    .label {{ color: #89a293; font-size: .76rem; text-transform: uppercase; letter-spacing: .105em; font-weight: 700; }}
    .value {{ margin-top: 8px; font-size: clamp(1.45rem, 3.4vw, 2.1rem); font-weight: 760; letter-spacing: -.045em; font-variant-numeric: tabular-nums; }}
    .value small {{ font-size: .72rem; color: #8fa99a; font-weight: 600; letter-spacing: 0; }}
    .progress-head {{ display: flex; align-items: end; justify-content: space-between; gap: 16px; margin: 30px 2px 11px; }}
    .progress-head strong {{ font-size: 1rem; }}
    .progress-head span {{ color: #72e795; font-variant-numeric: tabular-nums; font-weight: 750; }}
    .track {{ height: 13px; overflow: hidden; border-radius: 999px; background: #030805; border: 1px solid rgba(255,255,255,.06); box-shadow: inset 0 2px 5px rgba(0,0,0,.5); }}
    .fill {{ height: 100%; width: {progress_width:.6f}%; border-radius: inherit; background: linear-gradient(90deg, #23974a, #66f08d); box-shadow: 0 0 22px rgba(89, 238, 130, .55); }}
    .fill.has-progress {{ min-width: 7px; }}
    .details {{ margin-top: 20px; display: grid; grid-template-columns: 1fr 1fr; gap: 12px; color: #91aa9a; font-size: .85rem; }}
    .detail {{ padding: 14px 16px; border-radius: 13px; background: rgba(0,0,0,.16); display: flex; justify-content: space-between; gap: 12px; }}
    .detail strong {{ color: #d6e8db; font-weight: 650; text-align: right; }}
    .leaderboard {{ margin-top: 22px; padding-top: 22px; border-top: 1px solid rgba(155, 220, 171, .1); }}
    .leaderboard-head {{ display: flex; align-items: baseline; justify-content: space-between; gap: 16px; margin: 0 2px 12px; }}
    .leaderboard-head h2 {{ margin: 0; font-size: 1rem; letter-spacing: -.015em; }}
    .leaderboard-head span {{ color: #708779; font-size: .77rem; }}
    .contributors {{ display: grid; gap: 8px; max-height: 350px; overflow-y: auto; scrollbar-color: #2b7040 transparent; }}
    .contributor {{ display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 10px; min-height: 48px; padding: 9px 14px; border-radius: 13px; background: rgba(0,0,0,.18); border: 1px solid rgba(255,255,255,.035); }}
    .rank {{ color: #5d7967; font-size: .78rem; font-variant-numeric: tabular-nums; }}
    .worker {{ overflow: hidden; color: #dcebe0; font-weight: 670; text-overflow: ellipsis; white-space: nowrap; }}
    .contributor strong {{ color: #70e994; font-size: .95rem; font-variant-numeric: tabular-nums; }}
    .contributor strong small {{ margin-left: 5px; color: #708779; font-size: .7rem; font-weight: 600; }}
    .empty {{ padding: 18px; border-radius: 13px; color: #708779; text-align: center; background: rgba(0,0,0,.15); }}
    footer {{ display: flex; justify-content: space-between; gap: 20px; margin: 17px 4px 0; color: #587062; font-size: .75rem; }}
    @media (max-width: 760px) {{ .metrics {{ grid-template-columns: 1fr 1fr; }} .details {{ grid-template-columns: 1fr; }} }}
    @media (max-width: 480px) {{ header {{ align-items: flex-start; flex-direction: column; }} .online {{ margin-left: 68px; margin-top: -18px; }} .metrics {{ grid-template-columns: 1fr; }} footer {{ flex-direction: column; gap: 5px; }} }}
  </style>
</head>
<body>
  <main>
    <header>
      <div class="identity">
        <div class="mark" aria-hidden="true">✦</div>
        <div><h1>{title}</h1><div class="subtitle">Shared moss coordination progress</div></div>
      </div>
      <div class="online"><span class="dot"></span> Coordination API online</div>
    </header>
    <section class="panel" aria-label="Project statistics">
      <div class="metrics">
        <div class="metric primary"><div class="label">Completed</div><div class="value">{completed:,}</div></div>
        <div class="metric"><div class="label">Remaining</div><div class="value">{remaining:,}</div></div>
        <div class="metric"><div class="label">Active bots</div><div class="value">{stats['activeWorkers']:,} <small>{stats['activeClaims']:,} leases</small></div></div>
        <div class="metric"><div class="label">Last 24 hours</div><div class="value">+{stats['completedLast24Hours']:,}</div></div>
      </div>
      <div class="progress-head"><strong>Overall roof progress</strong><span>{progress_label}</span></div>
      <div class="track" role="progressbar" aria-label="Overall completion" aria-valuemin="0" aria-valuemax="100" aria-valuenow="{progress:.6f}"><div class="fill{fill_class}"></div></div>
      <div class="details">
        <div class="detail"><span>Total project size</span><strong>{total:,} chunks</strong></div>
        <div class="detail"><span>Completed this week</span><strong>{stats['completedLast7Days']:,} chunks</strong></div>
        <div class="detail"><span>Contributors</span><strong>{stats['contributors']:,}</strong></div>
        <div class="detail"><span>Last completion</span><strong>{utc_time(stats['lastCompletedAt'])}</strong></div>
      </div>
      <div class="leaderboard">
        <div class="leaderboard-head"><h2>Contributors</h2><span>Server-confirmed completions</span></div>
        <div class="contributors">{leaderboard_rows}</div>
      </div>
    </section>
    <footer><span>Only server-confirmed chunks are counted.</span><span>Updated {utc_time(stats['updatedAt'])} · refreshes every 30 seconds</span></footer>
  </main>
</body>
</html>"""


class ApiHandler(BaseHTTPRequestHandler):
    server_version = "SleepyMossAPI/1"

    def setup(self) -> None:
        super().setup()
        self.connection.settimeout(15)

    def do_GET(self) -> None:  # noqa: N802
        parsed = urlparse(self.path)
        if parsed.path == "/":
            self.send_response(302)
            self.send_header("Location", "/stats")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        if parsed.path in ("/stats", "/stats/"):
            self.send_html(200, stats_page(aggregate_stats()))
            return
        if parsed.path in ("/v1/stats", "/v1/stats/"):
            self.send_json(200, aggregate_stats())
            return
        if parsed.path == "/health":
            self.send_json(200, {"status": "ok", "time": int(time.time())})
            return
        if not self.authorized():
            return
        route = self.parse_chunk_route(parsed.path)
        if route is None:
            self.send_json(404, {"status": "error", "message": "not found"})
            return
        project, dimension, chunk_x, chunk_z, action = route
        if action is not None:
            self.send_json(405, {"status": "error", "message": "method not allowed"})
            return
        now = int(time.time())
        with connect() as db:
            row = db.execute(
                "SELECT * FROM chunks WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?",
                (project, dimension, chunk_x, chunk_z),
            ).fetchone()
        self.send_json(200, public_status(row, now))

    def do_POST(self) -> None:  # noqa: N802
        if not self.authorized():
            return
        route = self.parse_chunk_route(urlparse(self.path).path)
        if route is None or route[4] != "claim":
            self.send_json(404, {"status": "error", "message": "not found"})
            return
        project, dimension, chunk_x, chunk_z, _ = route
        body = self.read_json()
        if body is None:
            return
        worker = self.valid_worker(body)
        if worker is None:
            return
        try:
            requested = int(body.get("leaseSeconds", 900))
        except (TypeError, ValueError):
            self.send_json(400, {"status": "error", "message": "leaseSeconds must be an integer"})
            return
        lease_seconds = min(MAX_LEASE_SECONDS, max(60, requested))
        now = int(time.time())
        lease_until = now + lease_seconds

        with connect() as db:
            try:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute(
                    "SELECT * FROM chunks WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?",
                    (project, dimension, chunk_x, chunk_z),
                ).fetchone()
                visible = public_status(row, now)
                if visible["status"] == "complete":
                    db.execute("COMMIT")
                    self.send_json(200, visible)
                    return
                if visible["status"] == "claimed" and visible["owner"] != worker:
                    db.execute("COMMIT")
                    self.send_json(409, visible | {"message": "chunk is already claimed"})
                    return
                db.execute(
                    """
                    INSERT INTO chunks(project, dimension, chunk_x, chunk_z, status, owner,
                                       lease_until, updated_at, completed_at)
                    VALUES (?, ?, ?, ?, 'claimed', ?, ?, ?, NULL)
                    ON CONFLICT(project, dimension, chunk_x, chunk_z) DO UPDATE SET
                        status='claimed', owner=excluded.owner, lease_until=excluded.lease_until,
                        updated_at=excluded.updated_at, completed_at=NULL
                    """,
                    (project, dimension, chunk_x, chunk_z, worker, lease_until, now),
                )
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        self.send_json(
            200,
            {
                "status": "claimed",
                "owner": worker,
                "leaseUntil": lease_until,
                "leaseSeconds": lease_seconds,
            },
        )

    def do_PUT(self) -> None:  # noqa: N802
        if not self.authorized():
            return
        route = self.parse_chunk_route(urlparse(self.path).path)
        if route is None or route[4] != "complete":
            self.send_json(404, {"status": "error", "message": "not found"})
            return
        project, dimension, chunk_x, chunk_z, _ = route
        body = self.read_json()
        if body is None:
            return
        worker = self.valid_worker(body)
        if worker is None:
            return
        now = int(time.time())

        with connect() as db:
            try:
                db.execute("BEGIN IMMEDIATE")
                row = db.execute(
                    "SELECT * FROM chunks WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?",
                    (project, dimension, chunk_x, chunk_z),
                ).fetchone()
                if row is not None and row["status"] == "complete":
                    db.execute("COMMIT")
                    self.send_json(200, public_status(row, now))
                    return
                if (
                    row is None
                    or row["status"] != "claimed"
                    or row["owner"] != worker
                    or row["lease_until"] is None
                    or row["lease_until"] <= now
                ):
                    visible = public_status(row, now)
                    db.execute("COMMIT")
                    self.send_json(409, visible | {"message": "worker does not own this claim"})
                    return
                db.execute(
                    """
                    UPDATE chunks SET status='complete', owner=?, lease_until=NULL,
                                      updated_at=?, completed_at=?
                    WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?
                    """,
                    (worker, now, now, project, dimension, chunk_x, chunk_z),
                )
                db.execute("COMMIT")
            except Exception:
                db.execute("ROLLBACK")
                raise
        self.send_json(200, {"status": "complete", "owner": worker, "leaseUntil": None, "completedAt": now})

    def do_DELETE(self) -> None:  # noqa: N802
        if not self.authorized():
            return
        route = self.parse_chunk_route(urlparse(self.path).path)
        if route is None or route[4] != "claim":
            self.send_json(404, {"status": "error", "message": "not found"})
            return
        project, dimension, chunk_x, chunk_z, _ = route
        body = self.read_json()
        if body is None:
            return
        worker = self.valid_worker(body)
        if worker is None:
            return
        now = int(time.time())
        with connect() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute(
                "SELECT * FROM chunks WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?",
                (project, dimension, chunk_x, chunk_z),
            ).fetchone()
            if row is not None and row["status"] == "claimed" and row["owner"] == worker:
                db.execute(
                    "DELETE FROM chunks WHERE project=? AND dimension=? AND chunk_x=? AND chunk_z=?",
                    (project, dimension, chunk_x, chunk_z),
                )
            db.execute("COMMIT")
        self.send_json(200, {"status": "available", "owner": None, "leaseUntil": None})

    def parse_chunk_route(self, path: str):
        parts = [unquote(part) for part in path.strip("/").split("/")]
        if len(parts) not in (6, 7) or parts[:2] != ["v1", "chunks"]:
            return None
        try:
            chunk_x = int(parts[4])
            chunk_z = int(parts[5])
        except ValueError:
            return None
        action = parts[6] if len(parts) == 7 else None
        if action not in (None, "claim", "complete"):
            return None
        project, dimension = parts[2], parts[3]
        if not project or not dimension or len(project) > 128 or len(dimension) > 128:
            return None
        return project, dimension, chunk_x, chunk_z, action

    def authorized(self) -> bool:
        supplied = self.headers.get("Authorization", "")
        expected = "Bearer " + TOKEN
        if TOKEN and hmac.compare_digest(supplied, expected):
            return True
        self.send_json(401, {"status": "error", "message": "unauthorized"})
        return False

    def read_json(self) -> dict | None:
        try:
            length = int(self.headers.get("Content-Length", "0"))
        except ValueError:
            length = -1
        if length < 0 or length > MAX_BODY_BYTES:
            self.send_json(413, {"status": "error", "message": "request body too large"})
            return None
        try:
            value = json.loads(self.rfile.read(length) or b"{}")
            if not isinstance(value, dict):
                raise ValueError
            return value
        except (json.JSONDecodeError, UnicodeDecodeError, ValueError):
            self.send_json(400, {"status": "error", "message": "invalid JSON object"})
            return None

    def valid_worker(self, body: dict) -> str | None:
        worker = body.get("worker")
        if not isinstance(worker, str) or not worker.strip() or len(worker.strip()) > 128:
            self.send_json(400, {"status": "error", "message": "worker must be 1-128 characters"})
            return None
        return worker.strip()

    def send_json(self, status_code: int, value: dict) -> None:
        payload = json.dumps(value, separators=(",", ":")).encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.end_headers()
        self.wfile.write(payload)

    def send_html(self, status_code: int, value: str) -> None:
        payload = value.encode("utf-8")
        self.send_response(status_code)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header(
            "Content-Security-Policy",
            "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'",
        )
        self.end_headers()
        self.wfile.write(payload)

    def log_message(self, fmt: str, *args) -> None:
        print(f"{self.log_date_time_string()} {self.client_address[0]} {fmt % args}", flush=True)


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> None:
    if not TOKEN:
        raise SystemExit("MOSS_API_TOKEN is required; refusing to start an unauthenticated public API")
    initialize()
    server = Server((HOST, PORT), ApiHandler)
    print(f"Roof moss API listening on http://{HOST}:{PORT}; database={DB_PATH}", flush=True)
    try:
        server.serve_forever(poll_interval=0.5)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
