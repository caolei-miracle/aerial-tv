package app.orchard.tv.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * show the same film. It is decorative and small, so it leaves the still to carry the screen.
 */
@Composable
fun MovieInfoCard(modifier: Modifier = Modifier) {
    val film = PosterWall.featured ?: return
    if (film.title.isBlank() && film.overview.isBlank()) return

    Column(
        modifier = modifier
            .width(400.dp)
            .liquidGlass(corner = 20.dp, bottomCorners = true)
            .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        BasicText(
            film.title,
            style = TextStyle(
                fontFamily = InterDisplay, fontWeight = FontWeight.W700,
                fontSize = 28.sp, color = Palette.Label, lineHeight = 33.sp,
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
            Spacer(Modifier.height(7.dp))
            BasicText(
                meta,
                style = TextStyle(
                    fontFamily = InterText, fontWeight = FontWeight.W500,
                    fontSize = 14.sp, color = Palette.SecondaryLabel,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }

        if (film.overview.isNotBlank()) {
            Spacer(Modifier.height(9.dp))
            BasicText(
                film.overview,
                style = TextStyle(
                    fontFamily = InterText, fontWeight = FontWeight.W400,
                    fontSize = 14.sp, color = Color(0xFFD6D6DE), lineHeight = 19.sp,
                ),
                maxLines = 3,
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
