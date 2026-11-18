package org.openstrap.app.maps

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openstrap.app.data.Route
import org.openstrap.app.data.catching
import kotlin.math.cos

/**
 * A phone workout's route along the streets it took, for the route card's Roads line: the map's roads around it
 * ([RoadTiles]) made into a graph ([RoadGraph]) and the smoothed track matched to them ([RoadMatcher]), all on the phone.
 * The route never leaves it; only the tiles of the area it covers are fetched, as the map fetches them. Distance, pace
 * and splits stay the track's: this draws the line, it doesn't measure it.
 */
class RouteSnapper(
    private val tiles: RoadTiles,
    private val work: CoroutineDispatcher = Dispatchers.Default,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** What came of matching a route. */
    sealed interface Outcome {
        /** The route along the roads, timed as the track is, so it colours by heart rate zone as the track does. */
        data class Snapped(val line: List<Route.Point>) : Outcome

        /** Too little of it lies along mapped roads (a field, a track), or it covers too large an area. */
        data object NotOnRoads : Outcome

        /** The roads couldn't be read: offline, say. Tried again the next time. */
        data object Failed : Outcome
    }

    private val lock = Mutex()
    private val done = object : LinkedHashMap<Key, Outcome>(CACHED, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Outcome>?) = size > CACHED
    }

    private data class Key(val first: Long, val last: Long, val size: Int, val lat: Double, val lon: Double)

    /** [route], the stored fixes, along the roads. One route at a time, each worked out once. */
    suspend fun snap(route: List<Route.Point>): Outcome = lock.withLock {
        val first = route.firstOrNull() ?: return@withLock Outcome.NotOnRoads
        val key = Key(first.t, route.last().t, route.size, first.lat, first.lon)
        done[key]?.let { return@withLock it }
        val outcome = catching { withContext(work) { match(route) } }
            .onFailure { log("route along roads: not worked out: $it") }
            .getOrDefault(Outcome.Failed)
        if (outcome != Outcome.Failed) done[key] = outcome
        outcome
    }

    private suspend fun match(route: List<Route.Point>): Outcome {
        val track = Route.track(route)
        val area = track.takeIf { Route.mappable(it) }?.let(::tilesAround)
        val m = area?.let { (xs, ys) -> matched(track, xs, ys) }
        return if (m != null && m.onRoads >= MIN_ON_ROADS && Route.mappable(m.line)) Outcome.Snapped(m.line) else Outcome.NotOnRoads
    }

    /** The tiles [track] and [PAD_M] around it lie in, columns by rows, or null when that is more than [MAX_TILES]. */
    private fun tilesAround(track: List<Route.Point>): Pair<IntRange, IntRange>? {
        val padLat = PAD_M / METERS_PER_DEGREE
        val padLon = padLat / cos(Math.toRadians(track[0].lat))
        val xs = Tiles.x(track.minOf { it.lon } - padLon)..Tiles.x(track.maxOf { it.lon } + padLon)
        val ys = Tiles.y(track.maxOf { it.lat } + padLat)..Tiles.y(track.minOf { it.lat } - padLat)
        return (xs to ys).takeIf { (xs.last - xs.first + 1) * (ys.last - ys.first + 1) <= MAX_TILES }
    }

    /** [track] matched to the roads of the tiles [xs] by [ys]. */
    private suspend fun matched(track: List<Route.Point>, xs: IntRange, ys: IntRange): RoadMatcher.Match? {
        val frame = LocalFrame(track[0].lat, track[0].lon)
        val roads = tiles.roads(xs, ys).flatMap { (at, layer) -> roadsOf(layer, at.first, at.second, frame) }
        val graph = RoadGraph.build(roads, Corridor(track.map { frame.east(it.lon) to frame.north(it.lat) }, CORRIDOR_M))
        return RoadMatcher.match(graph, frame, track)?.also { m ->
            log("route along roads: ${graph.edges} road pieces near it, ${Math.round(m.onRoads * PERCENT)} % of it on them")
        }
    }

    internal companion object {
        private const val TAG = "unstrap"
        /** Tiles reach this far past the route, so a road just beside its edge is in them. */
        private const val PAD_M = 100.0
        /** Roads this far from the track can't be where it went, so the graph leaves them out. */
        private const val CORRIDOR_M = 60.0
        private const val METERS_PER_DEGREE = 111_195.0
        /** Six by six tiles, about 14 km across at the equator and less further from it: a long ride stays as recorded. */
        private const val MAX_TILES = 36
        /** Under half the route on the roads, the GPS line says more. */
        private const val MIN_ON_ROADS = 0.5
        private const val PERCENT = 100
        private const val CACHED = 8
        private const val LOAD = 0.75f

        /** Roads you can walk, run or ride on: every class but motorways, rails, ferries and roads being built. */
        private val WALKABLE = setOf("trunk", "primary", "secondary", "tertiary", "minor", "service", "path", "track", "busway")

        /** [layer]'s walkable lines, from tile [x], [y], in [frame]'s metres. */
        internal fun roadsOf(layer: VectorTile.Layer, x: Int, y: Int, frame: LocalFrame): List<Road> =
            layer.lines.filter { it.kind in WALKABLE }.map { line ->
                val xy = DoubleArray(line.xy.size)
                for (i in 0 until line.xy.size / 2) {
                    xy[2 * i] = frame.east(Tiles.lon(x + line.xy[2 * i].toDouble() / layer.extent))
                    xy[2 * i + 1] = frame.north(Tiles.lat(y + line.xy[2 * i + 1].toDouble() / layer.extent))
                }
                Road(line.level, xy)
            }
    }
}
