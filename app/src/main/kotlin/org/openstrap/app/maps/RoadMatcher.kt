package org.openstrap.app.maps

import org.openstrap.app.data.Route
import kotlin.math.abs
import kotlin.math.hypot

/**
 * A GPS route drawn along the roads it most likely took: hidden Markov map matching (Newson and Krumm, "Hidden Markov
 * map matching through noise and sparseness", ACM SIGSPATIAL 2009), the method GraphHopper's, OSRM's and Valhalla's map
 * matching are built on, here on the phone over the map's own roads ([RoadGraph]).
 *
 * The smoothed track ([Route.track]) is sampled every [SAMPLE_M]. Each sample's possible places are the nearest point of
 * each road within reach, the [CANDIDATES] nearest, and one "off the map" place at the sample itself, for a square, a
 * park or a path the map doesn't have. A place is likelier the nearer its road (Gaussian in the distance, sigma the fix's
 * accuracy but at least [SIGMA_M], since a pavement runs metres from the road's centre line). A move between two
 * samples' places is likelier the closer its way along the roads is to the straight line between the samples
 * (exp(−|route − straight| / [BETA_M])), and a move back along a road while the walk goes on is noise, so it pays
 * double. Leaving the map or coming back to it costs [OFF_SWITCH]. Viterbi picks the likeliest places, the line runs
 * between them along the roads, and out-and-back tails around a sample are taken out ([despike]).
 *
 * Tuned on simulated walks over the roads of a real OpenFreeMap tile (docs/design/route-snapping.md): with 6 to 15 m of
 * drifting GPS error, the share of the line off the street walked fell from 26–51 % (the smoothed track) to 6–13 %,
 * its length within 2–4 % of the walk's.
 */
internal object RoadMatcher {
    /** The [line] along the roads, timed from the track, and the share of samples it put on a road ([onRoads]). */
    class Match(val line: List<Route.Point>, val onRoads: Double)

    /** One sample of the track, in metres. */
    private class Sample(val x: Double, val y: Double, val t: Long, val sigma: Double)

    /** A place a sample may have been: on [road] (null off the map) at ([x], [y]), with its log-likelihood [emit]. */
    private class State(val road: RoadGraph.OnEdge?, val x: Double, val y: Double, val emit: Double)

    /** A move between two samples' states: its log-likelihood and its way along the roads (null for a straight one). */
    private class Move(val score: Double, val path: RoadGraph.Path?)

    /** The state chosen for each sample, and the move that reached it (null at the first and after a break). */
    private class Chosen(val states: IntArray, val moves: Array<Move?>)

    /** [track] matched to [graph]'s roads, or null with fewer than two samples. */
    fun match(graph: RoadGraph, frame: LocalFrame, track: List<Route.Point>): Match? {
        val samples = samples(track, frame)
        if (samples.size < 2) return null
        val layers = samples.map { states(graph, it) }
        return line(graph, frame, samples, layers, viterbi(graph, samples, layers))
    }

    /** The track every [SAMPLE_M], first and last always. */
    private fun samples(track: List<Route.Point>, frame: LocalFrame): List<Sample> {
        val out = mutableListOf<Sample>()
        for ((i, p) in track.withIndex()) {
            val s = Sample(frame.east(p.lon), frame.north(p.lat), p.t, maxOf(SIGMA_M, p.accuracy.toDouble()))
            val last = out.lastOrNull()
            if (last == null || i == track.lastIndex || hypot(s.x - last.x, s.y - last.y) >= SAMPLE_M) out += s
        }
        return out
    }

    private fun states(graph: RoadGraph, s: Sample): List<State> {
        val radius = (REACH_SIGMAS * s.sigma).coerceIn(MIN_REACH_M, MAX_REACH_M)
        val roads = graph.near(s.x, s.y, radius).sortedBy { it.distance }.take(CANDIDATES)
        val onRoads = roads.map { State(it, it.x, it.y, gaussian(it.distance / s.sigma)) }
        return onRoads + State(null, s.x, s.y, gaussian(OFF_MAP_Z))
    }

