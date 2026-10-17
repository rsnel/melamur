# Tallinja / Meep API — reverse-engineered structure

This documents the **shape** of the API the official Tallinja app
(`com.mpt.tallinjaapp` 3.2.17) talks to, so MelaMur can use the same backend
**with our own credentials**. No API key, token or other secret is recorded
here or committed to the repo — only endpoint paths and field names, which are
what we need for interoperability.

The official app is built on the **Meep** MaaS SDK (`app.meep.*`) in front of an
**OpenTripPlanner (OTP)** routing backend. The `/api/v1/routers/{zone}/index/...`
paths are the standard OTP Index API.

## Hosts

| Purpose | Host |
|---|---|
| Production API | `https://api.meep.me/` |
| Icons / images | `https://apidev2.meep.me/common/api/v1/icon/raw/...` |
| Operator landing | `https://tallinja-app.publictransport.com.mt` |

Zone / router id for Malta: **`Malta`** (the OTP `{zoneName}` / `{routerId}`).
Realtime responses key vehicles by a numeric **`companyZoneId`**.

## Authentication (anonymous SDK token)

The app does not send a static key on every call. It exchanges an application
key for a short-lived **anonymous JWT**, then sends that as a Bearer token:

```
POST /access/sdk/anonymous
X-API-KEY: <application key>          # per-app; obtain your own (see request draft)
→ { "accessToken": "...", "refreshToken": "...", "expiresIn": ... }

# then, on every subsequent call:
Authorization: Bearer <accessToken>
```

`POST /access/sdk/jwt` refreshes the token. Per-app settings come from
`GET /api/private/mobile_sdk/settings/{applicationId}.json`.

> We must request our own application key from Malta Public Transport / Meep
> rather than reuse the one embedded in the official app. See
> `tallinja-api-key-request.md`.

## Transit endpoints (OTP Index API)

All relative to `https://api.meep.me`, `{zone}` = `Malta`.

| What | Method + path |
|---|---|
| Stop detail | `GET /api/v1/routers/{zone}/index/stops/{stopId}` |
| Routes serving a stop | `GET /api/v1/routers/{zone}/index/stops/{stopId}/routes` |
| Next departures at a stop | `GET /api/v1/routers/{zone}/index/stops/{stopId}/stoptimes` |
| Full timetable at a stop | `GET /api/v1/routers/{zone}/index/stops/{stopId}/timetable` |
| Route detail | `GET /api/v1/routers/{zone}/index/routes/{routeId}` |
| Route patterns (directions) | `GET /api/v1/routers/{zone}/index/routes/{routeId}/patterns` |
| Stops of a trip | `GET /api/v1/routers/{zone}/index/trips/{tripId}/stops` |
| Stoptimes of a trip | `GET /api/v1/routers/{zone}/index/trips/{tripId}/stoptimes` |
| Geocode | `GET /api/v1/routers/{zone}/geocode?query=...` |
| Operator resources/icons | `GET /api/v1/routers/{zone}/resources` |
| Journey plan (multi-modal) | `GET /api/v1/routers/{routerId}/multiplan?fromPlace=lat,lon&toPlace=lat,lon` |

### Realtime vehicle positions ("moving buses")

Served by the Meep layer, keyed by `companyZoneId`
(`NetworkMovingBusesResponse`, `NetworkETagsByCompanyZoneIdBody` — the client
sends ETags per zone and gets back only changed vehicles). Fields observed:
`companyZoneId`, and per vehicle `lat`, `lon`, `bearing`, line/route id and
`tripId`.

## Response fields we rely on (standard OTP Index)

- **Stop**: `id`, `code`, `name`, `lat`, `lon`.
- **Route**: `id`, `shortName`, `longName`, `mode`, **`color`**, `textColor`,
  `agencyName`. `color` is the route colour the official app renders — MelaMur
  uses the same value, so colours match without hard-coding.
- **Pattern** (one per direction): `id`, `desc`, `routeId`, `directionId`,
  `stops[]` (ordered), `geometry` (Google-encoded polyline → the line shape).
- **Stoptime**: `scheduledArrival`, `realtimeArrival`, `realtime`,
  `tripHeadsign`, `tripId` (seconds since local midnight for the service day).

## Mapping to MelaMur

`data/TallinjaApi.kt` targets these paths. Two directions of a route come from
the route's **patterns**; each pattern's `geometry` + ordered `stops[]` feeds
`RouteRenderer`, which draws one line where the directions coincide and splits
with arrows where they diverge (and Malta stops sit on the left kerb). Until a
key is granted, the app falls back to bundled `assets/stops.json` (from the OSM
map) and optional `assets/routes.json` (build it from the open GTFS feed with
`tools/build_routes_from_gtfs.py`).
