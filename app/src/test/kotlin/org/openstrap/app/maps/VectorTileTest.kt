package org.openstrap.app.maps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.openstrap.app.maps.TestTiles.Feature

class VectorTileTest {
    @Test fun `reads a layer's lines with their class, level and points`() {
        val tile = TestTiles.tile(
            "water" to listOf(Feature("lake", listOf(listOf(0 to 0, 10 to 10)))),
            "transportation" to listOf(
                Feature("minor", listOf(listOf(100 to 200, 150 to 200, 150 to 260))),
                Feature("primary", listOf(listOf(0 to 0, 4096 to 4096)), brunnel = "bridge"),
                Feature("path", listOf(listOf(5 to 5, 6 to 6)), brunnel = "tunnel"),
                Feature("service", listOf(listOf(1 to 1, 2 to 2)), layer = 2),
            ),
        )
        val layer = VectorTile.layer(tile, "transportation")!!
        assertEquals(4096, layer.extent)
        assertEquals(listOf("minor", "primary", "path", "service"), layer.lines.map { it.kind })
        assertEquals(listOf(0, 1, -1, 2), layer.lines.map { it.level })
        assertArrayEquals(intArrayOf(100, 200, 150, 200, 150, 260), layer.lines[0].xy)
        assertArrayEquals(intArrayOf(0, 0, 4096, 4096), layer.lines[1].xy)
    }

    @Test fun `a line in parts gives a line per part, and points past the tile's edge read right`() {
        val tile = TestTiles.tile(
            "transportation" to listOf(Feature("minor", listOf(listOf(-60 to 10, 30 to 10), listOf(30 to -40, 30 to 90, 80 to 90)))),
        )
        val lines = VectorTile.layer(tile, "transportation")!!.lines
        assertEquals(2, lines.size)
        assertArrayEquals(intArrayOf(-60, 10, 30, 10), lines[0].xy)
        assertArrayEquals(intArrayOf(30, -40, 30, 90, 80, 90), lines[1].xy)
    }

    @Test fun `points and polygons are left out`() {
        val tile = TestTiles.tile(
            "transportation" to listOf(
                Feature("pier", listOf(listOf(0 to 0, 10 to 0, 10 to 10)), type = 3),
                Feature("minor", listOf(listOf(0 to 0)), type = 1),
                Feature("minor", listOf(listOf(0 to 0, 9 to 9))),
            ),
        )
        assertEquals(1, VectorTile.layer(tile, "transportation")!!.lines.size)
    }

    @Test fun `no such layer is null, and a broken tile says so`() {
        assertNull(VectorTile.layer(TestTiles.tile("water" to emptyList()), "transportation"))
        assertNull(VectorTile.layer(ByteArray(0), "transportation"))
        val good = TestTiles.tile("transportation" to listOf(Feature("minor", listOf(listOf(0 to 0, 9 to 9)))))
        assertThrows(IllegalArgumentException::class.java) { VectorTile.layer(good.copyOf(good.size - 3), "transportation") }
    }
}
