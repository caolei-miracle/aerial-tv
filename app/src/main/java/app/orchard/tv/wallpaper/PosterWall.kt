package app.orchard.tv.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.core.graphics.createBitmap
import app.orchard.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The poster wall: a still picture of films' posters behind Home — a staggered grid on the left
 * with one poster spanning two rows as the hero, and one large picture on the right. The whole
 * wall is baked into one bitmap at [Wallpapers]' request, so it costs the TV no GPU while Home runs.
 *
 * Every rotation turn ([turn] set by Wallpapers from the chosen interval, default a day) reshuffles
 * the films with that turn as a seed, so the next wall is a completely new arrangement. There is no
 * tilt, no flip, no drift: what you see is one quiet, still image.
 *
 * The list is TMDB's weekly trending ([API]) when a key was built in; its pictures come from
 * image.tmdb.org, which answers from China. api.themoviedb.org does not: its name is answered with
 * a black hole here (DNS poisoning), so [tmdbGet] speaks TLS to a known address while still asking
 * for the name. The 24 posters kept in assets are the offline fallback when there is no key or no
 * network: a wall is always possible.
 *
 * Coordinates are of the 1920×1080 design ([BASE_W], [BASE_H]), scaled to whatever Home is drawn at.
 */
object PosterWall {
    private const val TAG = "AerialPosters"

    const val ID = "posters"

    // ---- the wall's shape, at 1920×1080 ----
    private const val BASE_W = 1920f
    private const val BASE_H = 1080f
    private const val LEFT_W = 1190f
    private const val COLS = 6
    private const val ROWS = 4
    private const val GAP = 8f
    /** The hero occupies a 2×2 block, starting at this column and row. */
    private const val HERO_C = 2
    private const val HERO_R = 0
    private const val HERO_SPAN = 2

    /** Posters on the wall. The offline set in assets is this many. */
    const val SLOTS = COLS * ROWS - HERO_SPAN * HERO_SPAN + 1 + 1 // grid cells (hero counts once) + right picture
    private const val KEEP = 40
    private const val REFRESH_MS = 12 * 60 * 60 * 1000L

    private const val API = "https://api.themoviedb.org/3"
    private const val HOST = "api.themoviedb.org"
    /** Addresses that have answered for [HOST]; the first is tried, then the ones DNS-over-HTTPS gives. */
    private val KNOWN_IPS = listOf("18.239.50.21")
    private const val IMG = "https://image.tmdb.org/t/p/w500"

    class Poster(val id: Int, val title: String)

