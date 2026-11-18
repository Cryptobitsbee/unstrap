package org.openstrap.app.maps

import android.util.Log
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.openstrap.app.data.catching
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

/** Web Mercator tiles at [ZOOM], the most detailed OpenFreeMap serves: every footpath is in them. */
internal object Tiles {
    const val ZOOM = 14
    private const val COUNT = 1 shl ZOOM

    /** The column of the tile holding [lon]. */
    fun x(lon: Double): Int = floor((lon + HALF_TURN) / FULL_TURN * COUNT).toInt().coerceIn(0, COUNT - 1)

    /** The row of the tile holding [lat]. */
    fun y(lat: Double): Int {
        val r = Math.toRadians(lat.coerceIn(-MAX_LAT, MAX_LAT))
        return floor((1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * COUNT).toInt().coerceIn(0, COUNT - 1)
    }

    /** The longitude [x] tiles from the west edge of the world, fractions inside a tile. */
    fun lon(x: Double): Double = x / COUNT * FULL_TURN - HALF_TURN

    /** The latitude [y] tiles from the north edge. */
    fun lat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / COUNT))))

    private const val HALF_TURN = 180.0
    private const val FULL_TURN = 360.0
    /** Web Mercator's edge. */
    private const val MAX_LAT = 85.05112878
}

/**
 * The road layer of OpenFreeMap's tiles around a route, for drawing it along the roads ([RouteSnapper]). The same tiles
 * the workout map loads, from the same server (`tiles.openfreemap.org`, docs/privacy.md, Network use): fetched at
 * [Tiles.ZOOM], at most four at a time and with unstrap's user agent, as OpenFreeMap's terms ask, and kept in [dir] for
 * [KEEP_DAYS] days, so a route looked at again costs nothing. Roads change slowly, so a weekly map update doesn't fetch
 * them again.
 */
