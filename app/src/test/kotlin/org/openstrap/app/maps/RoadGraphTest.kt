package org.openstrap.app.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadGraphTest {
    private fun road(vararg xy: Double, level: Int = 0) = Road(level, xy)

    /** The way from the point on the graph nearest (ax, ay) to the one nearest (bx, by), or null. */
    private fun way(g: RoadGraph, ax: Double, ay: Double, bx: Double, by: Double, limit: Double = 1_000.0): RoadGraph.Path? {
        val a = g.near(ax, ay, 5.0).minByOrNull { it.distance }!!
        val b = g.near(bx, by, 5.0).minByOrNull { it.distance }!!
        return g.paths(a, listOf(b), limit)[0]
    }

    @Test fun `roads that cross with no shared point meet where they cross`() {
        // A plus sign: east to west and north to south, each one straight piece.
        val g = RoadGraph.build(listOf(road(-100.0, 0.0, 100.0, 0.0), road(0.0, -100.0, 0.0, 100.0)))
        assertEquals(4, g.edges)
        // From the west end to the north end through the crossing: 100 + 100 m.
        assertEquals(200.0, way(g, -100.0, 0.0, 0.0, 100.0)!!.meters, 1e-6)
    }

    @Test fun `a bridge crosses the road under it without meeting it`() {
        val g = RoadGraph.build(listOf(road(-100.0, 0.0, 100.0, 0.0), road(0.0, -100.0, 0.0, 100.0, level = 1)))
        assertEquals(2, g.edges)
        assertNull(way(g, -100.0, 0.0, 0.0, 100.0))
    }

    @Test fun `a side street stopping a metre short joins the road`() {
        // Its end is 1.2 m short of the main road: tile rounding leaves gaps like it.
        val g = RoadGraph.build(listOf(road(-100.0, 0.0, 100.0, 0.0), road(20.0, -1.2, 20.0, -80.0)))
        // 80 m along the road, the 1.2 m gap, 58.8 m down the side street.
        assertEquals(140.0, way(g, -60.0, 0.0, 20.0, -60.0)!!.meters, 0.5)
    }

    @Test fun `a road cut at a tile's edge goes on in the next tile`() {
        // The same road from two tiles, each with it to just past its edge (tiles carry a buffer), rounded apart.
        val g = RoadGraph.build(listOf(road(-200.0, 0.0, 10.0, 0.0), road(-10.3, 0.4, 200.0, 0.4)))
        assertEquals(400.0, way(g, -200.0, 0.0, 200.0, 0.4)!!.meters, 2.0)
    }

    @Test fun `the same piece twice is one edge`() {
        assertEquals(1, RoadGraph.build(listOf(road(0.0, 0.0, 50.0, 0.0), road(0.0, 0.0, 50.0, 0.0))).edges)
    }

    @Test fun `a way longer than the limit is out of reach`() {
        val g = RoadGraph.build(listOf(road(0.0, 0.0, 100.0, 0.0, 100.0, 100.0)))
        assertNotNull(way(g, 0.0, 0.0, 100.0, 100.0, limit = 250.0))
        assertNull(way(g, 0.0, 0.0, 100.0, 100.0, limit = 150.0))
    }

    @Test fun `only the roads near the route are kept`() {
        val corridor = Corridor(listOf(0.0 to 0.0, 50.0 to 0.0), radius = 60.0)
        val g = RoadGraph.build(listOf(road(-100.0, 10.0, 100.0, 10.0), road(-100.0, 500.0, 100.0, 500.0)), corridor)
        assertEquals(1, g.edges)
        assertTrue(g.near(0.0, 10.0, 1.0).isNotEmpty())
    }
}
