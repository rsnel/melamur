package it.snel.melamur.data

/**
 * Connection Scan Algorithm (Dibbelt, Pajor, Strasser, Wagner). All elementary
 * connections are pre-sorted by departure; one linear scan computes the
 * earliest arrival at every stop, relaxing walking footpaths after each
 * connection. Returns the single earliest-arrival journey (reconstructed).
 */
object Csa {
    private const val INF = Int.MAX_VALUE / 4
    private fun ceilMin(sec: Int) = (sec + 59) / 60

    fun query(d: RaptorData, access: List<IntArray>, egress: Map<Int, Int>, departMin: Int): List<RJourney> {
        val n = d.nStops; val c = d.conn
        val tau = IntArray(n) { INF }
        val pConn = IntArray(n) { -1 }; val pFoot = IntArray(n) { -1 }; val pFootSec = IntArray(n) { 0 }
        val accessSec = IntArray(n) { -1 }
        for (a in access) { val s = a[0]; val t = departMin + ceilMin(a[1]); if (t < tau[s]) { tau[s] = t; accessSec[s] = a[1] } }

        var targetBest = INF
        fun egressRelax(stop: Int, arr: Int) { egress[stop]?.let { val x = arr + ceilMin(it); if (x < targetBest) targetBest = x } }
        for (a in access) egressRelax(a[0], tau[a[0]])

        for (i in 0 until c.size) {
            if (c.depT[i] > targetBest) break                 // sorted: no later connection can help
            val ds = c.depStop[i]
            if (tau[ds] == INF || tau[ds] > c.depT[i]) continue
            val a = c.arrStop[i]
            if (c.arrT[i] < tau[a] && c.arrT[i] < targetBest) {
                tau[a] = c.arrT[i]; pConn[a] = i; pFoot[a] = -1; egressRelax(a, c.arrT[i])
                val tr = d.transfers[a]; var j = 0
                while (j < tr.size) {
                    val q = tr[j]; val w = tr[j + 1]; val cand = c.arrT[i] + ceilMin(w)
                    if (cand < tau[q] && cand < targetBest) { tau[q] = cand; pFoot[q] = a; pFootSec[q] = w; pConn[q] = -1; egressRelax(q, cand) }
                    j += 2
                }
            }
        }

        var e = -1; var bestArr = INF
        for ((st, w) in egress) if (tau[st] < INF) { val arr = tau[st] + ceilMin(w); if (arr < bestArr) { bestArr = arr; e = st } }
        if (e < 0) return emptyList()
        return listOf(reconstruct(d, pConn, pFoot, pFootSec, accessSec, e, egress[e]!!, bestArr))
    }

    private fun reconstruct(d: RaptorData, pConn: IntArray, pFoot: IntArray, pFootSec: IntArray,
                            accessSec: IntArray, e: Int, egressSec: Int, bestArr: Int): RJourney {
        val rev = ArrayList<Seg>()
        var cur = e; var accessStop = e; var accSec = 0; var rides = 0
        while (true) {
            if (pFoot[cur] >= 0) { rev.add(STransfer(pFoot[cur], cur, pFootSec[cur])); cur = pFoot[cur]; continue }
            val ci = pConn[cur]
            if (ci < 0) { accessStop = cur; accSec = accessSec[cur].coerceAtLeast(0); break }
            val route = d.conn.route[ci]; val trip = d.conn.trip[ci]; val alightPos = d.conn.pos[ci] + 1
            var boardStop = d.conn.depStop[ci]; var boardPos = d.conn.pos[ci]
            while (pConn[boardStop] >= 0) {
                val pc = pConn[boardStop]
                if (d.conn.trip[pc] == trip && d.conn.route[pc] == route && d.conn.arrStop[pc] == boardStop) {
                    boardStop = d.conn.depStop[pc]; boardPos = d.conn.pos[pc]
                } else break
            }
            rev.add(SRide(route, trip, boardPos, alightPos)); rides++; cur = boardStop
        }
        return RJourney(rides, bestArr, accessStop, accSec, e, egressSec, rev.reversed())
    }
}