class RoadTiles(
    private val dir: File,
    private val userAgent: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** The bytes at a URL; tests pass their own. */
    private val download: (suspend (String) -> ByteArray)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    /** One cache write at a time. */
    private val lock = Mutex()
    /** One TileJSON read at a time, so four tiles asked for at once read it once. */
    private val templateLock = Mutex()
    @Volatile private var template: Pair<String, Long>? = null

    /** The road layer of the tile at [x], [y] (zoom [Tiles.ZOOM]), from the cache or the server. Throws when it can't. */
    internal suspend fun roads(x: Int, y: Int): VectorTile.Layer = withContext(io) {
        val bytes = cached(x, y) ?: fetch(x, y)
        VectorTile.layer(inflated(bytes), LAYER) ?: VectorTile.Layer(DEFAULT_EXTENT, emptyList())
    }

    /** Every tile in [xs] by [ys], four at a time, or the exception of the first that failed. */
    internal suspend fun roads(xs: IntRange, ys: IntRange): Map<Pair<Int, Int>, VectorTile.Layer> = coroutineScope {
        val out = HashMap<Pair<Int, Int>, VectorTile.Layer>()
        for (batch in xs.flatMap { x -> ys.map { y -> x to y } }.chunked(PARALLEL)) {
            batch.map { (x, y) -> async { (x to y) to roads(x, y) } }.awaitAll().forEach { (at, layer) -> out[at] = layer }
        }
        out
    }

    private fun file(x: Int, y: Int) = File(dir, "${Tiles.ZOOM}-$x-$y.mvt")

    private fun cached(x: Int, y: Int): ByteArray? {
        val f = file(x, y)
        if (!f.isFile || clock() - f.lastModified() > KEEP_DAYS * DAY_MS) return null
        return runCatching { f.readBytes() }.getOrNull()
    }

    private suspend fun fetch(x: Int, y: Int): ByteArray {
        val url = urlTemplate().replace("{z}", "${Tiles.ZOOM}").replace("{x}", "$x").replace("{y}", "$y")
        val bytes = get(url)
        lock.withLock {
            dir.mkdirs()
            val tmp = File(dir, "${Tiles.ZOOM}-$x-$y.part")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(file(x, y))) tmp.delete()
            prune()
        }
        return bytes
    }

    /**
     * The tile URL from OpenFreeMap's TileJSON, which names the current weekly map. Read again once a day, and the last
     * one kept for when it can't be.
     */
    private suspend fun urlTemplate(): String = templateLock.withLock {
        template?.takeIf { clock() - it.second < DAY_MS }?.let { return@withLock it.first }
        val saved = File(dir, "tilejson.txt")
        val fresh = catching { tilesFrom(String(get(TILEJSON), Charsets.UTF_8)) }
            .onFailure { log("road tiles: TileJSON not read: $it") }.getOrNull()
        val url = fresh ?: saved.takeIf { it.isFile }?.readText()?.takeIf(::allowed) ?: throw IOException("no tile URL")
        if (fresh != null) {
            dir.mkdirs()
            runCatching { saved.writeText(fresh) }.onFailure { log("road tiles: TileJSON not kept: $it") }
        }
        template = url to clock()
        url
    }

    private suspend fun get(url: String): ByteArray {
        download?.let { return it(url) }
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_MS
            conn.readTimeout = READ_MS
            conn.setRequestProperty("User-Agent", userAgent)
            val code = conn.responseCode
            if (code !in HTTP_OK) throw IOException("HTTP $code from $url")
            return conn.inputStream.use { limited(it) }
        } finally {
            conn.disconnect()
        }
    }

    /** Deletes the oldest tiles once the cache holds more than [MAX_CACHE_BYTES], down to three quarters of it. */
    private fun prune() {
        val tiles = dir.listFiles { f -> f.name.endsWith(".mvt") }?.sortedBy { it.lastModified() } ?: return
        var total = tiles.sumOf { it.length() }
        if (total <= MAX_CACHE_BYTES) return
        for (f in tiles) {
            if (total <= MAX_CACHE_BYTES * PRUNE_TO_SHARE) break
            total -= f.length()
            f.delete()
        }
    }

    internal companion object {
        private const val TAG = "unstrap"
        const val LAYER = "transportation"
        const val TILEJSON = "https://tiles.openfreemap.org/planet"
        private const val HOST = "tiles.openfreemap.org"
        private const val DEFAULT_EXTENT = 4096
        const val KEEP_DAYS = 30L
        private const val DAY_MS = 24 * 3600 * 1000L
        private const val PARALLEL = 4
        private const val CONNECT_MS = 15_000
        private const val READ_MS = 20_000
        private val HTTP_OK = 200..299
        /** A z14 tile of a dense city centre is about 250 kB; anything past this isn't one. */
        private const val MAX_TILE_BYTES = 4 * 1024 * 1024
        private const val MAX_CACHE_BYTES = 40L * 1024 * 1024
        private const val PRUNE_TO_SHARE = 0.75
        private const val GZIP_1 = 0x1f
        private const val GZIP_2 = 0x8b
        private const val BUFFER = 64 * 1024

        /** The `tiles` URL of a TileJSON reply, when it is OpenFreeMap's over HTTPS; else it throws. */
        fun tilesFrom(json: String): String {
            val url = org.json.JSONObject(json).getJSONArray("tiles").getString(0)
            require(allowed(url)) { "unexpected tile URL $url" }
            return url
        }

        fun allowed(url: String): Boolean {
            val uri = runCatching { URI(url.replace("{", "").replace("}", "")) }.getOrNull() ?: return false
            return uri.scheme == "https" && uri.host == HOST && "{z}" in url && "{x}" in url && "{y}" in url
        }

        /** [bytes] gunzipped when they are gzip (a server may send either), else as they are. */
        fun inflated(bytes: ByteArray): ByteArray {
            val gzip = bytes.size > 2 && (bytes[0].toInt() and 0xFF) == GZIP_1 && (bytes[1].toInt() and 0xFF) == GZIP_2
            return if (gzip) GZIPInputStream(bytes.inputStream()).use { limited(it) } else bytes
        }

        private fun limited(input: InputStream): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_TILE_BYTES) throw IOException("tile too large")
            }
            return out.toByteArray()
        }
    }
}
