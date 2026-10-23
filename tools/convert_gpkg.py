#!/usr/bin/env python3
"""Convert a Geofabrik "free" GeoPackage (e.g. malta-latest-free.gpkg) into the
compact assets used by the MelaMur app:

  app/src/main/assets/map.bin   vector base map (land, landuse, water, roads, buildings)
  app/src/main/assets/stops.json OSM bus stops, corrected for left-hand traffic

Projection: local equirectangular metres around (LAT0, LON0); coordinates are
stored as int16 metres (Malta fits easily in +/-32 km).

Bus-stop correction (Malta drives on the LEFT, buses only have doors on the
left): every stop is snapped to the nearest bus-usable road. The direction of
travel it serves is the one for which the stop lies on the LEFT of the road.
On one-way roads the serving direction is fixed, so a stop that was mapped on
the right side is moved to the left kerb. The stop is placed KERB metres from
the road centre line.

usage: convert_gpkg.py malta.gpkg outdir
"""
import json, math, os, sqlite3, struct, sys

LAT0, LON0 = 35.9, 14.4
MX = 111320.0 * math.cos(math.radians(LAT0))
MY = 110540.0
KERB = 5.0

def proj(lon, lat):
    return ((lon - LON0) * MX, (lat - LAT0) * MY)

def unproj(x, y):
    return (x / MX + LON0, y / MY + LAT0)

# ---------- GeoPackage geometry -> list of rings/lines (lists of (lon,lat))
def gpkg_wkb(blob):
    flags = blob[3]
    env = (flags >> 1) & 7
    off = 8 + {0: 0, 1: 32, 2: 48, 3: 48, 4: 64}[env]
    return blob[off:]

def parse_wkb(b, o=0, out=None):
    if out is None:
        out = []
    bo = '<' if b[o] == 1 else '>'
    t = struct.unpack_from(bo + 'I', b, o + 1)[0] % 1000
    o += 5
    def pts(o):
        n = struct.unpack_from(bo + 'I', b, o)[0]; o += 4
        p = [struct.unpack_from(bo + 'dd', b, o + 16 * i) for i in range(n)]
        return p, o + 16 * n
    if t == 1:
        out.append([struct.unpack_from(bo + 'dd', b, o)]); o += 16
    elif t == 2:
        p, o = pts(o); out.append(p)
    elif t == 3:
        nr = struct.unpack_from(bo + 'I', b, o)[0]; o += 4
        for _ in range(nr):
            p, o = pts(o); out.append(p)
    elif t in (4, 5, 6, 7):
        n = struct.unpack_from(bo + 'I', b, o)[0]; o += 4
        for _ in range(n):
            o = parse_wkb(b, o, out)[1]
        return out, o
    return out, o

def geoms(c, sql):
    for row in c.execute(sql):
        rings, _ = parse_wkb(gpkg_wkb(row[0]))
        yield row[1:], rings

# ---------- Douglas-Peucker
def simplify(p, eps):
    if len(p) < 3:
        return p
    if p[0] == p[-1]:                    # closed ring: split, else DP collapses it
        m = len(p) // 2
        return simplify(p[:m + 1], eps)[:-1] + simplify(p[m:], eps)
    keep = [False] * len(p); keep[0] = keep[-1] = True
    st = [(0, len(p) - 1)]
    while st:
        a, b = st.pop()
        ax, ay = p[a]; bx, by = p[b]
        dx, dy = bx - ax, by - ay; L = math.hypot(dx, dy) or 1e-9
        best, bi = -1, -1
        for i in range(a + 1, b):
            d = abs((p[i][0] - ax) * dy - (p[i][1] - ay) * dx) / L
            if d > best:
                best, bi = d, i
        if best > eps:
            keep[bi] = True; st += [(a, bi), (bi, b)]
    return [q for q, k in zip(p, keep) if k]

# layer id, style id -> matches LayerStyle in MapData.kt
LAYERS = []

def add(layer, style, rings, eps):
    for r in rings:
        p = simplify([proj(*q) for q in r], eps)
        p = [(int(round(x)), int(round(y))) for x, y in p]
        dd = [p[0]] if p else []
        for q in p[1:]:
            if q != dd[-1]:
                dd.append(q)
        if len(dd) >= 2 and all(-32767 < v < 32767 for q in dd for v in q):
            LAYERS.append((layer, style, dd))

ROAD_STYLE = {
    'trunk': 10, 'trunk_link': 10, 'primary': 11, 'primary_link': 11,
    'secondary': 12, 'secondary_link': 12, 'tertiary': 13, 'tertiary_link': 13,
    'unclassified': 14, 'residential': 14, 'living_street': 14, 'busway': 12,
    'service': 15, 'pedestrian': 16, 'footway': 16, 'path': 16, 'steps': 16,
    'cycleway': 16,
}
BUS_ROADS = {'trunk', 'trunk_link', 'primary', 'primary_link', 'secondary',
             'secondary_link', 'tertiary', 'tertiary_link', 'unclassified',
             'residential', 'living_street', 'busway', 'service'}
