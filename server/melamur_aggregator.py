#!/usr/bin/env python3
"""MelaMur aggregator — receives on-board GPS uploads from the app and stores
them in MySQL.

It is a WSGI application so it runs unchanged:
  * behind Apache via mod_wsgi (production, see melamur-apache.conf), and
  * standalone via Python's built-in wsgiref server (for testing, `--serve`).

The app authenticates with a client TLS certificate. TLS itself is terminated
by Apache (or by the test harness); this app reads the verified client
certificate from the WSGI environment (SSL_CLIENT_* set by mod_ssl) and refuses
requests without a verified client cert. It never trusts a client-supplied
header for identity.

Endpoint:  POST /aggregator/   (JSON body, see app/net/AggregatorClient.kt)
  { "v":1, "trip":"<uuid>", "fixes":[
      {"t":<ms>,"lat":..,"lon":..,"acc":..,"spd":..,"brg":..|null,"route":..|null}, ... ] }
Responds 204 on success.

Config via environment variables (see melamur-apache.conf for SetEnv):
  MELAMUR_DB_HOST  (default 127.0.0.1)
  MELAMUR_DB_NAME  (default melamur)
  MELAMUR_DB_USER  (default melamur)
  MELAMUR_DB_PASS  (default "")
  MELAMUR_REQUIRE_CLIENT_CERT  (default "1"; set "0" only for local testing)
"""
import json
import os
import sys

try:
    import pymysql  # pip install PyMySQL
except Exception:  # pragma: no cover - import guard for clearer error
    pymysql = None

MAX_BODY = 1 << 20          # 1 MiB
MAX_FIXES = 500             # per request

SCHEMA = """
CREATE TABLE IF NOT EXISTS fixes (
  id        BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  trip_id   CHAR(36)        NOT NULL,
  client    VARCHAR(255)    NOT NULL,          -- client cert subject CN
  t_ms      BIGINT          NOT NULL,          -- device timestamp (ms since epoch)
  rx_ms     BIGINT          NOT NULL,          -- server receive time (ms since epoch)
  lat       DOUBLE          NOT NULL,
  lon       DOUBLE          NOT NULL,
  acc       FLOAT           NULL,
  speed     FLOAT           NULL,
  bearing   FLOAT           NULL,
  route_id  VARCHAR(64)     NULL,
  PRIMARY KEY (id),
  KEY idx_trip (trip_id),
  KEY idx_rx (rx_ms),
  KEY idx_route (route_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
"""


def db_connect():
    if pymysql is None:
        raise RuntimeError("PyMySQL not installed; run: pip install PyMySQL")
    return pymysql.connect(
        host=os.environ.get("MELAMUR_DB_HOST", "127.0.0.1"),
        user=os.environ.get("MELAMUR_DB_USER", "melamur"),
        password=os.environ.get("MELAMUR_DB_PASS", ""),
        database=os.environ.get("MELAMUR_DB_NAME", "melamur"),
        autocommit=True,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.Cursor,
    )


def init_db():
    conn = db_connect()
    with conn.cursor() as cur:
        cur.execute(SCHEMA)
    conn.close()


def _require_cert() -> bool:
    return os.environ.get("MELAMUR_REQUIRE_CLIENT_CERT", "1") == "1"


def _client_id(environ) -> str | None:
    """Verified client identity, or None. mod_ssl sets SSL_CLIENT_VERIFY=SUCCESS
    and SSL_CLIENT_S_DN_CN for a client cert it has validated against the CA."""
    if not _require_cert():
        return environ.get("SSL_CLIENT_S_DN_CN") or "test-client"
    if environ.get("SSL_CLIENT_VERIFY") != "SUCCESS":
        return None
    return environ.get("SSL_CLIENT_S_DN_CN") or environ.get("SSL_CLIENT_S_DN") or "unknown"


def _store(trip_id, client, fixes):
    import time
    rx = int(time.time() * 1000)
    rows = []
    for f in fixes:
        rows.append((
            str(trip_id)[:36], client[:255], int(f["t"]), rx,
            float(f["lat"]), float(f["lon"]),
            _num(f.get("acc")), _num(f.get("spd")), _num(f.get("brg")),
            (str(f["route"])[:64] if f.get("route") not in (None, "") else None),
        ))
    conn = db_connect()
    with conn.cursor() as cur:
        cur.executemany(
            "INSERT INTO fixes (trip_id,client,t_ms,rx_ms,lat,lon,acc,speed,bearing,route_id) "
            "VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)", rows)
    conn.close()
    return len(rows)


