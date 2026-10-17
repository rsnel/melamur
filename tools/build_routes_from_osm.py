#!/usr/bin/env python3
"""Build app/src/main/assets/routes.json from OpenStreetMap bus route relations
(Overpass API). Temporary stand-in until an official Tallinja/GTFS feed is
available: uses only open OSM data, no Tallinja backend, no credentials.

Overpass is not reachable from every network; this script fetches it directly,
so run it where you have internet. With no argument it queries Overpass for
Malta; pass a saved Overpass JSON file to parse that instead (offline):

  python3 tools/build_routes_from_osm.py                 # fetch Malta from Overpass
  python3 tools/build_routes_from_osm.py malta-osm.json  # parse a saved response
  # optional 2nd arg: output path (default app/src/main/assets/routes.json)

To save the Overpass response yourself (e.g. behind a proxy / in a browser),
use this query at https://overpass-api.de/api/interpreter :

  [out:json][timeout:180];
  relation["route"="bus"](35.75,14.15,36.12,14.62);
  out geom;

Output matches TallinjaApi.parseRoutes:
  [{"id","short_name","color":"#RRGGBB",
    "directions":[{"id","headsign","stops":[nodeId,...],"shape":[[lat,lon],...]}]}]
Each OSM PTv2 route relation is one direction; relations are grouped by `ref`.
"""
import json, sys, urllib.parse, urllib.request
from collections import defaultdict

OVERPASS = "https://overpass-api.de/api/interpreter"
# Bounding box around the Maltese islands (S,W,N,E) — robust, no area resolution.
BBOX = "35.75,14.15,36.12,14.62"
QUERY = ('[out:json][timeout:180];'
         f'relation["route"="bus"]({BBOX});'
         'out geom;')
STOP_ROLES = ("stop", "platform", "stop_entry_only", "stop_exit_only",
              "platform_entry_only", "platform_exit_only")


def fetch():
    req = urllib.request.Request(OVERPASS, data=urllib.parse.urlencode({"data": QUERY}).encode(),
                                 headers={"User-Agent": "melamur-routes-builder"})
    with urllib.request.urlopen(req, timeout=200) as r:
        return json.load(r)


def stitch(ways):
    """Concatenate member way geometries into one ordered polyline, flipping
    each way so its start meets the running endpoint (greedy)."""
    line = []
    for w in ways:
        g = [(p["lat"], p["lon"]) for p in w.get("geometry") or []]
        if len(g) < 2:
            continue
        if not line:
            line = g
            continue
        # choose orientation of g that best continues `line`
        end = line[-1]

        def d(a, b):
            return (a[0] - b[0]) ** 2 + (a[1] - b[1]) ** 2
        if d(end, g[0]) <= d(end, g[-1]):
            line += g[1:] if d(end, g[0]) < 1e-10 else g
        else:
            gr = g[::-1]
            line += gr[1:] if d(end, gr[0]) < 1e-10 else gr
    # drop consecutive duplicates
    out = []
    for p in line:
        if not out or out[-1] != p:
            out.append(p)
    return out


def direction(rel):
    tags = rel.get("tags", {})
    members = rel.get("members", [])
    ways = [m for m in members if m["type"] == "way"]
    stops = [str(m["ref"]) for m in members
             if m["type"] == "node" and m.get("role", "").startswith(("stop", "platform"))]
    shape = stitch(ways)
    headsign = tags.get("to") or tags.get("name", "")
    return {
        "id": str(rel["id"]),
        "headsign": headsign,
        "stops": stops,
        "shape": [[round(la, 6), round(lo, 6)] for la, lo in shape],
    }, tags


def main():
    import argparse
    ap = argparse.ArgumentParser(description="Build routes.json from OSM bus relations")
    ap.add_argument("--in", dest="src", help="saved Overpass JSON (default: fetch from Overpass)")
    ap.add_argument("--out", default="app/src/main/assets/routes.json")
    a = ap.parse_args()
    data = json.load(open(a.src)) if a.src else fetch()
    out = a.out

    rels = [el for el in data.get("elements", []) if el.get("type") == "relation"]
    by_ref = defaultdict(list)
    no_geom = 0
    for el in rels:
        d, tags = direction(el)
        if not d["shape"]:
            no_geom += 1
            continue
        ref = tags.get("ref") or tags.get("name") or d["id"]
        by_ref[ref].append((d, tags))
    if not rels:
        sys.stderr.write("No bus route relations in the Overpass response. If you saved it "
                         "yourself, check the query/bbox; otherwise Overpass may have been busy "
                         "(try again).\n")
    elif no_geom == len(rels):
        sys.stderr.write(f"{len(rels)} relations found but none had member geometry — the query "
                         "must end with 'out geom;' (not 'out;' or 'out tags;').\n")

    routes = []
    for ref, dirs in sorted(by_ref.items()):
        colour = ""
        for _, tags in dirs:
            c = (tags.get("colour") or tags.get("color") or "").strip()
            if c:
                colour = c if c.startswith("#") else ("#" + c if len(c) in (3, 6) and all(
                    ch in "0123456789abcdefABCDEF" for ch in c) else c)
                break
        routes.append({
            "id": ref,
            "short_name": ref,
            "color": colour,
            "directions": [d for d, _ in dirs],
        })

    with open(out, "w") as f:
        json.dump(routes, f, ensure_ascii=False, separators=(",", ":"))
    npts = sum(len(d["shape"]) for r in routes for d in r["directions"])
    print(f"wrote {len(routes)} routes, "
          f"{sum(len(r['directions']) for r in routes)} directions, {npts} shape points -> {out}")


if __name__ == "__main__":
    main()
