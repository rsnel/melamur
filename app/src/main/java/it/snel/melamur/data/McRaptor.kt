package it.snel.melamur.data

/**
 * Multi-criteria RAPTOR (McRAPTOR, Delling/Pajor/Werneck). Each stop holds a
 * Pareto bag of labels over (arrival time, total walking seconds); rounds add
 * the "number of trips" criterion. This surfaces journeys that trade walking
 * against arrival time and transfers. Footpaths (origin access, inter-stop
 * transfers, destination egress) model walking.
 */
object McRaptor {
    private const val INF = Int.MAX_VALUE / 4
    private fun ceilMin(sec: Int) = (sec + 59) / 60

    private sealed class Par
    private class Access(val sec: Int) : Par()
    private class Board(val route: Int, val trip: Int, val boardPos: Int, val alightPos: Int, val src: Label) : Par()
    private class Transfer(val fromStop: Int, val sec: Int, val src: Label) : Par()
    private class Label(val stop: Int, val arr: Int, val walk: Int, val par: Par)

    /** true if a (arr,walk) dominates b. */
    private fun dom(aa: Int, aw: Int, ba: Int, bw: Int) = aa <= ba && aw <= bw

    private fun addLabel(bag: ArrayList<Label>, lab: Label): Boolean {
        for (x in bag) if (dom(x.arr, x.walk, lab.arr, lab.walk)) return false
        bag.removeAll { dom(lab.arr, lab.walk, it.arr, it.walk) }
        bag.add(lab); return true
    }

    fun query(d: RaptorData, access: List<IntArray>, egress: Map<Int, Int>,
              departMin: Int, maxRounds: Int = 6): List<RJourney> {
        val n = d.nStops
        val bags = Array(maxRounds + 1) { Array(n) { ArrayList<Label>() } }
        var marked = BooleanArray(n)
        val target = ArrayList<Label>()       // egress-applied labels (stop = egress stop, arr includes egress walk)
        val targetMeta = HashMap<Label, IntArray>() // label -> [egressStop, egressSec, srcArr]
        val targetSrc = HashMap<Label, Label>()

        fun collectTarget(k: Int) {
            for ((e, w) in egress) for (l in bags[k][e]) {
                val fin = Label(e, l.arr + ceilMin(w), l.walk + w, l.par)
                if (addLabel(target, fin)) { targetMeta[fin] = intArrayOf(e, w); targetSrc[fin] = l }
            }
        }

        for (a in access) {
            val s = a[0]
            if (addLabel(bags[0][s], Label(s, departMin + ceilMin(a[1]), a[1], Access(a[1])))) marked[s] = true
        }
        collectTarget(0)

        for (k in 1..maxRounds) {
            for (s in 0 until n) bags[k][s] = ArrayList(bags[k - 1][s])
            val prev = marked; marked = BooleanArray(n)
            val startPos = HashMap<Int, Int>()
            for (s in 0 until n) if (prev[s]) {
                val ra = d.routesAtStop[s]; var i = 0
                while (i < ra.size) { val r = ra[i]; val p = ra[i + 1]; val c = startPos[r]; if (c == null || p < c) startPos[r] = p; i += 2 }
            }
            for ((r, sp) in startPos) {
                val stops = d.routeStops[r]; val trips = d.routeTripsMin[r]
                val carried = HashMap<Int, Label>()   // trip -> source label (min walk kept)
                val carriedBoardPos = HashMap<Int, Int>()
                for (pos in sp until stops.size) {
                    val s = stops[pos]
                    for ((trip, src) in carried) {
                        val lab = Label(s, trips[trip][pos], src.walk, Board(r, trip, carriedBoardPos[trip]!!, pos, src))
                        if (addLabel(bags[k][s], lab)) marked[s] = true
                    }
                    for (l in bags[k - 1][s]) {
                        val et = earliestTrip(trips, pos, l.arr)
                        if (et >= 0) {
                            val cur = carried[et]
                            if (cur == null || l.walk < cur.walk) { carried[et] = l; carriedBoardPos[et] = pos }
                        }
                    }
                }
            }
            // footpaths from stops improved by routes this round
            val routeMarked = (0 until n).filter { marked[it] }
            for (s in routeMarked) {
                val tr = d.transfers[s]; var i = 0
                while (i < tr.size) {
                    val q = tr[i]; val w = tr[i + 1]
                    for (l in ArrayList(bags[k][s])) {
                        val lab = Label(q, l.arr + ceilMin(w), l.walk + w, Transfer(s, w, l))
                        if (addLabel(bags[k][q], lab)) marked[q] = true
                    }
                    i += 2
                }
            }
            collectTarget(k)
            if (marked.none { it }) break
        }

        return target.sortedWith(compareBy({ it.arr }, { it.walk }))
            .map { reconstruct(d, it, targetMeta[it]!!, targetSrc[it]!!) }
    }

    private fun earliestTrip(trips: Array<IntArray>, pos: Int, ready: Int): Int {
        for (t in trips.indices) if (trips[t][pos] >= ready) return t
        return -1
    }

    private fun reconstruct(d: RaptorData, fin: Label, meta: IntArray, src: Label): RJourney {
        val rev = ArrayList<Seg>()
        var cur: Label = src; var rides = 0; var accessStop = src.stop; var accSec = 0
        while (true) {
            when (val p = cur.par) {
                is Board -> { rev.add(SRide(p.route, p.trip, p.boardPos, p.alightPos)); rides++; cur = p.src }
                is Transfer -> { rev.add(STransfer(p.fromStop, cur.stop, p.sec)); cur = p.src }
                is Access -> { accessStop = cur.stop; accSec = p.sec; break }
            }
        }
        return RJourney(rides, fin.arr, accessStop, accSec, meta[0], meta[1], rev.reversed())
    }
}
