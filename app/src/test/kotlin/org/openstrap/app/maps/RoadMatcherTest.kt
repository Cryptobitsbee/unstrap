package org.openstrap.app.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.openstrap.app.data.Route
import kotlin.math.hypot

class RoadMatcherTest {
    private val frame = LocalFrame(12.97, 77.59)

    /** Streets every 100 m both ways over 0 to 600 m, as a city grid. */
    private val grid = RoadGraph.build(
        (0..6).flatMap { i ->
            listOf(Road(0, doubleArrayOf(0.0, i * 100.0, 600.0, i * 100.0)), Road(0, doubleArrayOf(i * 100.0, 0.0, i * 100.0, 600.0)))
        },
    )

    /** The share of [line], a point every 3 m, more than [far] m from the streets walked. */
    private fun offStreet(line: List<Route.Point>, walked: List<Pair<Double, Double>>, far: Double = 8.0): Double {
        val points = TestTiles.along(frame, line)
        return points.count { (x, y) -> TestTiles.distance(x, y, walked) > far }.toDouble() / points.size
    }

    private fun length(line: List<Route.Point>) = line.zipWithNext().sumOf { (a, b) -> Route.meters(a, b) }

    @Test fun `a walk round two corners stays on its streets where the GPS track wanders off them`() {
        // East along a street, up a cross street, east again: 250 + 200 + 250 m, with 8 m of drifting error.
        val walked = listOf(50.0 to 200.0, 300.0 to 200.0, 300.0 to 400.0, 550.0 to 400.0)
        for (seed in 1L..4L) {
            val track = Route.track(TestTiles.walk(frame, walked, seed))
            val m = RoadMatcher.match(grid, frame, track)!!
            assertTrue("seed $seed: ${m.onRoads} on roads", m.onRoads > 0.9)
            val snapped = offStreet(m.line, walked)
            val gps = offStreet(track, walked)
            assertTrue("seed $seed: $snapped of the line off the streets", snapped < 0.08)
            assertTrue("seed $seed: snapped $snapped, GPS $gps", snapped < gps)
            assertEquals("seed $seed", 700.0, length(m.line), 50.0)
        }
    }

    @Test fun `the line is timed from the track`() {
        val track = Route.track(TestTiles.walk(frame, listOf(50.0 to 200.0, 300.0 to 200.0, 300.0 to 400.0), seed = 7))
        val line = RoadMatcher.match(grid, frame, track)!!.line
        assertTrue(line.zipWithNext().all { (a, b) -> b.t >= a.t })
        assertEquals(track.first().t.toDouble(), line.first().t.toDouble(), 20.0)
        assertEquals(track.last().t.toDouble(), line.last().t.toDouble(), 1.0)
    }

    @Test fun `a short cut across a park stays across it`() {
        // Up a street, then straight across the middle of a block the map has no path through, then up the next street.
        val walked = listOf(100.0 to 20.0, 100.0 to 100.0, 200.0 to 200.0, 200.0 to 300.0)
        val track = Route.track(TestTiles.walk(frame, walked, seed = 3, sigma = 5.0, side = 0.0))
        val line = TestTiles.along(frame, RoadMatcher.match(grid, frame, track)!!.line)
        val middle = line.minOf { (x, y) -> hypot(x - 150.0, y - 150.0) }
        assertTrue("the line passes $middle m from the middle of the block", middle < 12.0)
    }

    @Test fun `a walk away from any road stays as recorded`() {
        val empty = RoadGraph.build(listOf(Road(0, doubleArrayOf(0.0, 2_000.0, 600.0, 2_000.0))))
        val track = Route.track(TestTiles.walk(frame, listOf(50.0 to 200.0, 350.0 to 200.0), seed = 5))
        val m = RoadMatcher.match(empty, frame, track)!!
        assertEquals(0.0, m.onRoads, 0.0)
    }
}
