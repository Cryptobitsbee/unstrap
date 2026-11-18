package org.openstrap.app.maps

import org.openstrap.app.data.Route
import java.io.ByteArrayOutputStream
import java.util.Random
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Small Mapbox Vector Tiles laid out as the server's are (vector_tile.proto 2.1), and phone walks over roads, for the tests. */
internal object TestTiles {
    /** A line feature: its `class`, optional `brunnel` and `layer`, and its parts as x, y pairs in tile units. */
    class Feature(
        val kind: String,
        val parts: List<List<Pair<Int, Int>>>,
        val brunnel: String? = null,
        val layer: Long? = null,
        val type: Int = 2,
    )

    fun tile(vararg layers: Pair<String, List<Feature>>, extent: Int = 4096): ByteArray {
        val out = Writer()
        for ((name, features) in layers) out.message(3, layer(name, features, extent))
        return out.bytes()
    }

    private fun layer(name: String, features: List<Feature>, extent: Int): ByteArray {
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any>()
        fun key(k: String) = (keys.indexOf(k).takeIf { it >= 0 } ?: keys.size.also { keys += k }).toLong()
        fun value(v: Any) = (values.indexOf(v).takeIf { it >= 0 } ?: values.size.also { values += v }).toLong()
        val encoded = features.map { f ->
            val tags = mutableListOf(key("class"), value(f.kind))
            f.brunnel?.let { tags += listOf(key("brunnel"), value(it)) }
            f.layer?.let { tags += listOf(key("layer"), value(it)) }
            // A name, which the reader skips, as real tiles carry plenty of tags.
            tags += listOf(key("name"), value("Some Street"))
            Writer().apply {
                varintField(1, 7)
                packed(2, tags)
                varintField(3, f.type.toLong())
                packed(4, geometry(f.parts))
            }.bytes()
        }
        return Writer().apply {
            varintField(15, 2)
            string(1, name)
            encoded.forEach { message(2, it) }
            keys.forEach { string(3, it) }
            values.forEach { v ->
                val inner = Writer()
                when (v) {
                    is String -> inner.string(1, v)
                    is Long -> inner.varintField(4, v)
                    else -> error("value $v")
                }
                message(4, inner.bytes())
            }
            varintField(5, extent.toLong())
        }.bytes()
    }

    private fun geometry(parts: List<List<Pair<Int, Int>>>): List<Long> {
        val out = mutableListOf<Long>()
        var x = 0
        var y = 0
        for (part in parts) {
            out += command(1, 1)
            out += listOf(zigzag(part[0].first - x), zigzag(part[0].second - y))
            x = part[0].first
            y = part[0].second
            out += command(2, part.size - 1)
            for ((px, py) in part.drop(1)) {
                out += listOf(zigzag(px - x), zigzag(py - y))
                x = px
                y = py
            }
        }
        return out
    }

    private fun command(id: Int, count: Int) = (id or (count shl 3)).toLong()
    private fun zigzag(n: Int) = ((n shl 1) xor (n shr 31)).toLong() and 0xFFFFFFFFL

    private class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(v: Long) {
            var n = v
            while (n and 0x7FL.inv() != 0L) {
                out.write(((n and 0x7F) or 0x80).toInt())
                n = n ushr 7
            }
            out.write(n.toInt())
        }

        fun varintField(field: Int, v: Long) {
            varint((field shl 3).toLong())
            varint(v)
        }

        fun message(field: Int, bytes: ByteArray) {
            varint((field shl 3 or 2).toLong())
            varint(bytes.size.toLong())
            out.write(bytes)
        }

        fun string(field: Int, s: String) = message(field, s.toByteArray())

        fun packed(field: Int, values: List<Long>) {
            val inner = Writer()
            values.forEach { inner.varint(it) }
            message(field, inner.bytes())
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    /**
     * A phone's fixes, one a second, of a walk at 1.4 m/s through [corners] (metres in [frame]), on the pavement [side] m
     * right of the road's centre. Most of the error drifts (multipath in a street, 30 s or so), [sigma] on each axis,
     * the rest is fresh each fix; the phone claims a little more than [sigma], with its Doppler speed and bearing.
     */
    fun walk(frame: LocalFrame, corners: List<Pair<Double, Double>>, seed: Long, sigma: Double = 8.0, side: Double = 4.0): List<Route.Point> {
        val rnd = Random(seed)
        var e = rnd.nextGaussian() * sigma
        var n = rnd.nextGaussian() * sigma
        val drift = 0.97
        val out = mutableListOf<Route.Point>()
        var t = 0L
        for ((a, b) in corners.zipWithNext()) {
            val length = hypot(b.first - a.first, b.second - a.second)
            val ux = (b.first - a.first) / length
            val uy = (b.second - a.second) / length
            var along = 0.0
            while (along < length) {
                val x = a.first + ux * along + uy * side
                val y = a.second + uy * along - ux * side
                e = drift * e + rnd.nextGaussian() * sigma * sqrt(1 - drift * drift)
                n = drift * n + rnd.nextGaussian() * sigma * sqrt(1 - drift * drift)
                val fx = x + e + rnd.nextGaussian() * 1.5
                val fy = y + n + rnd.nextGaussian() * 1.5
                val bearing = Math.toDegrees(atan2(ux, uy)).let { if (it < 0) it + 360 else it }
                out += Route.Point(
                    t, frame.lat(fy), frame.lon(fx), (sigma * 1.2).toFloat(),
                    speed = 1.4f, bearing = bearing.toFloat(), speedAccuracy = 0.8f, bearingAccuracy = 35f,
                )
                t++
                along += 1.4
            }
        }
        return out
    }

    /** The distance from (x, y) to the polyline through [line]. */
    fun distance(x: Double, y: Double, line: List<Pair<Double, Double>>): Double =
        line.zipWithNext().minOf { (a, b) -> Geometry.project(x, y, a.first, a.second, b.first, b.second).distance }

    /** [points] in [frame]'s metres, with a point every [step] m along the line between them. */
    fun along(frame: LocalFrame, points: List<Route.Point>, step: Double = 3.0): List<Pair<Double, Double>> {
        val xy = points.map { frame.east(it.lon) to frame.north(it.lat) }
        val out = mutableListOf<Pair<Double, Double>>()
        for ((a, b) in xy.zipWithNext()) {
            val l = hypot(b.first - a.first, b.second - a.second)
            val k = maxOf(1, (l / step).toInt())
            for (i in 0 until k) out += (a.first + (b.first - a.first) * i / k) to (a.second + (b.second - a.second) * i / k)
        }
        return out + xy.last()
    }
}