    /** A rectangle on the wall, in the pixels of the draw it is for. */
    private class Cell(val left: Float, val top: Float, val w: Float, val h: Float)

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("posters", Context.MODE_PRIVATE) }
    private val dir by lazy { File(app.filesDir, "posters").apply { mkdirs() } }

    var posters by mutableStateOf<List<Poster>>(emptyList()); private set
    /** Goes up when the films change: the wall and its card are made again. */
    var version by mutableIntStateOf(0); private set
    /** Told once a new set of films is in, so the wall and its card are made again. */
    var onNewWall: (() -> Unit)? = null
    private var refreshedAt = 0L
    private var started = false

    /** The rotation turn the current wall was baked for; -1 until one is. */
    private var bakedTurn = Long.MIN_VALUE
    private var bakedW = 0
    private var bakedH = 0
    private var bakedBmp: Bitmap? = null
    private val tiles = HashMap<String, Bitmap>()
    private val offline by lazy { app.assets.list("posters").orEmpty().filter { it.endsWith(".jpg", true) }.sorted() }

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        refreshedAt = prefs.getLong("refreshed", 0L)
        posters = readIndex().ifEmpty { offlineIndex() }
        Log.i(TAG, "init: ${posters.size} posters (${if (BuildConfig.TMDB_KEY.isBlank()) "offline" else "tmdb"})")
    }

    fun ready(): Boolean = posters.size >= SLOTS

    private fun file(p: Poster): File = File(dir, "${p.id}.jpg")

    /** The 24 posters shipped in assets, as a list; used until TMDB answers (or instead of it). */
    private fun offlineIndex(): List<Poster> = offline.map { name ->
        val id = name.removeSuffix(".jpg").toIntOrNull() ?: name.hashCode()
        Poster(id, "")
    }

    /** Decodes a poster from the kept file, or from assets when it was never downloaded. */
    private fun source(p: Poster): File? {
        val f = file(p)
        if (f.exists()) return f
        // An asset poster has no downloaded file: decode it straight from assets at read time.
        return null
    }

    private fun loadBitmap(p: Poster): Bitmap? {
        val f = file(p)
        if (f.exists()) return runCatching { android.graphics.BitmapFactory.decodeFile(f.path) }.getOrNull()
        val name = offline.firstOrNull { it.removeSuffix(".jpg").toIntOrNull() == p.id }
            ?: return null
        return runCatching { app.assets.open("posters/$name").use { android.graphics.BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    /** A poster cut to a size, kept while it is on the wall. Call off the main thread. */
    private fun tile(p: Poster, w: Int, h: Int): Bitmap? {
        val key = "${p.id}@${w}x$h"
        tiles[key]?.let { return it }
        val b = loadBitmap(p)?.let { Images.cover(it, w, h) } ?: return null
        // One size is in use at a time; a kept wall's pictures go when another size is asked for.
        if (tiles.keys.none { it.substringAfter('@') == key.substringAfter('@') }) tiles.clear()
        tiles[key] = b
        return b
    }

    /**
     * The whole wall as one still picture at [w]×[h]. [turn] is the rotation slot: it seeds a fresh
     * shuffle, so each turn is a completely different wall. A bake for the same turn and size is
     * reused. The darkening is drawn in, as a film's picture is not shaded again by Home.
     */
    fun still(w: Int, h: Int, turn: Long): Bitmap? {
        if (!ready()) return null
        if (bakedBmp != null && turn == bakedTurn && w == bakedW && h == bakedH) return bakedBmp
        val sx = w / BASE_W
        val sy = h / BASE_H
        val gap = GAP

        val gridW = LEFT_W * sx
        val cellW = (gridW - gap * (COLS - 1) * sx) / COLS
        val cellH = (h - gap * (ROWS - 1) * sy) / ROWS
        val heroW = cellW * HERO_SPAN + gap * sx * (HERO_SPAN - 1)
        val heroH = cellH * HERO_SPAN + gap * sy * (HERO_SPAN - 1)

        // Shuffle with the turn: the same turn always gives the same wall, the next gives a new one.
        val shuffled = posters.toMutableList().also {
            val rng = java.util.Random(turn)
            java.util.Collections.shuffle(it, rng)
        }
        val out = createBitmap(w, h)
        val c = Canvas(out)
        c.drawColor(BACKGROUND)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)

        fun draw(p: Poster, cell: Cell) {
            val bmp = tile(p, cell.w.roundToInt().coerceAtLeast(1), cell.h.roundToInt().coerceAtLeast(1)) ?: return
            c.drawBitmap(bmp, null, RectF(cell.left, cell.top, cell.left + cell.w, cell.top + cell.h), paint)
        }

        var idx = 0
        // The hero: a 2×2 block, taken out of the grid first.
        if (idx < shuffled.size) {
            draw(shuffled[idx++], Cell(
                HERO_C * (cellW + gap * sx),
                HERO_R * (cellH + gap * sy),
                heroW, heroH,
            ))
        }
        // The rest of the grid, skipping the cells the hero covers.
        for (r in 0 until ROWS) {
            for (col in 0 until COLS) {
                if (col in HERO_C until HERO_C + HERO_SPAN && r in HERO_R until HERO_R + HERO_SPAN) continue
                if (idx >= shuffled.size) break
                draw(shuffled[idx++], Cell(
                    col * (cellW + gap * sx),
                    r * (cellH + gap * sy),
                    cellW, cellH,
                ))
            }
        }
        // The large picture on the right: a whole poster filling the column.
        val rx = LEFT_W * sx + gap * sx
        if (idx < shuffled.size) {
            draw(shuffled[idx++], Cell(rx, 0f, w - rx, h.toFloat()))
        }

        grade(c, w, h)
        bakedBmp = out
        bakedTurn = turn
        bakedW = w
        bakedH = h
        return out
    }

    private val BACKGROUND = 0xFF08080B.toInt()

    /**
     * Darkens the wall the way the wallpaper needs it: the corners, so the wall does not read as a
     * bright rectangle; the top, so the clock and greeting sit on quiet; the bottom, under the app
     * rows, where the plate and the glass come.
     */
    private fun grade(c: Canvas, w: Int, h: Int) {
        val fw = w.toFloat()
        val fh = h.toFloat()
        c.drawRect(0f, 0f, fw, fh, Paint().apply {
            shader = RadialGradient(
                fw / 2f, fh / 2f, max(fw, fh) * 0.72f,
                intArrayOf(0x00000000, 0x00000000, 0x6B000000),
                floatArrayOf(0f, 0.46f, 1f), Shader.TileMode.CLAMP,
            )
        })
        val top = fh * 0.24f
        c.drawRect(0f, 0f, fw, top, Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, top,
                intArrayOf(0xF208080B.toInt(), 0x8C08080B.toInt(), 0x0008080B),
                floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP,
            )
        })
        val bottom = fh * 0.5f
        c.drawRect(0f, fh - bottom, fw, fh, Paint().apply {
            shader = LinearGradient(
                0f, fh - bottom, 0f, fh,
                intArrayOf(0x0007070B, 0x4D07070B, 0xC707070B.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP,
            )
        })
    }

    // ---- the list ----

    fun ensureFresh(networkCameUp: Boolean = false) {
        if (!started || BuildConfig.TMDB_KEY.isBlank()) return
        val since = System.currentTimeMillis() - refreshedAt
        if (posters.size >= KEEP && since < REFRESH_MS && !networkCameUp) return
        if (since < 10 * 60 * 1000L && posters.size >= SLOTS) return
        io.launch { refresh() }
    }

    private val io = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var refreshing = false

    private suspend fun refresh() {
        if (refreshing) return
        refreshing = true
        try {
            val found = withContext(Dispatchers.IO) { fetchList() }
            if (found.isEmpty()) return
            val kept = mutableListOf<Poster>()
            found.take(KEEP).forEach { (id, title, path) ->
                val f = File(dir, "$id.jpg")
                if (!f.exists() && !download(IMG + path, f)) return@forEach
                kept += Poster(id, title)
            }
            if (kept.size < SLOTS) return
            writeIndex(kept)
            val keep = kept.map { "${it.id}.jpg" }.toSet()
            dir.listFiles { f -> f.extension == "jpg" && f.name !in keep }?.forEach { it.delete() }
            posters = kept
            tiles.clear()
            bakedBmp = null
            version++
            onNewWall?.invoke()
            refreshedAt = System.currentTimeMillis()
            prefs.edit { putLong("refreshed", refreshedAt) }
            Log.i(TAG, "kept ${kept.size} posters from TMDB")
        } catch (e: Exception) {
            Log.w(TAG, "the poster list could not be read", e)
        } finally {
            refreshing = false
        }
    }

    /** (id, title, poster_path) of the week's films and series. */
    private fun fetchList(): List<Triple<Int, String, String>> {
        val out = mutableListOf<Triple<Int, String, String>>()
        val seen = HashSet<Int>()
        for (page in 1..2) {
            val body = tmdbGet("/trending/all/week?language=zh-CN&page=$page") ?: break
            val results = runCatching { JSONObject(body).getJSONArray("results") }.getOrNull() ?: break
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                val path = o.optString("poster_path")
                val id = o.optInt("id")
                if (path.isBlank() || id == 0 || !seen.add(id)) continue
                val title = o.optString("title").ifBlank { o.optString("name") }
                out += Triple(id, title, path)
                if (out.size >= KEEP) return out
            }
        }
        return out
    }

    private fun download(url: String, to: File): Boolean = runCatching {
        val bytes = httpGetBytes(url, pinned = false)
        check(bytes.isNotEmpty())
        check(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) != null)
        val part = File(to.path + ".part")
        part.writeBytes(bytes)
        check(part.renameTo(to))
        true
    }.onFailure { Log.w(TAG, "no poster from $url", it) }.getOrDefault(false)

    private fun readIndex(): List<Poster> = runCatching {
        val arr = JSONArray(File(dir, "index.json").readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val p = Poster(o.getInt("id"), o.optString("title"))
            if (File(dir, "${p.id}.jpg").exists()) p else null
        }
    }.getOrDefault(emptyList())

    private fun writeIndex(list: List<Poster>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title)) }
        File(dir, "index.json").writeText(arr.toString())
    }

    // ---- talking to TMDB ----

    private val KEY get() = BuildConfig.TMDB_KEY

    private fun tmdbGet(path: String): String? {
        val tried = mutableListOf<String>()
        val remembered = prefs.getString("ip", null)
        for (ip in (listOfNotNull(remembered) + KNOWN_IPS).distinct()) {
            val url = "$API$path&api_key=$KEY"
            runCatching { return httpGet(url, pinned = true, ip = ip) }
                .onSuccess { prefs.edit { putString("ip", ip) } }
                .onFailure { Log.w(TAG, "$ip: ${it.javaClass.simpleName} ${it.message}"); tried += ip }
        }
        for (ip in resolveOverHttps()) {
            val url = "$API$path&api_key=$KEY"
            runCatching { return httpGet(url, pinned = true, ip = ip) }
                .onSuccess { prefs.edit { putString("ip", ip) } }
                .onFailure { Log.w(TAG, "$ip: ${it.javaClass.simpleName} ${it.message}"); tried += ip }
        }
        Log.w(TAG, "no address answered for $HOST (tried ${tried.joinToString()})")
        return null
    }

    private fun resolveOverHttps(): List<String> {
        for (resolver in listOf("1.1.1.1", "8.8.8.8")) {
            val body = runCatching {
                httpGet("https://$resolver/dns-query?name=$HOST&type=A", pinned = true, ip = resolver)
            }.onFailure { Log.w(TAG, "DoH $resolver: ${it.javaClass.simpleName} ${it.message}") }.getOrNull() ?: continue
            val answer = runCatching { JSONObject(body).getJSONArray("Answer") }.getOrNull() ?: continue
            val ips = (0 until answer.length()).mapNotNull { i ->
                val o = answer.getJSONObject(i)
                if (o.optInt("type") == 1) o.optString("data").takeIf { it.isNotBlank() } else null
            }
            if (ips.isNotEmpty()) return ips
        }
        return emptyList()
    }

    private const val UA = "Aerial/2.0 (Android TV)"

    private fun httpGet(url: String, pinned: Boolean, ip: String? = null): String =
        String(httpGetBytes(url, pinned, ip), Charsets.UTF_8)

    private fun httpGetBytes(url: String, pinned: Boolean, ip: String? = null): ByteArray {
        val uri = java.net.URI(url)
        val host = uri.host
        val target = (uri.rawPath.ifEmpty { "/" }) + (uri.rawQuery?.let { "?$it" } ?: "")
        val plain = Socket()
        try {
            plain.connect(InetSocketAddress(if (pinned) ip ?: host else host, 443), 10_000)
            plain.soTimeout = 20_000
            val factory = tls().socketFactory
            val ssl = factory.createSocket(plain, host, 443, true) as SSLSocket
            ssl.startHandshake()
            val session = ssl.session
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session)) {
                throw IOException("the certificate is not for $host")
            }
            ssl.outputStream.apply {
                write(
                    ("GET $target HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "User-Agent: $UA\r\n" +
                        "Accept: application/json, image/*\r\n" +
                        "Accept-Encoding: identity\r\n" +
                        "Connection: close\r\n\r\n").toByteArray()
                )
                flush()
            }
            val all = ssl.inputStream.readBytes()
            ssl.close()
            val split = indexOf(all, "\r\n\r\n".toByteArray())
            if (split < 0) throw IOException("no answer")
            val head = String(all, 0, split)
            val status = head.lineSequence().firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: 0
            if (status !in 200..299) throw IOException("HTTP $status")
            val body = all.copyOfRange(split + 4, all.size)
            val chunked = head.contains("Transfer-Encoding: chunked", ignoreCase = true)
            val out = if (chunked) dechunk(body) else body
            if (out.isEmpty()) throw IOException("an empty answer")
            return out
        } finally {
            runCatching { plain.close() }
        }
    }

    private var ssl: SSLContext? = null

    private fun tls(): SSLContext {
        ssl?.let { return it }
        val system = trustManagers(null).firstOrNull()
        val extra = runCatching {
            val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
            val cf = CertificateFactory.getInstance("X.509")
            for (name in app.assets.list("certs").orEmpty()) {
                val cert = app.assets.open("certs/$name").use { cf.generateCertificate(it) }
                ks.setCertificateEntry(name, cert)
            }
            trustManagers(ks).firstOrNull()
        }.onFailure { Log.w(TAG, "the kept roots could not be read", it) }.getOrNull()
        val manager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
                (system ?: extra)?.checkClientTrusted(chain, authType)
            }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system?.checkServerTrusted(chain, authType) ?: throw CertificateException("no roots")
                } catch (e: CertificateException) {
                    extra?.checkServerTrusted(chain, authType) ?: throw e
                }
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> =
                ((system?.acceptedIssuers ?: emptyArray()) + (extra?.acceptedIssuers ?: emptyArray()))
        }
        return SSLContext.getInstance("TLS").apply { init(null, arrayOf(manager), null) }.also { ssl = it }
    }

    private fun trustManagers(store: KeyStore?): List<X509TrustManager> = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(store) }
            .trustManagers.filterIsInstance<X509TrustManager>()
    }.getOrDefault(emptyList())

    private fun indexOf(hay: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..hay.size - needle.size) {
            for (j in needle.indices) if (hay[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun dechunk(body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(body.size)
        var i = 0
        while (i < body.size) {
            var lineEnd = i
            while (lineEnd + 1 < body.size && !(body[lineEnd] == 13.toByte() && body[lineEnd + 1] == 10.toByte())) lineEnd++
            val size = String(body, i, lineEnd - i).substringBefore(';').trim().toIntOrNull(16) ?: break
            i = lineEnd + 2
            if (size == 0) break
            val end = minOf(i + size, body.size)
            out.write(body, i, end - i)
            i = end + 2
        }
        return out.toByteArray()
    }
}
