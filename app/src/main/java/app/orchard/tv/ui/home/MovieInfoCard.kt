package app.orchard.tv.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orchard.tv.ui.components.liquidGlass
import app.orchard.tv.ui.theme.InterDisplay
import app.orchard.tv.ui.theme.InterText
import app.orchard.tv.ui.theme.Palette
import app.orchard.tv.wallpaper.PosterWall

/**
 * The card over the Movie wall (B): what film the still is from, with the details TMDB gave us.
 * It is a live Composable over the baked wallpaper, so it follows the rotation turn:
 * [PosterWall.featured] is set by the same bake that made the picture, so card and wall always
 * show the same film. It is decorative: the "预告片" line is a label, not a playable control, so it
 * adds no false affordance to Home.
 */
@Composable
fun MovieInfoCard(modifier: Modifier = Modifier) {
    val film = PosterWall.featured ?: return
    if (film.title.isBlank() && film.overview.isBlank()) return

    Column(
        modifier = modifier
            .width(470.dp)
            .liquidGlass(corner = 22.dp, bottomCorners = true)
            .padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 28.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        // Trailer label: a red badge and a play glyph, matching weatherglass's card language.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Palette.Red)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                BasicText("▶", style = TextStyle(fontSize = 14.sp, color = Color.White))
            }
            Spacer(Modifier.width(10.dp))
            BasicText(
                "预告片",
                style = TextStyle(
                    fontFamily = InterText, fontWeight = FontWeight.W600,
                    fontSize = 17.sp, color = Palette.Label,
                ),
            )
        }

        Spacer(Modifier.height(16.dp))

        BasicText(
            film.title,
            style = TextStyle(
                fontFamily = InterDisplay, fontWeight = FontWeight.W700,
                fontSize = 34.sp, color = Palette.Label, lineHeight = 40.sp,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        val meta = buildList {
            if (film.year > 0) add(film.year.toString())
            if (film.genres.isNotBlank()) add(film.genres)
            if (film.runtime > 0) add(runtimeText(film.runtime))
            if (film.rating > 0f) add("★ ${String.format(java.util.Locale.getDefault(), "%.1f", film.rating)}")
        }.joinToString("   ·   ")
        if (meta.isNotBlank()) {
            Spacer(Modifier.height(10.dp))
            BasicText(
                meta,
                style = TextStyle(
                    fontFamily = InterText, fontWeight = FontWeight.W500,
                    fontSize = 15.sp, color = Palette.SecondaryLabel,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (film.overview.isNotBlank()) {
            Spacer(Modifier.height(16.dp))
            BasicText(
                film.overview,
                style = TextStyle(
                    fontFamily = InterText, fontWeight = FontWeight.W400,
                    fontSize = 15.sp, color = Color(0xFFD6D6DE), lineHeight = 21.sp,
                ),
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Runtime in hours and minutes, e.g. 173 -> "2h 53m". */
private fun runtimeText(minutes: Int): String {
    val h = minutes / 60
    val m = minutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
