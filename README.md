# MelaMur

Alternative Malta bus app (Android, Kotlin, no Google Play Services).

## What works

- **Offline map of Malta** from the Geofabrik OSM extract (`tools/convert_gpkg.py`
  → `app/src/main/assets/map.bin`), dark red/black theme, pan, pinch zoom,
  double-tap zoom.
- **Centre on me**: the ◎ button enters follow mode. A pinch near the screen
  centre zooms around your position without losing it; panning or pinching
  elsewhere leaves follow mode.
- **Street and place names** from the OSM extract (`assets/labels.json`): town
  and village names scaled by importance, street names drawn along the road
  when zoomed in, with simple collision avoidance so they don't overlap.
- **GDPR consent** on first start (Privacy button to review or withdraw). Without
  consent the app is map-only.
- **Bus detection**: a foreground service watches for the Wi-Fi network
  `Tallinja Wi-Fi` (connected network and scan results). When it shows up, GPS
  is switched on. After 60 s without the network the user has left the bus and
  GPS is released.
- **Upload over mutual TLS** to `https://melamur.snel.it/aggregator/`. The server
  certificate is checked against the system CAs (with optional pinning), and the
  app authenticates with a client certificate (`assets/client.p12`). Without
  that file the upload is a logging stub.
- **Next stop + "press STOP"**: tap a stop to make it your destination. When it
  is the next stop and closer than max(250 m, 30 s of travel), you get a
  vibrating heads-up alert.
- **Off-route warning**: three good fixes more than 70 m off the matched route
  shape trigger an alert.
- **Stop search** (🔍 in the top bar): searches the **stop area** — the part
  after the "Locality - " prefix (so "baruni" finds "Ħaż-Żabbar - Baruni", while
  the locality "zabbar" does not match) — diacritic-insensitively, or a stop
  number once the backend supplies them. Each result shows its **island**
  (Malta/Gozo) and **direction of travel**
  (compass + arrow), so the several stops that share a name are easy to tell
  apart. Tapping a result centres the map on it and sets it as your destination.
  Note: the OSM data has no official Tallinja stop numbers (only names); numbers
  light up automatically when an API/GTFS feed provides the `code` field.
- **Bus stops on the left**: Malta drives on the left and buses only have doors on
  the left. The converter snaps every OSM stop to the nearest bus-usable road,
  works out which direction of travel the stop serves (the one with the stop on
  its left), and puts it 5 m from the centre line. On one-way roads, stops mapped
  on the wrong side are moved to the left kerb (50 in the current extract). The
  map draws a tick in the served direction.
- **Route drawing with direction split**: where the two directions share a road
  and every stop has an opposite stop within 120 m, one line is drawn. Where the
  shapes are more than 30 m apart, or a stop has no opposite stop, the line
  splits into two thinner lines, offset to the left, with direction arrows.
  Colours come from the route data.

## Stubs / TODO

- **Tallinja API** (`data/TallinjaApi.kt`): wired to the real backend — the
  Meep MaaS layer over OpenTripPlanner (`api.meep.me`, zone `Malta`), with the
  anonymous-JWT auth flow. The endpoint structure is documented in
  [`docs/tallinja-api.md`](docs/tallinja-api.md). **No API key is committed.**
  The official app's key is not reused; request your own (draft in
  [`docs/tallinja-api-key-request.md`](docs/tallinja-api-key-request.md)) and
  set it with `-PtallinjaApiKey=...` or in `~/.gradle/gradle.properties`. With
  no key the app runs fully offline on bundled data: OSM stops and an optional
  `assets/routes.json`. With no routes, next stop falls back to the nearest stop
  ahead on the left kerb of your direction.
- **Bus lines from open data** (no backend, no key). Malta has no published
  static GTFS at the moment, so the temporary source is OpenStreetMap:

      python3 tools/build_routes_from_osm.py            # fetch Malta from Overpass
      # -> app/src/main/assets/routes.json (shapes, directions, stop ids, colours)

  `build_routes_from_osm.py` reads OSM bus route relations via the Overpass API
  (run it where you have internet; pass `--in saved.json` to parse a saved
  Overpass response offline). If an official GTFS ever appears (e.g. on Malta's
  [National Access Point](https://news.transport.gov.mt/data/)),
  `tools/build_routes_from_gtfs.py malta-gtfs.zip app/src/main/assets/routes.json`
  produces the same file from it.
- **Route colours**: taken from the API `color` field (same as the official app).
  There is a fallback palette only when that field is missing.
- **Server** (`server/melamur_aggregator.py`): a WSGI app that receives the
  app's uploads and stores each GPS fix in **MySQL** (table `fixes`). It reads
  the Apache-verified client certificate from `SSL_CLIENT_*` and refuses
  requests without one. Runs behind **Apache2 + mod_wsgi**
  (`server/melamur-apache.conf`, with `server/melamur.wsgi` as entry point), and
  standalone for testing:

      pip install PyMySQL                       # once
      python3 server/melamur_aggregator.py --init-db     # create the schema
      sh server/test_aggregator.sh db           # HTTP, no cert, posts + reads back rows
      sh server/test_aggregator.sh tls          # mutual TLS using server/certs

  Config is via environment (`MELAMUR_DB_HOST/NAME/USER/PASS`,
  `MELAMUR_REQUIRE_CLIENT_CERT`). The older `server/aggregator.py` (JSONL, no
  DB) is kept as a minimal reference. Fusion with stops and schedules is TODO.
- No persistent upload retry queue yet.

## Build

No Gradle wrapper is committed. Use Gradle 8.14 with JDK 17 and the Android SDK:

    gradle assembleDebug

GitHub Actions (`.github/workflows/android.yml`) builds the debug APK on every
push and keeps it as an artifact.

Dev certificates: `server/make_certs.sh` (copies `client.p12` into the app
assets; the file is git-ignored). Build with `-PmelamurP12Password=...`.

Regenerate the map:

    python3 tools/convert_gpkg.py malta-latest-free.gpkg app/src/main/assets

Map data © OpenStreetMap contributors, ODbL.
