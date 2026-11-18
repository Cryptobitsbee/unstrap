package org.openstrap.app.maps

/**
 * The lines of one layer of a Mapbox Vector Tile (spec 2.1), as [RoadGraph] needs them from OpenFreeMap's OpenMapTiles
 * `transportation` layer: each line's `class`, how high it runs ([Line.level]) and its points in tile units, 0 to
 * [Layer.extent] with y down. Points and polygons are skipped, and so is every tag but those three. The tile comes
 * already inflated; one that isn't a vector tile throws [IllegalArgumentException].
 */
internal object VectorTile {
    /**
     * One part of a line feature. [kind] is its `class` (`primary`, `minor`, `path`…). [level] is its `layer` tag, else 1
     * for a bridge and -1 for a tunnel, else 0: lines on different levels cross without meeting. [xy] holds x0, y0, x1,
     * y1… in tile units.
     */
    class Line(val kind: String?, val level: Int, val xy: IntArray)

    class Layer(val extent: Int, val lines: List<Line>)

    /** The layer called [name], or null when the tile has none. */
    fun layer(tile: ByteArray, name: String): Layer? {
        val r = Proto(tile, 0, tile.size)
        while (r.more()) {
            val key = r.varint()
            if (field(key) == TILE_LAYERS && wire(key) == LENGTH) {
                val span = r.span()
                layerAt(tile, span, name)?.let { return it }
            } else r.skip(wire(key))
        }
        return null
    }

    /** The layer encoded in tile[span] when it is [name]'s, else null without reading its features. */
    private fun layerAt(tile: ByteArray, span: IntRange, name: String): Layer? {
        val r = Proto(tile, span.first, span.last + 1)
        var layerName: String? = null
        var extent = DEFAULT_EXTENT
        val features = mutableListOf<IntRange>()
        val keys = mutableListOf<String>()
        val values = mutableListOf<Any?>()
        while (r.more()) {
            val key = r.varint()
            when {
                wire(key) == LENGTH && field(key) == LAYER_NAME -> layerName = r.string()
                wire(key) == LENGTH && field(key) == LAYER_FEATURES -> features += r.span()
                wire(key) == LENGTH && field(key) == LAYER_KEYS -> keys += r.string()
                wire(key) == LENGTH && field(key) == LAYER_VALUES -> values += value(tile, r.span())
                wire(key) == VARINT && field(key) == LAYER_EXTENT -> extent = r.varint().toInt()
                else -> r.skip(wire(key))
            }
        }
        if (layerName != name) return null
        require(extent > 0) { "extent $extent" }
        return Layer(extent, features.flatMap { feature(tile, it, keys, values) })
    }

    /** A feature's lines: none unless it is a line string. */
    private fun feature(tile: ByteArray, span: IntRange, keys: List<String>, values: List<Any?>): List<Line> {
        val r = Proto(tile, span.first, span.last + 1)
        var tags = LongArray(0)
        var type = 0L
        var geometry = LongArray(0)
        while (r.more()) {
            val key = r.varint()
            when {
                wire(key) == LENGTH && field(key) == FEATURE_TAGS -> tags = r.packed()
                wire(key) == VARINT && field(key) == FEATURE_TYPE -> type = r.varint()
                wire(key) == LENGTH && field(key) == FEATURE_GEOMETRY -> geometry = r.packed()
                else -> r.skip(wire(key))
            }
        }
        if (type != LINESTRING) return emptyList()
        var kind: String? = null
        var brunnel: String? = null
        var layerTag: Long? = null
        for (i in 0 until tags.size - 1 step 2) {
            val v = values.getOrNull(tags[i + 1].toInt())
            when (keys.getOrNull(tags[i].toInt())) {
                "class" -> kind = v as? String
                "brunnel" -> brunnel = v as? String
                "layer" -> layerTag = v as? Long
            }
        }
        val level = layerTag?.toInt() ?: when (brunnel) {
            "bridge" -> 1
            "tunnel" -> -1
            else -> 0
        }
        return parts(geometry).map { Line(kind, level, it) }
    }

    /** A line string's parts from its geometry commands (spec 4.3): MoveTo starts a part, LineTo adds to it. */
    internal fun parts(geometry: LongArray): List<IntArray> {
        val out = mutableListOf<IntArray>()
        val part = ArrayList<Int>()
        var x = 0
        var y = 0
        var i = 0
        while (i < geometry.size) {
            val command = geometry[i++]
            val id = (command and COMMAND_MASK).toInt()
            val count = (command ushr COMMAND_BITS).toInt()
            if (id == CLOSE_PATH) continue
            require(id == MOVE_TO || id == LINE_TO) { "command $id" }
            repeat(count) {
                require(i + 1 < geometry.size) { "geometry cut short" }
                x += zigzag(geometry[i++])
                y += zigzag(geometry[i++])
                if (id == MOVE_TO) {
                    if (part.size >= TWO_POINTS) out += part.toIntArray()
                    part.clear()
                }
                part += x
                part += y
            }
        }
        if (part.size >= TWO_POINTS) out += part.toIntArray()
        return out
    }