# classes a pedestrian may use; written UN-simplified to walk.bin so shared OSM
# junction vertices line up and the app can node them into a routing graph
WALK_ROADS = set(ROAD_STYLE.keys())
LANDUSE_STYLE = {'residential': 2, 'industrial': 2, 'commercial': 2, 'retail': 2,
                 'park': 3, 'grass': 3, 'forest': 3, 'meadow': 3, 'scrub': 3,
                 'farmland': 4, 'orchard': 4, 'vineyard': 4, 'allotments': 4}

def main(gpkg, outdir):
    c = sqlite3.connect(gpkg)
    # land: union of the regions (admin level 1207) approximates the coastline
    for _, r in geoms(c, "select geom from gis_osm_adminareas_a_free where code=1207"):
        add(0, 1, r, 3)
    for (fc,), r in geoms(c, "select geom,fclass from gis_osm_landuse_a_free"):
        if fc in LANDUSE_STYLE:
            add(0, LANDUSE_STYLE[fc], r, 3)
    for _, r in geoms(c, "select geom from gis_osm_water_a_free"):
        add(0, 5, r, 3)
    for _, r in geoms(c, "select geom from gis_osm_buildings_a_free"):
        add(0, 6, r, 1.5)
    roads = []
    walk_ways = []   # un-simplified walkable ways, int metres, for walk.bin
    for (fc, oneway), r in geoms(c, "select geom,fclass,oneway from gis_osm_roads_free"):
        if fc.startswith('track'):
            fc = 'path'
        if fc in ROAD_STYLE:
            add(1, ROAD_STYLE[fc], r, 1.5)
        if fc in BUS_ROADS:
            for ring in r:
                roads.append((fc, oneway, [proj(*q) for q in ring]))
        if fc in WALK_ROADS:
            for ring in r:
                w = []
                for q in ring:
                    x, y = proj(*q)
                    xi, yi = int(round(x)), int(round(y))
                    if not (-32767 < xi < 32767 and -32767 < yi < 32767):
                        continue
                    if not w or w[-1] != (xi, yi):
                        w.append((xi, yi))
                if len(w) >= 2:
                    walk_ways.append(w)

    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, 'map.bin'), 'wb') as f:
        f.write(b'MLMR'); f.write(struct.pack('<ddI', LAT0, LON0, len(LAYERS)))
        for layer, style, p in LAYERS:
            f.write(struct.pack('<BBH', layer, style, len(p)))
            f.write(struct.pack('<%dh' % (2 * len(p)), *[v for q in p for v in q]))

    # ---- walk.bin: walkable ways, un-simplified, for the app's foot router.
    # Ways are split into chunks of <=65535 points sharing a boundary vertex.
    def chunks(w):
        i = 0
        while i < len(w):
            yield w[i:i + 65535]
            i += 65535 - 1   # overlap one vertex so the chunks stay connected
    walk_out = [ck for w in walk_ways for ck in chunks(w) if len(ck) >= 2]
    with open(os.path.join(outdir, 'walk.bin'), 'wb') as f:
        f.write(b'MLWK'); f.write(struct.pack('<ddI', LAT0, LON0, len(walk_out)))
        for w in walk_out:
            f.write(struct.pack('<H', len(w)))
            f.write(struct.pack('<%dh' % (2 * len(w)), *[v for q in w for v in q]))

    # ---- spatial grid of road segments for snapping
    G = 100.0
    grid = {}
    for ri, (fc, ow, p) in enumerate(roads):
        for i in range(len(p) - 1):
            (ax, ay), (bx, by) = p[i], p[i + 1]
            for gx in range(int(min(ax, bx) // G), int(max(ax, bx) // G) + 1):
                for gy in range(int(min(ay, by) // G), int(max(ay, by) // G) + 1):
                    grid.setdefault((gx, gy), []).append((ri, i))

    stops, moved = [], 0
    for (osm_id, name), r in geoms(c, "select geom,osm_id,name from gis_osm_transport_free where fclass='bus_stop'"):
        lon, lat = r[0][0]
        sx, sy = proj(lon, lat)
        best = None
        cx, cy = int(sx // G), int(sy // G)
        for gx in range(cx - 1, cx + 2):
            for gy in range(cy - 1, cy + 2):
                for ri, i in grid.get((gx, gy), ()):
                    (ax, ay), (bx, by) = roads[ri][2][i], roads[ri][2][i + 1]
                    dx, dy = bx - ax, by - ay; L2 = dx * dx + dy * dy or 1e-9
                    t = max(0, min(1, ((sx - ax) * dx + (sy - ay) * dy) / L2))
                    px, py = ax + t * dx, ay + t * dy
                    d = math.hypot(sx - px, sy - py)
                    if best is None or d < best[0]:
                        best = (d, ri, px, py, dx, dy)
        rec = {'id': str(osm_id), 'name': name or '', 'lat': lat, 'lon': lon}
        if best and best[0] < 60:
            d, ri, px, py, dx, dy = best
            fc, ow = roads[ri][0], roads[ri][1]
            L = math.hypot(dx, dy) or 1e-9
            ux, uy = dx / L, dy / L          # unit vector along digitised direction
            left = (ux * (sy - py) - uy * (sx - px)) > 0   # stop left of a->b ?
            if ow == 'F':
                serve, left_ok = (ux, uy), left
            elif ow == 'T':
                serve, left_ok = (-ux, -uy), not left
            else:
                serve, left_ok = ((ux, uy) if left else (-ux, -uy)), True
            if not left_ok:
                moved += 1
            hx, hy = serve
            nx, ny = -hy, hx                 # left normal of the serving direction
            qx, qy = px + KERB * nx, py + KERB * ny
            rec['lon'], rec['lat'] = unproj(qx, qy)
            rec['heading'] = round((math.degrees(math.atan2(hx, hy)) + 360) % 360, 1)
            rec['road'] = fc
            if not left_ok:
                rec['moved'] = True
        stops.append(rec)
    with open(os.path.join(outdir, 'stops.json'), 'w') as f:
        json.dump(stops, f, ensure_ascii=False, separators=(',', ':'))

    # ---- labels: place names + street names
    PLACE_RANK = {'national_capital': 0, 'city': 0, 'town': 1, 'suburb': 2,
                  'village': 2, 'hamlet': 3, 'locality': 4}
    places = []
    for (fc, name), r in geoms(c, "select geom,fclass,name from gis_osm_places_free where name is not null"):
        if fc not in PLACE_RANK:
            continue
        lon, lat = r[0][0]
        places.append({'n': name, 'r': PLACE_RANK[fc], 'lat': round(lat, 6), 'lon': round(lon, 6)})

    # one street label per name per ~700 m cell, placed at a segment midpoint with
    # a neighbour point so the app can rotate the text along the road
    SC = 700.0
    seen = set()
    streets = []
    for (name,), r in geoms(c, "select geom,name from gis_osm_roads_free where name is not null and name<>''"):
        for ring in r:
            if len(ring) < 2:
                continue
            m = len(ring) // 2
            lon, lat = ring[m]
            lon2, lat2 = ring[m - 1]
            px, py = proj(lon, lat)
            key = (name, int(px // SC), int(py // SC))
            if key in seen:
                continue
            seen.add(key)
            streets.append({'n': name, 'lat': round(lat, 6), 'lon': round(lon, 6),
                            'lat2': round(lat2, 6), 'lon2': round(lon2, 6)})
    # landmarks: named points of interest people search for (attractions,
    # hotels, civic, parks, beaches, places of worship, ...). Excludes noise
    # like benches, bins, post boxes, shops.
    LAND_FCLASS = {'theme_park', 'attraction', 'museum', 'memorial', 'artwork', 'monument',
                   'castle', 'ruins', 'fort', 'tower', 'viewpoint', 'lighthouse', 'archaeological',
                   'zoo', 'gallery', 'tourist_info', 'hotel', 'hostel', 'guesthouse', 'motel',
                   'camp_site', 'hospital', 'university', 'college', 'library', 'theatre', 'cinema',
                   'arts_centre', 'marketplace', 'mall', 'department_store', 'supermarket', 'stadium',
                   'sports_centre', 'park', 'garden', 'beach', 'marina', 'golf_course', 'water_park',
                   'playground', 'town_hall', 'community_centre'}
    SCL = 500.0
    seen_l = set()
    landmarks = []
    for layer in ('gis_osm_pois_free', 'gis_osm_pois_a_free', 'gis_osm_pofw_free'):
        worship = layer == 'gis_osm_pofw_free'
        for (fc, name), r in geoms(c, f"select geom,fclass,name from {layer} where name is not null"):
            if not worship and fc not in LAND_FCLASS:
                continue
            ring = r[0]
            lon = sum(p[0] for p in ring) / len(ring)
            lat = sum(p[1] for p in ring) / len(ring)
            px, py = proj(lon, lat)
            key = (name, int(px // SCL), int(py // SCL))
            if key in seen_l:
                continue
            seen_l.add(key)
            landmarks.append({'n': name, 'lat': round(lat, 6), 'lon': round(lon, 6)})

    with open(os.path.join(outdir, 'labels.json'), 'w') as f:
        json.dump({'places': places, 'streets': streets, 'landmarks': landmarks},
                  f, ensure_ascii=False, separators=(',', ':'))

    print('features', len(LAYERS), 'stops', len(stops), 'moved to left kerb', moved,
          'places', len(places), 'street labels', len(streets), 'landmarks', len(landmarks),
          'map.bin bytes', os.path.getsize(os.path.join(outdir, 'map.bin')),
          'walk ways', len(walk_out), 'walk.bin bytes', os.path.getsize(os.path.join(outdir, 'walk.bin')))

if __name__ == '__main__':
    main(sys.argv[1], sys.argv[2])
