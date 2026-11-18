package org.openstrap.app.maps

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openstrap.app.data.Route
import org.openstrap.app.maps.TestTiles.Feature
import java.io.IOException

class RouteSnapperTest {
    @get:Rule val temp = TemporaryFolder()

    private val x0 = 11723
    private val y0 = 7596
    private val tileJson = """{"tiles":["https://tiles.openfreemap.org/planet/v/{z}/{x}/{y}.pbf"]}"""

    /** A grid of streets every 160 tile units (about 93 m here), from 480 to 3520 each way, as minor roads. */
    private val streets = TestTiles.tile(
        "transportation" to (480..3520 step 160).flatMap { c ->
            listOf(Feature("minor", listOf(listOf(480 to c, 3520 to c))), Feature("minor", listOf(listOf(c to 480, c to 3520))))
        },
    )

    private fun lon(u: Int) = Tiles.lon(x0 + u / 4096.0)
    private fun lat(v: Int) = Tiles.lat(y0 + v / 4096.0)
    private val frame = LocalFrame(lat(2080), lon(800))

    /** Tile units to [frame]'s metres. */
    private fun at(u: Int, v: Int) = frame.east(lon(u)) to frame.north(lat(v))

    /** East along the street at 2080 for about 930 m, then south down the one at 2400 for about 560 m. */
    private val walked = listOf(at(800, 2080), at(2400, 2080), at(2400, 3040))

    private fun TestScope.snapper(tiles: () -> ByteArray?, asked: MutableList<String> = mutableListOf()): RouteSnapper {
        val road = RoadTiles(
            temp.root, "unstrap/test", io = StandardTestDispatcher(testScheduler),
            download = { url ->
                asked += url
                when {
                    url == RoadTiles.TILEJSON -> tileJson.toByteArray()
                    url.endsWith("/14/$x0/$y0.pbf") -> tiles() ?: throw IOException("offline")
                    else -> TestTiles.tile("transportation" to emptyList())
                }
            },
            log = {},
        )
        return RouteSnapper(road, work = StandardTestDispatcher(testScheduler), log = {})
    }

    @Test fun `a walk over the map's streets is drawn along them, worked out once`() = runTest {
        val asked = mutableListOf<String>()
        val snapper = snapper({ streets }, asked)
        val route = TestTiles.walk(frame, walked, seed = 11)
        val out = snapper.snap(route)
        assertTrue("$out", out is RouteSnapper.Outcome.Snapped)
        val line = TestTiles.along(frame, (out as RouteSnapper.Outcome.Snapped).line)
        val off = line.count { (x, y) -> TestTiles.distance(x, y, walked) > 8.0 }.toDouble() / line.size
        assertTrue("$off of the line off the streets", off < 0.08)
        val fetched = asked.size
        assertEquals(out, snapper.snap(route))
        assertEquals("nothing fetched the second time", fetched, asked.size)
    }

    @Test fun `offline it fails and tries again next time`() = runTest {
        var online = false
        val snapper = snapper({ streets.takeIf { online } })
        val route = TestTiles.walk(frame, walked, seed = 12)
        assertEquals(RouteSnapper.Outcome.Failed, snapper.snap(route))
        online = true
        assertTrue(snapper.snap(route) is RouteSnapper.Outcome.Snapped)
    }

    @Test fun `with no roads on the map it stays as recorded`() = runTest {
        val snapper = snapper({ TestTiles.tile("transportation" to emptyList()) })
        assertEquals(RouteSnapper.Outcome.NotOnRoads, snapper.snap(TestTiles.walk(frame, walked, seed = 13)))
        assertEquals(RouteSnapper.Outcome.NotOnRoads, snapper.snap(emptyList<Route.Point>()))
    }
}
