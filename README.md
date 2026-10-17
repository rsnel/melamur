# MelaMur

Alternative Malta bus app (Android, Kotlin, no Google Play Services).

## What works

- **Offline map of Malta** from the Geofabrik OSM extract (`tools/convert_gpkg.py`
  → `app/src/main/assets/map.bin`), dark red/black theme, pan, pinch zoom,
  double-tap zoom.
- **Centre on me**: the ◎ button enters follow mode. A pinch near the screen
  centre zooms around your position without losing it; panning or pinching
  elsewhere leaves follow mode.
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

- **Tallinja API** (`data/TallinjaApi.kt`): the uploaded APK was the Uptodown
  installer wrapper, not the Tallinja app, so the real endpoints are not known
  yet. The paths in the code are placeholders. Put the base URL and key in
  `~/.gradle/gradle.properties` (`tallinjaBaseUrl`, `tallinjaApiKey`). Until
  then the app uses the bundled OSM stops and an optional `assets/routes.json`
  (format in `TallinjaApi.parseRoutes`). With no routes, next stop falls back
  to the nearest stop ahead on the left kerb of your direction.
- **Route colours**: taken from the API `color` field (same as the official app).
  There is a fallback palette only when that field is missing.
- **Server** (`server/aggregator.py`): mTLS-verified ingest into JSONL. Fusion
  with stops and schedules is TODO.
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
