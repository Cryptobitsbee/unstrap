package org.openstrap.app.maps

import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/** Metres east and north of [lat0], [lon0], flat as Route.smooth takes a route's area to be. */
internal class LocalFrame(val lat0: Double, val lon0: Double) {
    private val perDegNorth = Math.toRadians(1.0) * EARTH_M
    private val perDegEast = perDegNorth * cos(Math.toRadians(lat0))

    fun east(lon: Double) = (lon - lon0) * perDegEast
    fun north(lat: Double) = (lat - lat0) * perDegNorth
    fun lat(north: Double) = lat0 + north / perDegNorth
    fun lon(east: Double) = lon0 + east / perDegEast

    private companion object {
        const val EARTH_M = 6_371_000.0
    }
}

/** A road from the map in a [LocalFrame]: its [level] (see [VectorTile.Line.level]), x0, y0, x1, y1… in metres. */
internal class Road(val level: Int, val xy: DoubleArray)

/** Where a route went, as points in metres: what lies within [radius] of them is near it. */
internal class Corridor(xy: List<Pair<Double, Double>>, private val radius: Double) {
    private val byCell = HashMap<Long, MutableList<Pair<Double, Double>>>()

    init {
        for (p in xy) byCell.getOrPut(cellKey(cell(p.first, radius), cell(p.second, radius))) { mutableListOf() } += p
    }

    /** Whether the segment from (ax, ay) to (bx, by) passes within [radius] of the route. */
    fun near(ax: Double, ay: Double, bx: Double, by: Double): Boolean {
        for (cx in cells(minOf(ax, bx) - radius, maxOf(ax, bx) + radius, radius)) {
            for (cy in cells(minOf(ay, by) - radius, maxOf(ay, by) + radius, radius)) {
                val points = byCell[cellKey(cx, cy)] ?: continue
                if (points.any { (px, py) -> Geometry.project(px, py, ax, ay, bx, by).distance <= radius }) return true
            }
        }
        return false
    }
}

/**
 * The roads near a route as a graph a route can be matched along ([RoadMatcher]), in metres of one [LocalFrame]. A
 * vector tile's lines don't always share a point where they meet: a crossing can have none, a side street can stop a
 * metre short of the road it joins, and a line cut at a tile's edge goes on in the next tile. So [build] nodes them:
 * lines on the same level are split where they cross and where one ends within [JOIN_M] of another, and points within
 * [MERGE_M] of each other are one node. Every road is two-way, as it is on foot.
 */
