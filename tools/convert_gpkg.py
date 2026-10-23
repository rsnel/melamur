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

usage: convert_gpkg.py malta.gpkg outdir              all assets
       convert_gpkg.py malta.gpkg outdir --only walk  only walk.bin (keeps current map)
       convert_gpkg.py malta.gpkg --tables            list the gpkg's layers + columns

Layer names are discovered (classic gis_osm_*_free or Geofabrik's 2026 v0.8 names).
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
    try:
        cur = c.execute(sql)
    except sqlite3.OperationalError as e:
        sys.exit("SQL failed: %s\n  %s\n(table columns: see --tables)" % (e, sql))
    for row in cur:
        if row[0] is None:
            continue
        rings, _ = parse_wkb(gpkg_wkb(row[0]))
        yield row[1:], rings

# ---------- table discovery: Geofabrik renamed layers in 2026 (standard v0.8),
# so resolve each logical layer to whatever table this gpkg actually has.
def tables(c):
    try:
        return [r[0] for r in c.execute("select table_name from gpkg_contents")]
    except sqlite3.OperationalError:
        return [r[0] for r in c.execute("select name from sqlite_master where type='table'")]

def resolve(c, kind, required=True, hint=''):
    """kind like 'roads', 'landuse_a', 'adminareas_a'. Accepts the classic
    gis_osm_<kind>_free(_1) names and the v0.8 style osm_<kind>(_v08)(_1)."""
    import re
    ts = tables(c)
    for t in ('gis_osm_%s_free' % kind, 'gis_osm_%s_free_1' % kind):
        if t in ts:
            return t
    pat = re.compile(r'^(gis_)?osm_%s(_free)?(_v\d+)?(_1)?$' % re.escape(kind))
    for t in ts:
        if pat.match(t):
            return t
    if required:
        sys.exit("No '%s' layer in this gpkg.%s\nTables found:\n  %s"
                 % (kind, hint, "\n  ".join(sorted(ts))))
    print("warning: no '%s' layer, skipping" % kind)
    return None

def geomcol(c, t):
    try:
        r = c.execute("select column_name from gpkg_geometry_columns where table_name=?", (t,)).fetchone()
        if r:
            return r[0]
    except sqlite3.OperationalError:
        pass
    return 'geom'

def columns(c, t):
    return [r[1] for r in c.execute("pragma table_info(%s)" % t)]

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

def walk_way(ring):
    """One way as un-simplified int-metre points (shared junctions stay exact)."""
    w = []
    for q in ring:
        x, y = proj(*q)
        xi, yi = int(round(x)), int(round(y))
        if not (-32767 < xi < 32767 and -32767 < yi < 32767):
            continue
        if not w or w[-1] != (xi, yi):
            w.append((xi, yi))
    return w if len(w) >= 2 else None

def write_walk(outdir, walk_ways):
    """walk.bin: walkable ways, un-simplified, for the app's foot router.
    Ways longer than 65535 points are split into chunks sharing a vertex."""
    def chunks(w):
        i = 0
        while i < len(w):
            yield w[i:i + 65535]
            i += 65535 - 1
    out = [ck for w in walk_ways for ck in chunks(w) if len(ck) >= 2]
    with open(os.path.join(outdir, 'walk.bin'), 'wb') as f:
        f.write(b'MLWK'); f.write(struct.pack('<ddI', LAT0, LON0, len(out)))
        for w in out:
            f.write(struct.pack('<H', len(w)))
            f.write(struct.pack('<%dh' % (2 * len(w)), *[v for q in w for v in q]))
    return out

def fclass_of(fc):
    fc = fc or ''
    return 'path' if fc.startswith('track') else fc

def main_walk_only(c, outdir):
    rt = resolve(c, 'roads')
    ways = []
    for (fc,), r in geoms(c, "select %s,fclass from %s" % (geomcol(c, rt), rt)):
        if fclass_of(fc) in WALK_ROADS:
            for ring in r:
                w = walk_way(ring)
                if w:
                    ways.append(w)
    os.makedirs(outdir, exist_ok=True)
    out = write_walk(outdir, ways)
    print('roads table', rt, '| walk ways', len(out),
          '| walk.bin bytes', os.path.getsize(os.path.join(outdir, 'walk.bin')))

def main(gpkg, outdir, only=None):
    c = sqlite3.connect(gpkg)
    if only == 'walk':
        return main_walk_only(c, outdir)
    T = lambda kind, **kw: resolve(c, kind, **kw)
    G = lambda t: geomcol(c, t)
    # land: union of the regions (admin level 1207) approximates the coastline
    land = T('adminareas_a', hint="\nThe land polygon comes from this layer. To build only the walk graph "
                                  "and keep your current map, run with:  --only walk")
    for _, r in geoms(c, "select %s from %s where code=1207" % (G(land), land)):
        add(0, 1, r, 3)
    t = T('landuse_a')
    for (fc,), r in geoms(c, "select %s,fclass from %s" % (G(t), t)):
        if fc in LANDUSE_STYLE:
            add(0, LANDUSE_STYLE[fc], r, 3)
    t = T('water_a')
    for _, r in geoms(c, "select %s from %s" % (G(t), t)):
        add(0, 5, r, 3)
    t = T('buildings_a')
    for _, r in geoms(c, "select %s from %s" % (G(t), t)):
        add(0, 6, r, 1.5)
    roads = []
    walk_ways = []   # un-simplified walkable ways, int metres, for walk.bin
    rt = T('roads')
    for (fc, oneway), r in geoms(c, "select %s,fclass,oneway from %s" % (G(rt), rt)):
        fc = fclass_of(fc)
        if fc in ROAD_STYLE:
            add(1, ROAD_STYLE[fc], r, 1.5)
        if fc in BUS_ROADS:
            for ring in r:
                roads.append((fc, oneway, [proj(*q) for q in ring]))
        if fc in WALK_ROADS:
            for ring in r:
                w = walk_way(ring)
                if w:
                    walk_ways.append(w)

    os.makedirs(outdir, exist_ok=True)
    with open(os.path.join(outdir, 'map.bin'), 'wb') as f:
        f.write(b'MLMR'); f.write(struct.pack('<ddI', LAT0, LON0, len(LAYERS)))
        for layer, style, p in LAYERS:
            f.write(struct.pack('<BBH', layer, style, len(p)))
            f.write(struct.pack('<%dh' % (2 * len(p)), *[v for q in p for v in q]))

    walk_out = write_walk(outdir, walk_ways)

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
    t = T('transport')
    for (osm_id, name), r in geoms(c, "select %s,osm_id,name from %s where fclass='bus_stop'" % (G(t), t)):
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
    t = T('places')
    for (fc, name), r in geoms(c, "select %s,fclass,name from %s where name is not null" % (G(t), t)):
        if fc not in PLACE_RANK:
            continue
        lon, lat = r[0][0]
        places.append({'n': name, 'r': PLACE_RANK[fc], 'lat': round(lat, 6), 'lon': round(lon, 6)})

    # one street label per name per ~700 m cell, placed at a segment midpoint with
    # a neighbour point so the app can rotate the text along the road
    SC = 700.0
    seen = set()
    streets = []
    for (name,), r in geoms(c, "select %s,name from %s where name is not null and name<>''" % (G(rt), rt)):
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
    for kind in ('pois', 'pois_a', 'pofw'):
        layer = T(kind, required=False)
        if layer is None:
            continue
        worship = kind == 'pofw'
        for (fc, name), r in geoms(c, "select %s,fclass,name from %s where name is not null" % (G(layer), layer)):
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
    args = sys.argv[1:]
    if '--tables' in args:               # diagnostic: what layers/columns does this gpkg have?
        args.remove('--tables')
        c = sqlite3.connect(args[0])
        for t in sorted(tables(c)):
            try:
                print(t, '->', ', '.join(columns(c, t)))
            except sqlite3.OperationalError:
                print(t)
        sys.exit(0)
    only = None
    if '--only' in args:
        i = args.index('--only'); only = args[i + 1]; del args[i:i + 2]
    if len(args) != 2:
        sys.exit(__doc__)
    main(args[0], args[1], only)
