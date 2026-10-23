#!/usr/bin/env python3
"""Validate the MelaMur pedestrian walking graph (assets/walk.bin).

walk.bin is written by convert_gpkg.py: the walkable OSM ways, UN-simplified, as
int16 metres in the map/Geo.kt projection. The app (WalkGraph.kt) nodes it at
load by merging identical vertex coordinates — because the ways are
un-simplified, shared OSM junction nodes have identical coordinates, so the
network connects. This script mirrors that construction and reports connectivity
and sample route quality, so you can confirm a freshly generated walk.bin is
sound before shipping it. Run with: python3 -I tools/walkgraph_ref.py

(If you still see a fragmented graph here, the ways were simplified or the wrong
classes were included — fix convert_gpkg.py, not the app.)
"""
import json
import math
import struct
import heapq
import os
import sys
from collections import Counter

LAT0, LON0 = 35.9, 14.4
MX = 111320.0 * math.cos(math.radians(LAT0))
MY = 110540.0
def gx(lon): return (lon - LON0) * MX
def gy(lat): return (lat - LAT0) * MY

WALK = "app/src/main/assets/walk.bin"
STOPS = "app/src/main/assets/stops.json"


def load_ways(path):
    with open(path, "rb") as f:
        b = f.read()
    assert b[:4] == b"MLWK", "not a walk.bin (bad magic)"
    off = 4 + 16
    n = struct.unpack_from("<I", b, off)[0]; off += 4
    ways = []
    for _ in range(n):
        np = struct.unpack_from("<H", b, off)[0]; off += 2
        pts = struct.unpack_from("<%dh" % (2 * np), b, off); off += 4 * np
        ways.append(pts)
    return ways


def build_graph(ways):
    """Node by exact integer-metre coordinate; edges join consecutive vertices."""
    coord = []
    node_of = {}
    adj = []
    def node(x, y):
        k = (x, y)
        i = node_of.get(k)
        if i is None:
            i = len(coord); node_of[k] = i; coord.append((x, y)); adj.append([])
        return i
    for pts in ways:
        prev = None
        for i in range(0, len(pts), 2):
            cur = node(pts[i], pts[i + 1])
            if prev is not None and prev != cur:
                d = math.hypot(coord[cur][0] - coord[prev][0], coord[cur][1] - coord[prev][1])
                adj[prev].append((cur, d)); adj[cur].append((prev, d))
            prev = cur
    return coord, adj


def components(adj):
    parent = list(range(len(adj)))
    def find(x):
        while parent[x] != x:
            parent[x] = parent[parent[x]]; x = parent[x]
        return x
    for u, nbrs in enumerate(adj):
        for v, _ in nbrs:
            ru, rv = find(u), find(v)
            if ru != rv:
                parent[ru] = rv
    c = Counter(find(i) for i in range(len(adj)))
    return len(c), (max(c.values()) if c else 0)


def grid_index(coord, cell=60.0):
    g = {}
    for i, (x, y) in enumerate(coord):
        g.setdefault((int(x // cell), int(y // cell)), []).append(i)
    return g, cell


def nearest(coord, g, cell, px, py, radius=80.0):
    cx, cy = int(px // cell), int(py // cell)
    best, bd = -1, radius
    for dx in (-1, 0, 1):
        for dy in (-1, 0, 1):
            for i in g.get((cx + dx, cy + dy), ()):
                d = math.hypot(coord[i][0] - px, coord[i][1] - py)
                if d < bd:
                    bd, best = d, i
    return best, bd


def astar(adj, coord, s, t):
    INF = float("inf")
    dist = {s: 0.0}
    pq = [(0.0, s)]
    tx, ty = coord[t]
    while pq:
        f, u = heapq.heappop(pq)
        if u == t:
            return dist[t]
        du = dist[u]
        if f - math.hypot(coord[u][0] - tx, coord[u][1] - ty) > du + 1e-6:
            continue
        for v, w in adj[u]:
            nd = du + w
            if nd < dist.get(v, INF):
                dist[v] = nd
                heapq.heappush(pq, (nd + math.hypot(coord[v][0] - tx, coord[v][1] - ty), v))
    return None


def main():
    if not os.path.exists(WALK):
        print("walk.bin not found at", WALK)
        print("Regenerate assets with your gpkg:  python3 tools/convert_gpkg.py malta.gpkg app/src/main/assets")
        return 1
    ways = load_ways(WALK)
    coord, adj = build_graph(ways)
    nodes = len(coord); edges = sum(len(a) for a in adj) // 2
    ncomp, largest = components(adj)
    print("walk.bin: ways=%d nodes=%d edges=%d comps=%d largest=%.1f%%"
          % (len(ways), nodes, edges, ncomp, 100.0 * largest / max(nodes, 1)))

    stops = json.load(open(STOPS))
    pxy = [(gx(s["lon"]), gy(s["lat"]), s["name"]) for s in stops]
    import random
    random.seed(1)
    probes = []
    for a in random.sample(pxy, 60):
        best, bd = None, 1e9
        for cc in pxy:
            if cc is a:
                continue
            d = math.hypot(a[0] - cc[0], a[1] - cc[1])
            if 50 < d < bd:
                bd, best = d, cc
        if best and bd < 1500:
            probes.append((a, best, bd))

    g, cell = grid_index(coord)
    ok = 0; ratios = []
    for a, cc, euc in probes:
        na, da = nearest(coord, g, cell, a[0], a[1])
        nc, dc = nearest(coord, g, cell, cc[0], cc[1])
        if na < 0 or nc < 0:
            continue
        net = astar(adj, coord, na, nc)
        if net is None:
            continue
        ok += 1; ratios.append((net + da + dc) / max(euc, 1))
    if ratios:
        ratios.sort()
        print("routed %d/%d near-pairs | net/euc min=%.2f med=%.2f avg=%.2f max=%.2f"
              % (ok, len(probes), ratios[0], ratios[len(ratios) // 2],
                 sum(ratios) / len(ratios), ratios[-1]))
    else:
        print("routed 0 pairs")
    return 0


if __name__ == "__main__":
    sys.exit(main())
