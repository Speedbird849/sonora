package dev.sonora.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lyrics
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A control on the player: a glyph on a translucent disc.
 *
 * The disc is the point. A bare glyph floating over artwork has no edge of its own, so it belongs to
 * whatever happens to be behind it — legible on a dark sleeve, gone on a pale one, and it changes
 * with every track. A disc gives it a surface that does not move, and a hairline around the disc
 * gives it an edge. Twenty per cent white is enough to lift off a dark sleeve without turning into
 * a button on a light one; forty is for the state the listener has chosen.
 */
private val DISC_IDLE = 0.18f

private val DISC_ACTIVE = 0.34f

@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    glyphSize: Dp = 19.dp,
    active: Boolean = false,
    /** Overridden by [disc]; a list has no white on it to tint for. */
    tint: Color? = null,
    /**
     * Whether the control sits on a translucent disc.
     *
     * On the player, yes, because it floats over artwork and needs a surface of its own to be
     * legible against whatever the record looks like. In a list, no: the row already has a
     * background, and a disc there is a grey blob on every line that competes with the artwork it
     * sits beside. The target is 36dp either way, so a bare control is still a comfortable target.
     */
    disc: Boolean = true,
) {
    val fill by animateColorAsState(
        targetValue = when {
            !disc -> Color.Transparent
            active -> Color.White.copy(alpha = DISC_ACTIVE)
            else -> Color.White.copy(alpha = DISC_IDLE)
        },
        animationSpec = tween(180),
        label = "glyphDisc",
    )

    val ink = tint ?: if (disc) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            // No ripple: the disc already brightens, and a ripple on top of a state change is two
            // answers to one tap.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(targetState = icon, animationSpec = tween(180), label = "glyphIcon") { shown ->
            Icon(
                imageVector = shown,
                contentDescription = contentDescription,
                tint = Color.White,
                modifier = Modifier.size(glyphSize),
            )
        }
    }
}

/** The like control, which fills when the track is liked and is dimmer when it is not. */
@Composable
internal fun LikeGlyph(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    disc: Boolean = true,
    tint: Color? = null,
) {
    CircleGlyph(
        icon = if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
        contentDescription = if (liked) {
            "Remove from Liked Songs"
        } else {
            "Add to Liked Songs"
        },
        onClick = onClick,
        active = liked,
        modifier = modifier,
        disc = disc,
        tint = tint,
    )
}

/** The three-dot control that opens the track's own sheet. */
@Composable
internal fun MenuGlyph(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CircleGlyph(
        icon = Icons.Filled.MoreHoriz,
        contentDescription = "More",
        onClick = onClick,
        modifier = modifier,
    )
}

/** The height of the row of discs under the transport. */
private val ACTION_SIZE: Dp = 44.dp

/** The glyph inside one of them. */
private val ACTION_GLYPH: Dp = 26.dp

/** How far the row is inset so its outer controls sit clear of the screen's edges. */
private val ACTION_EDGE_INSET: Dp = 28.dp

/**
 * The row the player ends on: a bare disc, the capsule, a bare disc.
 *
 * The two ends are bare and the middle is a capsule, which is what makes several controls read as
 * one object rather than as a row of unrelated buttons. The row is computed for the widest state
 * the capsule can be in, so a control moving in or out of it does not shift the ends sideways — the
 * middle is allowed to change, the ends are not.
 */
@Composable
internal fun PlayerActionRow(
    lyricsOpen: Boolean,
    onToggleLyrics: () -> Unit,
    queueOpen: Boolean,
    onToggleQueue: () -> Unit,
    onFindLossless: () -> Unit,
    modifier: Modifier = Modifier,
    capsule: @Composable RowScope.() -> Unit,
) {
    BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        val rowWidth = ACTION_EDGE_INSET * 2 + ACTION_SIZE * 3 + 64.dp * 3
        val inset = ((maxWidth - rowWidth) / 2).coerceAtLeast(0.dp)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = inset),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionGlyph(
                icon = Icons.Filled.Lyrics,
                contentDescription = if (lyricsOpen) "Hide the lyrics" else "Show the lyrics",
                active = lyricsOpen,
                onClick = onToggleLyrics,
            )

            ActionCapsule { capsule() }

            ActionGlyph(
                icon = Icons.Filled.Search,
                contentDescription = "Find a lossless copy",
                active = false,
                onClick = onFindLossless,
            )

            ActionGlyph(
                icon = Icons.Filled.QueueMusic,
                contentDescription = "Up next",
                active = queueOpen,
                onClick = onToggleQueue,
            )
        }
    }
}

/** One of the bare discs at the ends of the row. */
@Composable
private fun ActionGlyph(
    icon: ImageVector,
    contentDescription: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    val disc by animateColorAsState(
        targetValue = Color.White.copy(alpha = if (active) 0.20f else 0f),
        animationSpec = tween(180),
        label = "actionDisc",
    )

    Box(
        modifier = Modifier
            .size(ACTION_SIZE)
            .clip(CircleShape)
            .background(disc)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White.copy(alpha = if (active) 1f else 0.75f),
            modifier = Modifier.size(ACTION_GLYPH),
        )
    }
}

/**
 * A capsule of two or three controls.
 *
 * Separated by a hairline rather than a gap. A gap between the segments would read as separate
 * buttons that happen to be near each other; a hairline reads as one control that is divided, which
 * is what it is — and it grows to fit rather than clipping, so adding a third control does not
 * squeeze the other two.
 */
@Composable
internal fun ActionCapsule(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .height(ACTION_SIZE)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One segment of a capsule: a square hit area in a round container, with a hairline after it. */
@Composable
internal fun CapsuleSegment(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 64.dp,
    active: Boolean = false,
    showDivider: Boolean = true,
) {
    val tint by animateColorAsState(
        targetValue = Color.White.copy(alpha = if (active) 1f else 0.75f),
        animationSpec = tween(180),
        label = "capsuleTint",
    )

    Row(
        modifier = modifier
            .height(ACTION_SIZE)
            .width(width)
            .background(if (active) Color.White.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.weight(1f),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
        }

        if (showDivider) {
            Box(
                modifier = Modifier
                    .width(1.dp)
                    .height(ACTION_SIZE)
                    .background(Color.White.copy(alpha = 0.20f)),
            )
        }
    }
}

/**
 * What to show instead of the sleeve when a track could not be fetched.
 *
 * A reason, a way to try again and a way on. All three, because the three things a listener can
 * think are "try again", "skip it" and "why", and a screen that gives only one of them answers
 * none of the others. Kept inside the sleeve's own square so nothing below it has to move.
 */
@Composable
internal fun UnplayableState(
    track: dev.sonora.backend.LibraryTrack,
    reason: String,
    onRetry: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = track.title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = reason,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                maxLines = 4,
            )

            Spacer(Modifier.height(20.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(
                    onClick = onSkip,
                    colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                        contentColor = Color.White.copy(alpha = 0.8f),
                    ),
                ) {
                    Text("Skip")
                }
                Button(
                    onClick = onRetry,
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color.Black,
                    ),
                ) {
                    Text("Try again")
                }
            }
        }
    }
}