    private fun viterbi(graph: RoadGraph, samples: List<Sample>, layers: List<List<State>>): Chosen {
        val scores = ArrayList<DoubleArray>()
        val back = ArrayList<IntArray>()
        val moves = ArrayList<Array<Move?>>()
        scores += DoubleArray(layers[0].size) { layers[0][it].emit }
        back += IntArray(layers[0].size) { -1 }
        moves += arrayOfNulls<Move>(layers[0].size)
        for (i in 1 until layers.size) {
            val cur = layers[i]
            val score = DoubleArray(cur.size) { Double.NEGATIVE_INFINITY }
            val from = IntArray(cur.size) { -1 }
            val how = arrayOfNulls<Move>(cur.size)
            for ((a, sa) in layers[i - 1].withIndex()) {
                if (scores[i - 1][a] == Double.NEGATIVE_INFINITY) continue
                for ((b, move) in transitions(graph, samples[i - 1], samples[i], sa, cur).withIndex()) {
                    val v = scores[i - 1][a] + (move ?: continue).score + cur[b].emit
                    if (v > score[b]) {
                        score[b] = v
                        from[b] = a
                        how[b] = move
                    }
                }
            }
            // Nothing reaches this sample from the last (a gap in the roads, a jump): start afresh from here.
            if (score.all { it == Double.NEGATIVE_INFINITY }) for (b in cur.indices) score[b] = cur[b].emit
            scores += score
            back += from
            moves += how
        }
        val states = IntArray(layers.size)
        val reached = arrayOfNulls<Move>(layers.size)
        var b = best(scores.last())
        for (i in layers.indices.reversed()) {
            states[i] = b
            reached[i] = moves[i][b]
            if (i > 0) b = back[i][b].takeIf { it >= 0 } ?: best(scores[i - 1])
        }
        return Chosen(states, reached)
    }

    /** The moves from [sa] to each of [cur], one sample to the next; null where there's no way. */
    private fun transitions(graph: RoadGraph, prev: Sample, next: Sample, sa: State, cur: List<State>): Array<Move?> {
        val straight = hypot(next.x - prev.x, next.y - prev.y)
        val ways = sa.road?.let { graph.paths(it, cur.map { s -> s.road }, LIMIT_TIMES * straight + LIMIT_EXTRA_M) }
        return Array(cur.size) { b ->
            val sb = cur[b]
            when {
                sa.road == null && sb.road == null -> Move(0.0, null)
                sa.road == null || sb.road == null -> Move(-OFF_SWITCH, null)
                else -> ways?.get(b)?.let { way ->
                    val score = -abs(way.meters - straight) / BETA_M
                    Move(if (backwards(sa, sb, prev, next)) score - way.meters / BETA_M else score, way)
                }
            }
        }
    }

    /** Whether going from [a] to [b] heads back against the walk from [prev] to [next]. */
    private fun backwards(a: State, b: State, prev: Sample, next: Sample): Boolean {
        val vx = b.x - a.x
        val vy = b.y - a.y
        val ox = next.x - prev.x
        val oy = next.y - prev.y
        val nv = hypot(vx, vy)
        val no = hypot(ox, oy)
        return nv > 1 && no > 1 && (vx * ox + vy * oy) / (nv * no) < BACKWARDS_COS
    }

    /** The line through the chosen states along their ways, timed, as route points. */
    private fun line(graph: RoadGraph, frame: LocalFrame, samples: List<Sample>, layers: List<List<State>>, chosen: Chosen): Match {
        val xs = ArrayList<Double>()
        val ys = ArrayList<Double>()
        // A sample's own time where the line passes it, NaN at the road points between.
        val ts = ArrayList<Double>()
        fun add(x: Double, y: Double, t: Double) {
            if (xs.isNotEmpty() && xs.last() == x && ys.last() == y) {
                if (!t.isNaN()) ts[ts.lastIndex] = t
                return
            }
            xs += x
            ys += y
            ts += t
        }
        var onRoads = 0
        for (i in layers.indices) {
            val s = layers[i][chosen.states[i]]
            if (s.road != null) onRoads++
            chosen.moves[i]?.path?.via?.forEach { n -> add(graph.x(n), graph.y(n), Double.NaN) }
            add(s.x, s.y, samples[i].t.toDouble())
        }
        val kept = despike(xs, ys)
        val times = timed(xs, ys, ts, kept)
        val line = kept.mapIndexed { k, i -> Route.Point(Math.round(times[k]), frame.lat(ys[i]), frame.lon(xs[i]), 0f) }
        return Match(line, onRoads.toDouble() / layers.size)
    }