internal class RoadGraph private constructor(
    private val xs: DoubleArray,
    private val ys: DoubleArray,
    private val edgeA: IntArray,
    private val edgeB: IntArray,
    private val edgeLength: DoubleArray,
    /** Per node, its edges. */
    private val edgesOf: Array<IntArray>,
    /** The edges by [INDEX_CELL_M] cell, for [near]. */
    private val index: Map<Long, IntArray>,
) {
    val edges: Int get() = edgeA.size

    /** A point on [edge], [t] of the way from its first node, at ([x], [y]), [distance] from where it was asked. */
    data class OnEdge(val edge: Int, val t: Double, val x: Double, val y: Double, val distance: Double)

    /** A way between two points on edges: [meters] long, through [via] (node indexes) in order. */
    class Path(val meters: Double, val via: IntArray)

    fun x(node: Int) = xs[node]
    fun y(node: Int) = ys[node]

    /** Every edge within [radius] of ([px], [py]), with its nearest point to it. */
    fun near(px: Double, py: Double, radius: Double): List<OnEdge> {
        val seen = HashSet<Int>()
        val out = mutableListOf<OnEdge>()
        for (cx in cells(px - radius, px + radius, INDEX_CELL_M)) {
            for (cy in cells(py - radius, py + radius, INDEX_CELL_M)) {
                for (e in index[cellKey(cx, cy)] ?: continue) {
                    if (!seen.add(e)) continue
                    val p = Geometry.project(px, py, xs[edgeA[e]], ys[edgeA[e]], xs[edgeB[e]], ys[edgeB[e]])
                    if (p.distance <= radius) out += OnEdge(e, p.t, p.x, p.y, p.distance)
                }
            }
        }
        return out
    }

    /**
     * The shortest ways from [from] to each of [to] along the roads, none longer than [limit] metres (a target out of
     * reach, or null, gets null). On the same edge it is straight along it, either way. Else a Dijkstra search from
     * both ends of [from]'s edge, stopped at [limit], so a matcher's many short searches stay cheap.
     */
    fun paths(from: OnEdge, to: List<OnEdge?>, limit: Double): Array<Path?> {
        val out = arrayOfNulls<Path>(to.size)
        // Per node, the targets an edge from it leads to and how far along that edge each lies.
        val wanted = HashMap<Int, MutableList<Pair<Int, Double>>>()
        to.forEachIndexed { j, b ->
            if (b == null) return@forEachIndexed
            val length = edgeLength[b.edge]
            if (b.edge == from.edge) {
                val m = abs(b.t - from.t) * length
                if (m <= limit) out[j] = Path(m, IntArray(0))
            } else {
                wanted.getOrPut(edgeA[b.edge]) { mutableListOf() } += j to b.t * length
                wanted.getOrPut(edgeB[b.edge]) { mutableListOf() } += j to (1 - b.t) * length
            }
        }
        if (wanted.isNotEmpty()) search(from, limit, wanted, out)
        return out
    }

    private fun search(from: OnEdge, limit: Double, wanted: Map<Int, List<Pair<Int, Double>>>, out: Array<Path?>) {
        val dist = HashMap<Int, Double>()
        val previous = HashMap<Int, Int>()
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        fun reach(node: Int, d: Double, before: Int) {
            if (d > limit || d >= (dist[node] ?: Double.MAX_VALUE)) return
            dist[node] = d
            if (before >= 0) previous[node] = before else previous.remove(node)
            queue += d to node
        }
        val length = edgeLength[from.edge]
        reach(edgeA[from.edge], from.t * length, -1)
        reach(edgeB[from.edge], (1 - from.t) * length, -1)
        val done = HashSet<Int>()
        while (queue.isNotEmpty()) {
            val (d, u) = queue.remove()
            if (!done.add(u)) continue
            wanted[u]?.forEach { (j, extra) ->
                val total = d + extra
                if (total <= limit && total < (out[j]?.meters ?: Double.MAX_VALUE)) out[j] = Path(total, route(u, previous))
            }
            for (e in edgesOf[u]) reach(if (edgeA[e] == u) edgeB[e] else edgeA[e], d + edgeLength[e], u)
        }
    }

    /** The nodes from the search's start to [last], in order. */
    private fun route(last: Int, previous: Map<Int, Int>): IntArray {
        val nodes = ArrayList<Int>()
        var n: Int? = last
        while (n != null) {
            nodes += n
            n = previous[n]
        }
        return nodes.asReversed().toIntArray()
    }

    companion object {
        /** A line ending this close to another joins it: tile rounding and cut lines leave gaps of a metre or so. */
        const val JOIN_M = 1.5
        /** Points this close are one node. */
        const val MERGE_M = 1.0
        /** Cells for finding lines that may meet. */
        private const val NODING_CELL_M = 50.0
        /** Cells for [near]. */
        private const val INDEX_CELL_M = 25.0

        /** The graph of [roads], keeping only their pieces within [corridor] when one is given. */
        fun build(roads: List<Road>, corridor: Corridor? = null): RoadGraph {
            val segments = Segments()
            for (road in roads) {
                val xy = road.xy
                for (i in 0 until xy.size / 2 - 1) {
                    val ax = xy[2 * i]
                    val ay = xy[2 * i + 1]
                    val bx = xy[2 * i + 2]
                    val by = xy[2 * i + 3]
                    val piece = ax != bx || ay != by
                    if (piece && corridor?.near(ax, ay, bx, by) != false) segments.add(ax, ay, bx, by, road.level)
                }
            }
            segments.node()
            return segments.graph()
        }
    }

    /** The road pieces as they're noded: each with the points it is cut at, then split into edges. */
    private class Segments {
        val ax = ArrayList<Double>()
        val ay = ArrayList<Double>()
        val bx = ArrayList<Double>()
        val by = ArrayList<Double>()
        val level = ArrayList<Int>()
        /** Per piece, (t, x, y) of each point another piece crosses or joins it at. */
        val cuts = HashMap<Int, MutableList<Triple<Double, Double, Double>>>()
        /** Where a piece's start or end, stopping just short of another piece, joins it: a short link to that point. */
        val startJoins = HashMap<Int, Pair<Double, Double>>()
        val endJoins = HashMap<Int, Pair<Double, Double>>()

        fun add(x0: Double, y0: Double, x1: Double, y1: Double, lvl: Int) {
            ax += x0
            ay += y0
            bx += x1
            by += y1
            level += lvl
        }

        private fun cut(s: Int, t: Double, x: Double, y: Double) {
            cuts.getOrPut(s) { mutableListOf() } += Triple(t, x, y)
        }

        /** Cuts every piece where another on its level crosses it or ends on it. */
        fun node() {
            val grid = HashMap<Long, MutableList<Int>>()
            for (s in ax.indices) {
                for (cx in cells(minOf(ax[s], bx[s]) - JOIN_M, maxOf(ax[s], bx[s]) + JOIN_M, NODING_CELL_M)) {
                    for (cy in cells(minOf(ay[s], by[s]) - JOIN_M, maxOf(ay[s], by[s]) + JOIN_M, NODING_CELL_M)) {
                        grid.getOrPut(cellKey(cx, cy)) { mutableListOf() } += s
                    }
                }
            }
            val pairs = HashSet<Long>()
            for (ids in grid.values) {
                for (i in ids.indices) for (j in i + 1 until ids.size) {
                    val s = minOf(ids[i], ids[j])
                    val u = maxOf(ids[i], ids[j])
                    if (level[s] == level[u] && pairs.add(pairKey(s, u))) meet(s, u)
                }
            }
        }

        /** Where pieces [s] and [u] meet: a crossing, or an end of one on the other. */
        private fun meet(s: Int, u: Int) {
            Geometry.cross(ax[s], ay[s], bx[s], by[s], ax[u], ay[u], bx[u], by[u])?.let { (t1, t2) ->
                val x = ax[s] + t1 * (bx[s] - ax[s])
                val y = ay[s] + t1 * (by[s] - ay[s])
                cut(s, t1, x, y)
                cut(u, t2, x, y)
            }
            endOn(s, start = true, u)
            endOn(s, start = false, u)
            endOn(u, start = true, s)
            endOn(u, start = false, s)
        }

        /**
         * [e]'s start or end, when it stops within [JOIN_M] of piece [s]: [s] is cut there, and [e] linked to the cut
         * unless they are close enough to be one node anyway.
         */
        private fun endOn(e: Int, start: Boolean, s: Int) {
            val x = if (start) ax[e] else bx[e]
            val y = if (start) ay[e] else by[e]
            val p = Geometry.project(x, y, ax[s], ay[s], bx[s], by[s])
            if (p.distance > JOIN_M) return
            if (p.t > 0 && p.t < 1) cut(s, p.t, p.x, p.y)
            if (p.distance >= MERGE_M) (if (start) startJoins else endJoins)[e] = p.x to p.y
        }

        /** Piece [s]'s points in order: a join before its start, its cuts, a join after its end. */
        private fun points(s: Int): List<Pair<Double, Double>> {
            val cut = cuts[s].orEmpty().sortedBy { it.first }.map { it.second to it.third }
            return listOfNotNull(startJoins[s]) + (ax[s] to ay[s]) + cut + (bx[s] to by[s]) + listOfNotNull(endJoins[s])
        }

        fun graph(): RoadGraph {
            val nodes = Nodes()
            val a = ArrayList<Int>()
            val b = ArrayList<Int>()
            val pairs = HashSet<Long>()
            for (s in ax.indices) {
                for ((p, q) in points(s).zipWithNext()) {
                    val na = nodes.at(p.first, p.second)
                    val nb = nodes.at(q.first, q.second)
                    if (na == nb || !pairs.add(pairKey(minOf(na, nb), maxOf(na, nb)))) continue
                    a += na
                    b += nb
                }
            }
            val xs = nodes.xs.toDoubleArray()
            val ys = nodes.ys.toDoubleArray()
            val length = DoubleArray(a.size) { hypot(xs[b[it]] - xs[a[it]], ys[b[it]] - ys[a[it]]) }
            val of = Array(xs.size) { ArrayList<Int>() }
            for (e in a.indices) {
                of[a[e]] += e
                of[b[e]] += e
            }
            val index = HashMap<Long, MutableList<Int>>()
            for (e in a.indices) {
                for (cx in cells(minOf(xs[a[e]], xs[b[e]]), maxOf(xs[a[e]], xs[b[e]]), INDEX_CELL_M)) {
                    for (cy in cells(minOf(ys[a[e]], ys[b[e]]), maxOf(ys[a[e]], ys[b[e]]), INDEX_CELL_M)) {
                        index.getOrPut(cellKey(cx, cy)) { mutableListOf() } += e
                    }
                }
            }
            val edgesOf = Array(xs.size) { of[it].toIntArray() }
            return RoadGraph(xs, ys, a.toIntArray(), b.toIntArray(), length, edgesOf, index.mapValues { it.value.toIntArray() })
        }
    }

    /** Node positions, a point within [MERGE_M] of one already there being that one. */
    private class Nodes {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        private val grid = HashMap<Long, MutableList<Int>>()

        fun at(x: Double, y: Double): Int {
            val cx = cell(x, MERGE_M)
            val cy = cell(y, MERGE_M)
            for (dx in -1..1) for (dy in -1..1) {
                val near = grid[cellKey(cx + dx, cy + dy)]?.firstOrNull { hypot(xs[it] - x, ys[it] - y) < MERGE_M }
                if (near != null) return near
            }
            xs += x
            ys += y
            grid.getOrPut(cellKey(cx, cy)) { mutableListOf() } += xs.size - 1
            return xs.size - 1
        }
    }
}

