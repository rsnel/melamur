#!/bin/sh
# Test the aggregator WITHOUT Apache. Two modes:
#
#   ./test_aggregator.sh db        # real MySQL, plain HTTP, no client cert
#   ./test_aggregator.sh tls       # mutual TLS using server/certs (needs make_certs.sh)
#
# 'db' mode needs a MySQL you can reach; set MELAMUR_DB_* in the environment
# (defaults: 127.0.0.1 / melamur / melamur / ""). It creates the schema, starts
# the standalone server, posts two sample fixes, and reads them back.
set -e
cd "$(dirname "$0")"
MODE=${1:-db}
PORT=8080

if [ "$MODE" = tls ]; then
    [ -f certs/client.pem ] || sh make_certs.sh
    python3 melamur_aggregator.py --init-db || echo "(skipping schema; DB not reachable?)"
    python3 melamur_aggregator.py --serve --tls --port $PORT & SRV=$!
    trap 'kill $SRV 2>/dev/null' EXIT
    sleep 1
    echo "--- without client cert (expect failure) ---"
    curl -k -s -o /dev/null -w '%{http_code}\n' https://127.0.0.1:$PORT/aggregator/ -d '{}' || true
    echo "--- with client cert (expect 204) ---"
    curl -k -s -w '%{http_code}\n' --cert certs/client.pem --key certs/client.key \
        https://127.0.0.1:$PORT/aggregator/ -H 'Content-Type: application/json' \
        -d '{"v":1,"trip":"test-trip","fixes":[{"t":1,"lat":35.9,"lon":14.5,"acc":5,"spd":8,"brg":90,"route":"51"}]}'
else
    export MELAMUR_REQUIRE_CLIENT_CERT=0
    python3 melamur_aggregator.py --init-db
    python3 melamur_aggregator.py --serve --port $PORT & SRV=$!
    trap 'kill $SRV 2>/dev/null' EXIT
    sleep 1
    echo "--- POST two fixes (expect 204) ---"
    curl -s -w '%{http_code}\n' http://127.0.0.1:$PORT/aggregator/ -H 'Content-Type: application/json' \
        -d '{"v":1,"trip":"test-trip","fixes":[
            {"t":1700000000000,"lat":35.90,"lon":14.50,"acc":5,"spd":8,"brg":90,"route":"51"},
            {"t":1700000002000,"lat":35.901,"lon":14.501,"acc":4,"spd":9,"brg":92,"route":"51"}]}'
    echo "--- rows in MySQL ---"
    python3 - <<'PY'
import os, pymysql
c=pymysql.connect(host=os.environ.get("MELAMUR_DB_HOST","127.0.0.1"),
    user=os.environ.get("MELAMUR_DB_USER","melamur"),
    password=os.environ.get("MELAMUR_DB_PASS",""),
    database=os.environ.get("MELAMUR_DB_NAME","melamur"))
with c.cursor() as cur:
    cur.execute("SELECT trip_id,client,lat,lon,route_id FROM fixes ORDER BY id DESC LIMIT 5")
    for r in cur.fetchall(): print(r)
PY
fi
