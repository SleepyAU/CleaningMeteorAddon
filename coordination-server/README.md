# Roof Moss Coordination API

This is the shared chunk coordinator used by `RoofMosser`. It uses only Python's standard library and SQLite.

## Run it

Generate a long random token and keep it out of Git:

```bash
export MOSS_API_TOKEN="replace-with-a-long-random-secret"
export MOSS_API_HOST="127.0.0.1"
export MOSS_API_PORT="8080"
export MOSS_API_DB="/var/lib/roof-moss/roof-moss.sqlite3"
python3 server.py
```

Put Caddy, nginx, or a Cloudflare Tunnel in front of `127.0.0.1:8080`, then use `https://mossapi.sleepyfemboy.dev` as the client's API URL. A tunnel or HTTPS reverse proxy is preferable to directly exposing the Python port. Keep Cloudflare caching disabled for `/v1/*`; responses also send `Cache-Control: no-store`.

The same process serves a public project dashboard at `/stats` (and redirects `/` there). It includes a completion leaderboard, but does not reveal active worker names or chunk coordinates. Its machine-readable counterpart is `/v1/stats`. Set `MOSS_API_STATS_PROJECT`, `MOSS_API_STATS_DIMENSION`, `MOSS_API_STATS_TOTAL_CHUNKS`, or `MOSS_API_STATS_TITLE` to customize the page; their defaults describe the 10,000 x 10,000 Overworld roof project.

`roof-moss-api.service.example` is a hardened systemd starting point. Copy the server to `/opt/roof-moss-api`, create a restricted `roof-moss` user, keep the database in `/var/lib/roof-moss`, and put the environment variables in `/etc/roof-moss-api.env`. If you port-forward instead of using a Cloudflare Tunnel, firewall the origin so only Cloudflare's published proxy address ranges can reach it; orange-cloud DNS alone does not prevent direct access to a known home IP.

Run the coordinator regression test with `python3 -m unittest -v test_server.py` from this directory.

Every client only needs the same API key. Roof Mosser fixes the project to `2b2t-spawn-roof`, uses the logged-in Minecraft username as its worker ID, and automatically renews a fixed 15-minute claim lease. A crash therefore frees a chunk without manual cleanup. API failures and expired leases pause placement rather than allowing duplicate work.

## Protocol

All `/v1/*` requests require `Authorization: Bearer <MOSS_API_TOKEN>`.

```text
POST   /v1/chunks/{project}/{dimension}/{x}/{z}/claim
PUT    /v1/chunks/{project}/{dimension}/{x}/{z}/complete
DELETE /v1/chunks/{project}/{dimension}/{x}/{z}/claim
GET    /v1/chunks/{project}/{dimension}/{x}/{z}
GET    /v1/stats
GET    /stats
GET    /health
```

The chunk routes require authentication. `/`, `/stats`, `/v1/stats`, and `/health` are intentionally public. Public stats include completed-chunk totals by contributor, but never active lease owners or coordinates.

Claim body:

```json
{"worker":"bot-a1b2c3d4","leaseSeconds":900}
```

Complete and release body:

```json
{"worker":"bot-a1b2c3d4"}
```

Status responses use `available`, `claimed`, or `complete`. A successful claim also returns the actual `leaseSeconds` accepted by the server so clients renew before a server-side cap. Claiming and completion are atomic SQLite transactions, repeated requests are safe, expired owners cannot publish completion, and another worker receives HTTP 409 while a live lease exists.