def _num(v):
    try:
        return None if v is None else float(v)
    except (TypeError, ValueError):
        return None


def application(environ, start_response):
    """WSGI entry point (mod_wsgi looks for `application`)."""
    def reply(status, body=b"", ctype="text/plain"):
        start_response(status, [("Content-Type", ctype), ("Content-Length", str(len(body)))])
        return [body]

    path = environ.get("PATH_INFO", "").rstrip("/")
    if environ.get("REQUEST_METHOD") != "POST" or path not in ("/aggregator", ""):
        return reply("404 Not Found")

    client = _client_id(environ)
    if client is None:
        return reply("403 Forbidden", b"client certificate required")

    try:
        length = int(environ.get("CONTENT_LENGTH") or 0)
    except ValueError:
        length = 0
    if length <= 0 or length > MAX_BODY:
        return reply("413 Payload Too Large")

    try:
        body = environ["wsgi.input"].read(length)
        data = json.loads(body)
        fixes = data["fixes"]
        trip = data.get("trip", "")
        if not isinstance(fixes, list) or len(fixes) > MAX_FIXES:
            raise ValueError("bad fixes")
    except Exception:
        return reply("400 Bad Request", b"invalid payload")

    try:
        n = _store(trip, client, fixes)
    except Exception as e:  # don't leak internals to clients
        sys.stderr.write(f"[melamur] store failed: {e}\n")
        return reply("500 Internal Server Error", b"storage error")

    # TODO fusion: combine stored fixes with stops/schedules into a live map.
    sys.stderr.write(f"[melamur] stored {n} fixes trip={trip} client={client}\n")
    return reply("204 No Content")


# --------------------------------------------------------------------------
# CLI: init the schema, or run a standalone test server (no Apache needed).
def _main(argv):
    import argparse
    ap = argparse.ArgumentParser(description="MelaMur aggregator")
    ap.add_argument("--init-db", action="store_true", help="create the MySQL schema and exit")
    ap.add_argument("--serve", action="store_true", help="run a standalone WSGI server for testing")
    ap.add_argument("--port", type=int, default=8080)
    ap.add_argument("--tls", action="store_true",
                    help="with --serve: terminate HTTPS and require a client cert from server/certs")
    a = ap.parse_args(argv)

    if a.init_db:
        init_db(); print("schema ready"); return

    if a.serve:
        from wsgiref.simple_server import make_server
        httpd = make_server("127.0.0.1", a.port, application)
        if a.tls:
            import ssl
            here = os.path.join(os.path.dirname(os.path.abspath(__file__)), "certs")
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(os.path.join(here, "server.pem"), os.path.join(here, "server.key"))
            ctx.load_verify_locations(os.path.join(here, "client-ca.pem"))
            ctx.verify_mode = ssl.CERT_REQUIRED
            httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
            # make the verified client CN visible to the app, like mod_ssl does
            _wrap_tls_env(httpd)
            print(f"https://127.0.0.1:{a.port}/aggregator/ (client cert required)")
        else:
            os.environ.setdefault("MELAMUR_REQUIRE_CLIENT_CERT", "0")
            print(f"http://127.0.0.1:{a.port}/aggregator/ (no client cert; testing only)")
        httpd.serve_forever()
        return

    ap.print_help()


def _wrap_tls_env(httpd):
    """Populate SSL_CLIENT_* in the WSGI environ from the peer certificate, so
    the standalone --tls server behaves like Apache mod_ssl for testing."""
    base = httpd.RequestHandlerClass

    class Handler(base):
        def get_environ(self):
            env = super().get_environ()
            try:
                cert = self.connection.getpeercert()
                cn = ""
                for rdn in (cert or {}).get("subject", ()):
                    for k, v in rdn:
                        if k == "commonName":
                            cn = v
                env["SSL_CLIENT_VERIFY"] = "SUCCESS" if cert else "NONE"
                env["SSL_CLIENT_S_DN_CN"] = cn
            except Exception:
                env["SSL_CLIENT_VERIFY"] = "NONE"
            return env
    httpd.RequestHandlerClass = Handler


if __name__ == "__main__":
    _main(sys.argv[1:])
