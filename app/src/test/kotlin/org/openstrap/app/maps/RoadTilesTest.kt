package org.openstrap.app.maps

import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.openstrap.app.maps.TestTiles.Feature
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.GZIPOutputStream

class RoadTilesTest {
    @get:Rule val temp = TemporaryFolder()

    private val tileJson = """{"tilejson":"3.0.0","tiles":["https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf"]}"""

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(bytes) } }.toByteArray()

    @Test fun `tile numbers at zoom 14`() {
        // Bengaluru's centre, checked against tiles.openfreemap.org.
        assertEquals(11723, Tiles.x(77.5946))
        assertEquals(7596, Tiles.y(12.9716))
        assertTrue(Tiles.lon(11723.0) <= 77.5946 && 77.5946 < Tiles.lon(11724.0))
        assertTrue(Tiles.lat(7596.0) >= 12.9716 && 12.9716 > Tiles.lat(7597.0))
    }

    @Test fun `only OpenFreeMap's own tile URL over HTTPS is used`() {
        assertEquals("https://tiles.openfreemap.org/planet/20261115_080022_pt/{z}/{x}/{y}.pbf", RoadTiles.tilesFrom(tileJson))
        assertThrows(IllegalArgumentException::class.java) { RoadTiles.tilesFrom(tileJson.replace("https:", "http:")) }
        assertThrows(IllegalArgumentException::class.java) { RoadTiles.tilesFrom(tileJson.replace("tiles.openfreemap.org", "example.com")) }
        assertFalse(RoadTiles.allowed("https://tiles.openfreemap.org/planet/1/2/3.pbf"))
    }

    @Test fun `a gzipped tile is inflated and a plain one left as it is`() {
        val tile = TestTiles.tile("transportation" to emptyList())
        assertArrayEquals(tile, RoadTiles.inflated(gzip(tile)))
        assertArrayEquals(tile, RoadTiles.inflated(tile))
    }

    @Test fun `a tile is fetched once, kept, and fetched again a month on`() = runTest {
        var later = 0L
        val asked = mutableListOf<String>()
        val tile = TestTiles.tile("transportation" to listOf(Feature("minor", listOf(listOf(0 to 0, 100 to 100)))))
        val tiles = RoadTiles(
            temp.root, "unstrap/test", io = StandardTestDispatcher(testScheduler),
            download = { url ->
                asked += url
                if (url == RoadTiles.TILEJSON) tileJson.toByteArray() else gzip(tile)
            },
            clock = { System.currentTimeMillis() + later }, log = {},
        )
        assertEquals(1, tiles.roads(11723, 7596).lines.size)
        assertEquals(listOf(RoadTiles.TILEJSON, "https://tiles.openfreemap.org/planet/20261115_080022_pt/14/11723/7596.pbf"), asked)
        assertEquals(1, tiles.roads(11723, 7596).lines.size)
        assertEquals(2, asked.size)
        later = (RoadTiles.KEEP_DAYS + 1) * 24 * 3600 * 1000L
        tiles.roads(11723, 7596)
        assertEquals("the TileJSON and the tile again", 4, asked.size)
    }

    @Test fun `with no network a tile fails, and the last TileJSON still names the tiles`() = runTest {
        var online = true
        val tile = TestTiles.tile("transportation" to emptyList())
        val tiles = { RoadTiles(
            temp.root, "unstrap/test", io = StandardTestDispatcher(testScheduler),
            download = { url -> if (!online) throw IOException("offline") else if (url == RoadTiles.TILEJSON) tileJson.toByteArray() else tile },
            log = {},
        ) }
        tiles().roads(1, 1)
        online = false
        assertTrue(runCatching { tiles().roads(2, 2) }.isFailure)
        // Already kept, it needs no network.
        assertTrue(tiles().roads(1, 1).lines.isEmpty())
    }
}