/** Plane geometry in metres for [RoadGraph] and [RoadMatcher]. */
internal object Geometry {
    /** The nearest point to (px, py) on the segment a–b: [t] of the way along, at ([x], [y]), [distance] away. */
    data class Projection(val t: Double, val x: Double, val y: Double, val distance: Double)

    // A point and a segment's two ends.
    @Suppress("LongParameterList")
    fun project(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Projection {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        val x = ax + t * dx
        val y = ay + t * dy
        return Projection(t, x, y, hypot(x - px, y - py))
    }

    /** Where the segments a–b and c–d cross inside both, as the share of the way along each, or null. */
    // Two segments, four points.
    @Suppress("LongParameterList")
    fun cross(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double): Pair<Double, Double>? {
        val rx = bx - ax
        val ry = by - ay
        val sx = dx - cx
        val sy = dy - cy
        val den = rx * sy - ry * sx
        if (abs(den) < PARALLEL) return null
        val t = ((cx - ax) * sy - (cy - ay) * sx) / den
        val u = ((cx - ax) * ry - (cy - ay) * rx) / den
        return if (inside(t) && inside(u)) t to u else null
    }

    /** Strictly between a segment's ends: a crossing at an end is a shared point, which [RoadGraph]'s nodes handle. */
    private fun inside(share: Double) = share > EDGE && share < 1 - EDGE

    private const val PARALLEL = 1e-12
    private const val EDGE = 1e-9
}

/** The cell of [v] in cells [size] wide. */
private fun cell(v: Double, size: Double): Int = floor(v / size).toInt()

/** The cells [size] wide from the one holding [from] to the one holding [to]. */
private fun cells(from: Double, to: Double, size: Double): IntRange = cell(from, size)..cell(to, size)

private fun cellKey(cx: Int, cy: Int): Long = cx.toLong() shl Int.SIZE_BITS or (cy.toLong() and LOW_32)

private fun pairKey(a: Int, b: Int): Long = a.toLong() shl Int.SIZE_BITS or b.toLong()

private const val LOW_32 = 0xFFFFFFFFL