    /**
     * The indexes of the points to keep. An out-and-back tail, where the line turns back by more than 150° and its shorter
     * arm, under [SPIKE_ARM_M], lies along the longer one, is noise around a sample, not where the walk went.
     */
    private fun despike(xs: List<Double>, ys: List<Double>): List<Int> {
        var kept = xs.indices.toList()
        var changed = true
        while (changed && kept.size > 2) {
            changed = false
            val out = mutableListOf(kept.first())
            for (k in 1 until kept.size - 1) {
                if (spike(xs, ys, out.last(), kept[k], kept[k + 1])) changed = true else out += kept[k]
            }
            out += kept.last()
            kept = out
        }
        return kept
    }

    /** Whether b, between a and c, is a tail's tip ([despike]), or where a stood again. */
    private fun spike(xs: List<Double>, ys: List<Double>, a: Int, b: Int, c: Int): Boolean {
        val l1 = hypot(xs[b] - xs[a], ys[b] - ys[a])
        val l2 = hypot(xs[c] - xs[b], ys[c] - ys[b])
        return when {
            l1 < SAME_M -> true
            l2 < SAME_M -> false
            else -> {
                val cos = ((xs[b] - xs[a]) * (xs[c] - xs[b]) + (ys[b] - ys[a]) * (ys[c] - ys[b])) / (l1 * l2)
                val along = Geometry.project(xs[c], ys[c], xs[a], ys[a], xs[b], ys[b]).distance < ON_LINE_M ||
                    Geometry.project(xs[a], ys[a], xs[b], ys[b], xs[c], ys[c]).distance < ON_LINE_M
                cos < SPIKE_COS && minOf(l1, l2) < SPIKE_ARM_M && along
            }
        }
    }

    /** Times for the [kept] points: the samples' own, the road points between them spread by distance. */
    private fun timed(xs: List<Double>, ys: List<Double>, ts: List<Double>, kept: List<Int>): DoubleArray {
        val out = DoubleArray(kept.size) { ts[kept[it]] }
        val along = DoubleArray(kept.size)
        for (k in 1 until kept.size) along[k] = along[k - 1] + hypot(xs[kept[k]] - xs[kept[k - 1]], ys[kept[k]] - ys[kept[k - 1]])
        if (out[0].isNaN()) out[0] = out.firstOrNull { !it.isNaN() } ?: 0.0
        var last = 0
        for (k in 1 until kept.size) {
            if (out[k].isNaN() && k < kept.lastIndex) continue
            if (out[k].isNaN()) out[k] = out[last]
            val span = along[k] - along[last]
            for (m in last + 1 until k) {
                out[m] = if (span <= 0) out[last] else out[last] + (out[k] - out[last]) * (along[m] - along[last]) / span
            }
            last = k
        }
        return out
    }

    /** Samples this far apart along the track. Sparser follows the roads better, denser follows short turns. */
    const val SAMPLE_M = 20.0
    /** The least sigma for a fix: a pavement runs metres from the road's centre line, the line the map has. */
    const val SIGMA_M = 7.0
    /** Newson and Krumm's beta: how much longer or shorter than the straight line a way along the roads may be. */
    const val BETA_M = 4.0
    /** Roads up to this many sigmas away can be where a sample was, within [MIN_REACH_M] to [MAX_REACH_M]. */
    private const val REACH_SIGMAS = 3.0
    private const val MIN_REACH_M = 20.0
    private const val MAX_REACH_M = 40.0
    /** The nearest roads each sample may be on. */
    private const val CANDIDATES = 8
    /** "Off the map" is as likely as a road this many sigmas away: nearer roads win, farther ones lose to it. */
    private const val OFF_MAP_Z = 2.6
    /** The price, in log-likelihood, of leaving the roads or coming back to them. */
    private const val OFF_SWITCH = 6.0
    /** A way along the roads is searched up to this many times the straight line, plus [LIMIT_EXTRA_M]. */
    private const val LIMIT_TIMES = 2.0
    private const val LIMIT_EXTRA_M = 30.0
    /** Heading more than about 107° from the walk's own direction is going back. */
    private const val BACKWARDS_COS = -0.3
    /** A turn back sharper than 150°. */
    private const val SPIKE_COS = -0.866
    private const val SPIKE_ARM_M = 15.0
    private const val ON_LINE_M = 1.5
    /** Points closer than this are one. */
    private const val SAME_M = 1e-6
}

/** The log-likelihood, up to a constant, of a Gaussian error [z] sigmas wide. */
private fun gaussian(z: Double) = -z * z / 2

/** The index of the highest score. */
private fun best(scores: DoubleArray): Int = scores.indices.maxByOrNull { scores[it] } ?: 0
