#!/usr/bin/env python3
"""Build app/src/main/assets/routes.json from a Malta GTFS feed.

This uses only the open GTFS feed (no Tallinja backend, no credentials), so the
app can draw real bus lines, colours and stop sequences for testing. The public
Malta feed is listed on the Mobility Database:
https://mobilitydatabase.org/  (search "Malta"); download the GTFS .zip there.

Output format matches TallinjaApi.parseRoutes:
  [{"id","short_name","color":"#RRGGBB",
    "directions":[{"id","headsign","stops":[stopId,...],"shape":[[lat,lon],...]}]}]

For each route we pick the longest trip per direction_id as its representative
pattern, take that trip's ordered stops, and its shape if shapes.txt is present
(otherwise the stop coordinates).

usage: build_routes_from_gtfs.py malta-gtfs.zip app/src/main/assets/routes.json
"""
import csv, io, json, sys, zipfile
from collections import defaultdict


def read(z, name):
    try:
        data = z.read(name).decode("utf-8-sig")
    except KeyError:
        return []
    return list(csv.DictReader(io.StringIO(data)))


def main(gtfs_zip, out):
    z = zipfile.ZipFile(gtfs_zip)
    routes = {r["route_id"]: r for r in read(z, "routes.txt")}
    stops = {s["stop_id"]: s for s in read(z, "stops.txt")}
    shapes = defaultdict(list)
    for p in read(z, "shapes.txt"):
        shapes[p["shape_id"]].append(
            (int(p["shape_pt_sequence"]), float(p["shape_pt_lat"]), float(p["shape_pt_lon"])))
    for k in shapes:
        shapes[k] = [(la, lo) for _, la, lo in sorted(shapes[k])]

    trips = read(z, "trips.txt")
    stoptimes = defaultdict(list)
    for st in read(z, "stop_times.txt"):
        stoptimes[st["trip_id"]].append((int(st["stop_sequence"]), st["stop_id"]))
    for k in stoptimes:
        stoptimes[k] = [s for _, s in sorted(stoptimes[k])]

    # longest trip (most stops) per (route, direction)
    best = {}
    for t in trips:
        key = (t["route_id"], t.get("direction_id", "0"))
        n = len(stoptimes.get(t["trip_id"], []))
        if n and (key not in best or n > best[key][1]):
            best[key] = (t, n)

    by_route = defaultdict(list)
    for (rid, dirid), (t, _) in best.items():
        by_route[rid].append((dirid, t))

    out_routes = []
    for rid, dirs in sorted(by_route.items()):
        r = routes.get(rid, {})
        color = "#" + (r.get("route_color") or "").strip().lstrip("#") if r.get("route_color") else ""
        directions = []
        for dirid, t in sorted(dirs):
            seq = stoptimes[t["trip_id"]]
            shp = shapes.get(t.get("shape_id", ""))
            if not shp:
                shp = [(float(stops[s]["stop_lat"]), float(stops[s]["stop_lon"]))
                       for s in seq if s in stops]
            directions.append({
                "id": f"{rid}:{dirid}",
                "headsign": t.get("trip_headsign", ""),
                "stops": seq,
                "shape": [[round(la, 6), round(lo, 6)] for la, lo in shp],
            })
        out_routes.append({
            "id": rid,
            "short_name": r.get("route_short_name") or rid,
            "color": color,
            "directions": directions,
        })

    with open(out, "w") as f:
        json.dump(out_routes, f, ensure_ascii=False, separators=(",", ":"))
    print(f"wrote {len(out_routes)} routes to {out}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
