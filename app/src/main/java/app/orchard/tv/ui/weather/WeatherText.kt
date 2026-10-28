package app.orchard.tv.ui.weather

import java.time.LocalDate
import java.util.Locale

/**
 * The words the weather is said in, in the owner's language. Aerial's box is English, but its owner
 * here reads Chinese and the phone weather apps he knows say it this way: one greeting over the
 * clock, "明天/后天/周六" over the days ahead, "西北风 3 级" for the wind, "良" for the air.
 * English stays plain, so nothing has to be translated back.
 */
private val zh: Boolean get() = Locale.getDefault().language == "zh"

/** Over the date, as a phone's lock screen greets its owner; null in English, which has no such line. */
fun greetingOf(hour: Int): String? {
    if (!zh) return null
    return when (hour) {
        in 5..8 -> "早上好"
        in 9..10 -> "上午好"
        in 11..12 -> "中午好"
        in 13..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
}

/** "明天", "后天", then the weekday: 周六. */
fun dayLabel(index: Int, date: LocalDate): String = when {
    index == 0 -> if (zh) "明天" else "Tomorrow"
    index == 1 -> if (zh) "后天" else "In 2 days"
    else -> weekdayShort(date)
}

fun weekdayShort(date: LocalDate): String =
    if (zh) "周" + "一二三四五六日"[date.dayOfWeek.value - 1]
    else date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

/** "明天 阴 11-19°", the form the clock's quiet line and the strip under it both use. */
fun dayLine(index: Int, date: LocalDate, condition: String, high: Int, low: Int): String =
    "${dayLabel(index, date)} $condition $high-$low°"

/** Open-Meteo reports the wind as the degrees it comes from; 8 points is all a TV needs. */
fun windDirection(degrees: Int): String {
    val points = if (zh) listOf("北风", "东北风", "东风", "东南风", "南风", "西南风", "西风", "西北风")
    else listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    val i = (((degrees % 360) + 360) % 360 + 22) / 45 % 8
    return points[i]
}

/** Beaufort, from km/h: what "3 级" means on a Chinese forecast. */
fun beaufort(kmh: Int): Int = when {
    kmh < 1 -> 0
    kmh <= 5 -> 1
    kmh <= 11 -> 2
    kmh <= 19 -> 3
    kmh <= 28 -> 4
    kmh <= 38 -> 5
    kmh <= 49 -> 6
    kmh <= 61 -> 7
    kmh <= 74 -> 8
    kmh <= 88 -> 9
    kmh <= 102 -> 10
    kmh <= 117 -> 11
    else -> 12
}

/** China's six bands: 优 良 轻度污染 中度污染 重度污染 严重污染. */
fun aqiLevel(aqi: Int): String = when {
    aqi <= 50 -> if (zh) "优" else "Good"
    aqi <= 100 -> if (zh) "良" else "Moderate"
    aqi <= 150 -> if (zh) "轻度污染" else "Unhealthy for sensitive"
    aqi <= 200 -> if (zh) "中度污染" else "Unhealthy"
    aqi <= 300 -> if (zh) "重度污染" else "Very unhealthy"
    else -> if (zh) "严重污染" else "Hazardous"
}

/** The condition in the owner's words: 晴, 多云, 阴, 雾, 雨, 雪, 雷雨. */
fun conditionText(kind: app.orchard.tv.weather.WeatherKind): String {
    if (zh) return when (kind) {
        app.orchard.tv.weather.WeatherKind.Clear -> "晴"
        app.orchard.tv.weather.WeatherKind.PartlyCloudy -> "多云"
        app.orchard.tv.weather.WeatherKind.Cloudy -> "阴"
        app.orchard.tv.weather.WeatherKind.Fog -> "雾"
        app.orchard.tv.weather.WeatherKind.Rain -> "雨"
        app.orchard.tv.weather.WeatherKind.Snow -> "雪"
        app.orchard.tv.weather.WeatherKind.Thunder -> "雷雨"
    }
    return when (kind) {
        app.orchard.tv.weather.WeatherKind.Clear -> "Clear"
        app.orchard.tv.weather.WeatherKind.PartlyCloudy -> "Partly cloudy"
        app.orchard.tv.weather.WeatherKind.Cloudy -> "Cloudy"
        app.orchard.tv.weather.WeatherKind.Fog -> "Fog"
        app.orchard.tv.weather.WeatherKind.Rain -> "Rain"
        app.orchard.tv.weather.WeatherKind.Snow -> "Snow"
        app.orchard.tv.weather.WeatherKind.Thunder -> "Thunderstorms"
    }
}

/** "10月28日 周三", the weather page's own date line. */
fun dateLine(date: LocalDate): String =
    if (zh) "${date.monthValue}月${date.dayOfMonth}日 ${weekdayShort(date)}"
    else "${date.monthValue}/${date.dayOfMonth} ${weekdayShort(date)}"

/** "22:35 更新", under the date. */
fun updatedLine(at: Long): String? {
    if (at <= 0L) return null
    val time = java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(at))
    return if (zh) "$time 更新" else "Updated $time"
}
