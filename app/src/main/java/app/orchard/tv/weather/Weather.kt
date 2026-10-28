package app.orchard.tv.weather

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind { Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder }

/** One of the days ahead, as the forecast strip shows it. */
data class WeatherDay(val date: LocalDate, val kind: WeatherKind, val high: Int, val low: Int)

/**
 * What Home shows, and what the weather page details. [days] holds today and the days after it
 * (Open-Meteo gives up to 16; Aerial asks for a week); [aqi] is China's AQI worked out from PM2.5
 * and PM10, the scale the owner reads on a Chinese phone. [high] and [low] are today's, shown under
 * the large clock; null in a reading saved by an older version.
 */
data class WeatherNow(
    val celsius: Int,
    val kind: WeatherKind,
    val day: Boolean,
    val high: Int? = null,
    val low: Int? = null,
    val humidity: Int? = null,
    val apparent: Int? = null,
    val windKmh: Int? = null,
    val windDir: Int? = null,
    val days: List<WeatherDay> = emptyList(),
    val aqi: Int? = null,
    val pm25: Int? = null,
    val fetchedAt: Long = 0L,
) {
    /** The days after today, as the clock's one quiet line shows them (at most three). */
    val ahead: List<WeatherDay> get() = days.drop(1).take(3)
}

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    /** How many days the forecast holds, today included. */
    const val FORECAST_DAYS = 7

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            now = prefs.getString("reading", null)?.let { parse(it) } ?: legacy()
        }
    }

    /** A reading saved by a version that kept only the temperature, today's high and low. */
    private fun legacy(): WeatherNow? {
        val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull() ?: return null
        return WeatherNow(
            prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
            high = prefs.getInt("high", NONE).takeIf { it != NONE },
            low = prefs.getInt("low", NONE).takeIf { it != NONE },
            fetchedAt = prefs.getLong("fetched_at", 0),
        )
    }

    private fun parse(text: String): WeatherNow? = runCatching {
        val o = JSONObject(text)
        fun intOrNull(key: String) = if (o.has(key)) o.getInt(key) else null
        val days = o.optJSONArray("days")?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val d = a.optJSONArray(i) ?: return@mapNotNull null
                val kind = runCatching { WeatherKind.valueOf(d.getString(1)) }.getOrNull() ?: return@mapNotNull null
                WeatherDay(LocalDate.parse(d.getString(0)), kind, d.getInt(2), d.getInt(3))
            }
        }.orEmpty()
        WeatherNow(
            celsius = o.getInt("c"),
            kind = WeatherKind.valueOf(o.getString("k")),
            day = o.getBoolean("d"),
            high = intOrNull("h"),
            low = intOrNull("l"),
            humidity = intOrNull("rh"),
            apparent = intOrNull("at"),
            windKmh = intOrNull("ws"),
            windDir = intOrNull("wd"),
            days = days,
            aqi = intOrNull("aqi"),
            pm25 = intOrNull("pm"),
            fetchedAt = o.optLong("t", 0),
        )
    }.getOrElse {
        Log.w(TAG, "unreadable reading", it)
        null
    }

    private fun WeatherNow.toJson(): String = JSONObject().apply {
        put("c", celsius); put("k", kind.name); put("d", day); put("t", fetchedAt)
        high?.let { put("h", it) }; low?.let { put("l", it) }
        humidity?.let { put("rh", it) }; apparent?.let { put("at", it) }
        windKmh?.let { put("ws", it) }; windDir?.let { put("wd", it) }
        aqi?.let { put("aqi", it) }; pm25?.let { put("pm", it) }
        put("days", JSONArray(days.map { JSONArray().put(it.date.toString()).put(it.kind.name).put(it.high).put(it.low) }))
    }.toString()

    private var failed = false

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    /** A new reading whatever its age: the weather page's own refresh. */
    fun refreshNow() {
        if (started) refresh()
    }

    private fun refresh() {
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                prefs.edit {
                    putLong("fetched_at", reading.fetchedAt)
                    putString("reading", reading.toJson())
                    // Kept for a copy of Aerial older than the weather page.
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                }
            }
        }
    }

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            // ipinfo.io: no key, and its "loc" is "lat,lon".
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            lat = loc[0].toFloat()
            lon = loc[1].toFloat()
            // ipinfo names the city in English ("Shangqiu"); in China, say it as the TV's owner would.
            val city = ip.optString("city").ifBlank { null }
            val zh = city?.takeIf { ip.optString("country") == "CN" && Locale.getDefault().language == "zh" }
                ?.let { Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)?.name }
            prefs.edit {
                putFloat("lat", lat); putFloat("lon", lon)
                putString("place", zh ?: city)
                putLong("located_at", System.currentTimeMillis())
            }
        }
        val json = JSONObject(
            get(
                "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code,is_day," +
                    "wind_speed_10m,wind_direction_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min" +
                    "&forecast_days=$FORECAST_DAYS&timezone=auto",
            ),
        )
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        val dates = daily?.optJSONArray("time")
        val codes = daily?.optJSONArray("weather_code")
        val highs = daily?.optJSONArray("temperature_2m_max")
        val lows = daily?.optJSONArray("temperature_2m_min")
        val days = (0 until (dates?.length() ?: 0)).mapNotNull { i ->
            val date = runCatching { LocalDate.parse(dates!!.getString(i)) }.getOrNull() ?: return@mapNotNull null
            val high = highs?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt() ?: return@mapNotNull null
            val low = lows?.optDouble(i, Double.NaN)?.takeIf { !it.isNaN() }?.roundToInt() ?: return@mapNotNull null
            WeatherDay(date, kindOf(codes?.optInt(i, 0) ?: 0), high, low)
        }
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        val air = air(lat, lon)
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
            humidity = current.optDouble("relative_humidity_2m", Double.NaN).takeIf { !it.isNaN() }?.roundToInt(),
            apparent = current.optDouble("apparent_temperature", Double.NaN).takeIf { !it.isNaN() }?.roundToInt(),
            windKmh = current.optDouble("wind_speed_10m", Double.NaN).takeIf { !it.isNaN() }?.roundToInt(),
            windDir = current.optInt("wind_direction_10m", -1).takeIf { it >= 0 },
            days = days,
            aqi = air?.first,
            pm25 = air?.second,
            fetchedAt = System.currentTimeMillis(),
        )
    }

    /**
     * China's AQI (HJ 633-2012) from PM2.5 and PM10, as a Chinese weather app shows it; null when
     * the air service does not answer, which the weather itself does not depend on.
     */
    private fun air(lat: Float, lon: Float): Pair<Int, Int>? = runCatching {
        val json = JSONObject(
            get("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=$lat&longitude=$lon&current=pm2_5,pm10&timezone=auto"),
        )
        val current = json.getJSONObject("current")
        val pm25 = current.optDouble("pm2_5", Double.NaN)
        val pm10 = current.optDouble("pm10", Double.NaN)
        val aqi = listOfNotNull(iaqi(pm25, PM25_BREAKS), iaqi(pm10, PM10_BREAKS)).maxOrNull() ?: return null
        aqi to pm25.roundToInt()
    }.onFailure { Log.w(TAG, "air quality unavailable", it) }.getOrNull()

    /** One pollutant's AQI: its 24 h bands in [breaks], China's five AQI bands above them. */
    private fun iaqi(concentration: Double, breaks: List<Pair<Double, Double>>): Int? {
        if (concentration.isNaN() || concentration < 0) return null
        val i = breaks.indexOfFirst { concentration <= it.second }.let { if (it < 0) breaks.lastIndex else it }
        val (low, high) = breaks[i]
        val (aqiLow, aqiHigh) = AQI_BANDS[i]
        val share = if (high == low) 0.0 else (concentration - low) / (high - low)
        return (aqiLow + share * (aqiHigh - aqiLow)).roundToInt()
    }

    private val AQI_BANDS = listOf(0 to 50, 50 to 100, 100 to 150, 150 to 200, 200 to 300, 300 to 400, 400 to 500)
    private val PM25_BREAKS = listOf(0.0 to 35.0, 35.0 to 75.0, 75.0 to 115.0, 115.0 to 150.0, 150.0 to 250.0, 250.0 to 350.0, 350.0 to 500.0)
    private val PM10_BREAKS = listOf(0.0 to 50.0, 50.0 to 150.0, 150.0 to 250.0, 250.0 to 350.0, 350.0 to 420.0, 420.0 to 500.0, 500.0 to 600.0)

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    // Towns before villages of the same name.
                    val best = all.firstOrNull { it.optString("feature_code").startsWith("PPLA") || it.optString("feature_code") == "PPLC" } ?: all.firstOrNull()
                    best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain // drizzle, rain and showers: 51..67, 80..82
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
