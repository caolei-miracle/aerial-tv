package app.orchard.tv.ui.weather

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.R
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.home.glyphFor
import app.orchard.tv.ui.theme.GlyphIcon
import app.orchard.tv.ui.theme.Motion
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.ui.theme.Type
import app.orchard.tv.weather.Weather
import app.orchard.tv.weather.WeatherNow

/**
 * The weather page: what the clock's four quiet lines cannot say, in one place. Reached from
 * Control Center, closed with Back. Every number comes from the reading Home already has, so the
 * page opens at once and only asks for a new one when the old one is stale.
 */
@Composable
fun WeatherPage(visible: Boolean, onDismiss: () -> Unit) {
    BackHandler(enabled = visible, onBack = onDismiss)
    LaunchedEffect(visible) {
        if (visible) Weather.refreshIfStale(networkCameUp = true)
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(160, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        // Home's own content is gone while this page is open (HomeScreen), so the wallpaper can stay
        // here: this much black over it keeps the page's type plain to read.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)))
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(260, easing = Motion.EaseOut)),
        exit = fadeOut(tween(140, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val now = Weather.now
        Column(Modifier.fillMaxSize().padding(horizontal = 88.dp, vertical = 56.dp)) {
            if (now == null) {
                BasicText(Weather.place ?: "", style = Type.LargeTitle)
                Spacer(Modifier.height(16.dp))
                BasicText(
                    stringResource(R.string.wp_weather_none),
                    style = Type.Body.copy(color = Palette.SecondaryLabel),
                )
                return@Column
            }
            Head(now)
            Current(now)
            Metrics(now)
            Spacer(Modifier.weight(1f))
            Forecast(now)
            Spacer(Modifier.height(18.dp))
            BasicText(
                if (Weather.chosen) stringResource(R.string.weather_page_source) else stringResource(R.string.weather_page_source_guessed),
                style = Type.Caption.copy(color = Palette.TertiaryLabel),
            )
        }
    }
}

@Composable
private fun Head(now: WeatherNow) {
    val place = Weather.place?.takeIf { it.isNotBlank() }
    val under = listOfNotNull(
        dateLine(java.time.LocalDate.now()),
        updatedLine(now.fetchedAt),
    ).joinToString("  ·  ")
    Row(verticalAlignment = Alignment.Bottom) {
        BasicText(place ?: stringResource(R.string.wp_weather), style = Type.LargeTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(18.dp))
        BasicText(under, style = Type.Callout, modifier = Modifier.padding(bottom = 3.dp))
    }
}

@Composable
private fun Current(now: WeatherNow) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 22.dp)) {
        GlyphIcon(glyphFor(now), Color.White, 76.dp)
        Spacer(Modifier.width(22.dp))
        BasicText("${now.celsius}°", style = Type.Clock.copy(fontSize = 96.sp))
        Spacer(Modifier.width(26.dp))
        Column {
            BasicText(conditionText(now.kind), style = Type.Title)
            if (now.high != null && now.low != null) {
                BasicText(
                    stringResource(R.string.weather_high_low, now.high, now.low),
                    style = Type.Callout,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun Metrics(now: WeatherNow) {
    val wind = when {
        now.windKmh == null -> null
        now.windDir == null -> stringResource(R.string.weather_beaufort, beaufort(now.windKmh))
        else -> "${windDirection(now.windDir)} ${stringResource(R.string.weather_beaufort, beaufort(now.windKmh))}"
    }
    Row(Modifier.padding(top = 26.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Metric(stringResource(R.string.weather_humidity), now.humidity?.let { "$it%" } ?: "—", Modifier.weight(1f))
        Metric(stringResource(R.string.weather_wind), wind ?: "—", Modifier.weight(1f))
        Metric(stringResource(R.string.weather_apparent), now.apparent?.let { "$it°" } ?: "—", Modifier.weight(1f))
        Metric(
            stringResource(R.string.weather_air),
            now.aqi?.let { "$it ${aqiLevel(it)}" } ?: "—",
            Modifier.weight(1f),
            dot = now.aqi?.let { aqiColor(it) },
        )
    }
}

@Composable
private fun Metric(label: String, value: String, modifier: Modifier = Modifier, dot: Color? = null) {
    Column(modifier.liquidGlass(18.dp).padding(horizontal = 20.dp, vertical = 16.dp)) {
        BasicText(label, style = Type.Caption)
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
            if (dot != null) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
                Spacer(Modifier.width(8.dp))
            }
            BasicText(value, style = Type.Title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Forecast(now: WeatherNow) {
    val days = now.days.take(7)
    if (days.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        days.forEachIndexed { i, day ->
            Column(
                Modifier
                    .weight(1f)
                    .liquidGlass(18.dp)
                    .padding(vertical = 14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                BasicText(
                    if (i == 0) stringResource(R.string.weather_today) else dayLabel(i - 1, day.date),
                    style = Type.Caption.copy(color = if (i == 0) Palette.Label else Palette.SecondaryLabel),
                )
                Spacer(Modifier.height(8.dp))
                GlyphIcon(glyphFor(now.copy(kind = day.kind, day = true)), Color.White, 26.dp)
                Spacer(Modifier.height(8.dp))
                BasicText("${day.high}° / ${day.low}°", style = Type.Caption.copy(color = Palette.Label))
                BasicText(conditionText(day.kind), style = Type.Caption.copy(fontSize = 10.sp), modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** China's bands: 优 green, 良 yellow, then orange, red and purple, as the phone apps colour them. */
private fun aqiColor(aqi: Int): Color = when {
    aqi <= 50 -> Palette.Green
    aqi <= 100 -> Color(0xFFFFD60A)
    aqi <= 150 -> Palette.Orange
    aqi <= 200 -> Palette.Red
    else -> Color(0xFFAF52DE)
}
