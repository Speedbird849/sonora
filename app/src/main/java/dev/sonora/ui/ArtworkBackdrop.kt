package dev.sonora.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.sonora.backend.LibraryTrack

/**
 * How far the sleeve's blur reaches, and how hard the scrim over it is.
 *
 * Both are on the edge of being too much. A blur that is too small leaves a recognisable copy of
 * the artwork behind the controls, which is a picture of a record rather than the mood of one; a
 * scrim that is too heavy turns the whole thing black and throws away the colour it was for. So the
 * blur is wide and the scrim is graded rather than flat — light at the top where the sleeve is
 * bright, heavy at the bottom where the controls have to stay readable.
 */
private val BLUR: Dp = 96.dp

private const val TOP_ALPHA = 0.58f

private const val MID_ALPHA = 0.80f

private const val BOTTOM_ALPHA = 0.95f

/**
 * The sleeve, scaled past its own edges and blurred, behind everything on the player.
 *
 * The scrim is heavy, and that is the whole trick. A sleeve is whatever colour the artist chose,
 * and plenty of them are pale enough that white text on them is unreadable — so what is left after
 * blurring is graded down until the controls always win. What survives is the hue, which is the part
 * that says which record this is; the brightness, which is the part that would break legibility, is
 * taken away.
 *
 * Overscaled rather than merely blurred because a blur samples past the edge of what it is given:
 * a bitmap blurred at the size of the screen has a soft band along the edges where there is nothing
 * to sample and the background shows through. Growing it first means every pixel in view has
 * something behind it.
 *
 * Falls back to a flat scrim on a device that cannot blur — a real `RenderEffect` needs API 31, and
 * below that a blur silently draws nothing, which would leave the screen black and look like a
 * rendering fault rather than like a missing effect.
 */
@Composable
internal fun ArtworkBackdrop(
    artwork: androidx.compose.ui.graphics.ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (artwork != null) 1f else 0f,
        animationSpec = tween(360),
        label = "backdropAlpha",
    )

    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    Box(modifier = modifier.fillMaxSize()) {
        if (artwork != null && alpha > 0f) {
            Image(
                bitmap = artwork,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = 1.6f
                        scaleY = 1.6f
                        this.alpha = alpha
                    }
                    .then(if (canBlur) Modifier.blur(BLUR) else Modifier),
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = TOP_ALPHA),
                        0.55f to Color.Black.copy(alpha = MID_ALPHA),
                        1f to Color.Black.copy(alpha = BOTTOM_ALPHA),
                    ),
                ),
        )
    }
}

@Composable
internal fun ArtworkBackdrop(
    track: LibraryTrack,
    modifier: Modifier = Modifier,
) {
    val artwork = rememberTrackArtwork(track, px = PLAYER_ART_PX)
    ArtworkBackdrop(artwork = artwork, modifier = modifier)
}

@Composable
internal fun ArtworkBackdrop(
    artworkUrl: String?,
    fallbackTrack: LibraryTrack? = null,
    modifier: Modifier = Modifier,
) {
    val artwork = rememberArtworkAt(artworkUrl, px = PLAYER_ART_PX)
        ?: fallbackTrack?.let { rememberTrackArtwork(it, px = PLAYER_ART_PX) }
    ArtworkBackdrop(artwork = artwork, modifier = modifier)
}