    /** A Value message (spec 4.1): its string, integer, number or flag; null for anything else. */
    private fun value(tile: ByteArray, span: IntRange): Any? {
        val r = Proto(tile, span.first, span.last + 1)
        var out: Any? = null
        while (r.more()) {
            val key = r.varint()
            when (field(key)) {
                VALUE_STRING -> out = r.string()
                VALUE_FLOAT -> out = Float.fromBits(r.fixed32()).toDouble()
                VALUE_DOUBLE -> out = Double.fromBits(r.fixed64())
                VALUE_INT, VALUE_UINT -> out = r.varint()
                VALUE_SINT -> out = zigzag(r.varint()).toLong()
                VALUE_BOOL -> out = r.varint() != 0L
                else -> r.skip(wire(key))
            }
        }
        return out
    }

    private fun field(key: Long) = (key ushr WIRE_BITS).toInt()
    private fun wire(key: Long) = (key and WIRE_MASK).toInt()
    private fun zigzag(n: Long): Int = ((n ushr 1) xor -(n and 1)).toInt()

    // Field numbers and wire types of vector_tile.proto 2.1.
    private const val TILE_LAYERS = 3
    private const val LAYER_NAME = 1
    private const val LAYER_FEATURES = 2
    private const val LAYER_KEYS = 3
    private const val LAYER_VALUES = 4
    private const val LAYER_EXTENT = 5
    private const val FEATURE_TAGS = 2
    private const val FEATURE_TYPE = 3
    private const val FEATURE_GEOMETRY = 4
    private const val VALUE_STRING = 1
    private const val VALUE_FLOAT = 2
    private const val VALUE_DOUBLE = 3
    private const val VALUE_INT = 4
    private const val VALUE_UINT = 5
    private const val VALUE_SINT = 6
    private const val VALUE_BOOL = 7
    private const val LINESTRING = 2L
    private const val MOVE_TO = 1
    private const val LINE_TO = 2
    private const val CLOSE_PATH = 7
    private const val COMMAND_BITS = 3
    private const val COMMAND_MASK = 7L
    private const val DEFAULT_EXTENT = 4096
    /** x and y of two points: the least a line is. */
    private const val TWO_POINTS = 4
    const val VARINT = 0
    const val FIXED64 = 1
    const val LENGTH = 2
    const val FIXED32 = 5
    private const val WIRE_BITS = 3
    private const val WIRE_MASK = 7L
}

/** A protocol buffer reader over bytes[pos until end]: just what [VectorTile] reads. Bad input throws [IllegalArgumentException]. */
private class Proto(private val bytes: ByteArray, private var pos: Int, private val end: Int) {
    fun more() = pos < end

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            require(pos < end) { "truncated varint" }
            val b = bytes[pos++].toInt() and BYTE
            result = result or ((b and LOW_SEVEN).toLong() shl shift)
            if (b and MORE == 0) return result
            shift += SEVEN
            require(shift < Long.SIZE_BITS) { "varint too long" }
        }
    }

    /** The bytes of the length-delimited field that follows, as an index range, skipped over. */
    fun span(): IntRange {
        val n = length()
        val from = pos
        pos += n
        return from until from + n
    }

    fun string(): String = span().let { String(bytes, it.first, it.last - it.first + 1, Charsets.UTF_8) }

    /** A packed repeated varint field. */
    fun packed(): LongArray {
        val span = span()
        val inner = Proto(bytes, span.first, span.last + 1)
        val out = ArrayList<Long>()
        while (inner.more()) out += inner.varint()
        return out.toLongArray()
    }

    fun fixed32(): Int {
        val from = advance(Int.SIZE_BYTES)
        var v = 0
        for (i in Int.SIZE_BYTES - 1 downTo 0) v = (v shl Byte.SIZE_BITS) or (bytes[from + i].toInt() and BYTE)
        return v
    }

    fun fixed64(): Long {
        val from = advance(Long.SIZE_BYTES)
        var v = 0L
        for (i in Long.SIZE_BYTES - 1 downTo 0) v = (v shl Byte.SIZE_BITS) or (bytes[from + i].toLong() and BYTE.toLong())
        return v
    }

    fun skip(wire: Int) {
        when (wire) {
            VectorTile.VARINT -> varint()
            VectorTile.FIXED64 -> advance(Long.SIZE_BYTES)
            VectorTile.LENGTH -> advance(length())
            VectorTile.FIXED32 -> advance(Int.SIZE_BYTES)
            else -> throw IllegalArgumentException("wire type $wire")
        }
    }

    /** The length of the field that follows, checked against what is left. */
    private fun length(): Int {
        val n = varint()
        require(n in 0..(end - pos).toLong()) { "field overruns" }
        return n.toInt()
    }

    /** Skips [n] bytes; where they began. */
    private fun advance(n: Int): Int {
        require(n in 0..end - pos) { "field overruns" }
        pos += n
        return pos - n
    }

    private companion object {
        const val BYTE = 0xFF
        const val LOW_SEVEN = 0x7F
        const val MORE = 0x80
        const val SEVEN = 7
    }
}
