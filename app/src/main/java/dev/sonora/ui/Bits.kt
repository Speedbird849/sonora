package dev.sonora.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import dev.sonora.ui.theme.accentText

/**
 * The small pieces every screen reaches for.
 *
 * They live together because they are the vocabulary the screens are written in: a row is a tile
 * plus two text styles plus a gap, and defining any of that twice is how two lists that are supposed
 * to be the same list end up not being.
 */

/**
 * A hairline ring around a piece of artwork.
 *
 * Applied after a clip, not instead of one, so it traces the corner it is given. A cover on a black
 * background with no edge is a hole in the page rather than a picture of something, and the ring is
 * what tells the eye where the picture stops.
 */
@Composable
fun Modifier.thumbnailBorder(shape: Shape): Modifier = border(
    width = 1.dp,
    color = if (isSystemInDarkTheme()) {
        Color.White.copy(alpha = 0.15f)
    } else {
        Color.Black.copy(alpha = 0.15f)
    },
    shape = shape,
)

/**
 * The outlined "E" that marks audio the catalogue flags as explicit.
 *
 * Drawn as a box around a single letter rather than a filled badge, so it sits beside a title
 * without competing with it — the title is what the listener is reading, and a solid block in front
 * of it is the louder of the two.
 */
@Composable
internal fun ExplicitBadge(color: Color, modifier: Modifier = Modifier) {
    Text(
        text = "E",
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = modifier
            .border(1.dp, color.copy(alpha = 0.72f), RoundedCornerShape(2.dp))
            .padding(horizontal = 3.dp),
    )
}

/**
 * A track's title, preceded by the explicit badge when it has one.
 *
 * The badge is a leading item rather than part of the string because it is not part of the name: it
 * has to be searchable, sortable and comparable as plain text everywhere else, and baking it into the
 * title would break all three.
 */
@Composable
internal fun ExplicitSongTitle(
    title: String,
    isExplicit: Boolean,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (isExplicit) {
            ExplicitBadge(color = color)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = title,
            style = style,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * The mark that says a track is already on the device.
 *
 * Smaller than the more button beside it, and deliberately not interactive, because it is
 * information rather than a control. At the same size as its neighbour it invites a tap that does
 * nothing, which reads as a broken button rather than as a label.
 */
@Composable
internal fun DownloadedBadge(tint: Color, modifier: Modifier = Modifier) {
    Icon(
        imageVector = Icons.Filled.DownloadDone,
        contentDescription = "Downloaded",
        tint = tint,
        modifier = modifier.size(16.dp),
    )
}

/**
 * A centred message, with an optional action.
 *
 * No icon and no illustration: this is the state a screen reaches when a request failed or there is
 * nothing to show, and a picture there competes with the one sentence that explains why. The action
 * only appears when there is genuinely something to do about it.
 */
@Composable
internal fun MessageState(
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = PAGE_GUTTER + 12.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) { Text(actionLabel) }
        }
    }
}

/**
 * The app's own text field: a flat rounded box, no outline and no underline.
 *
 * Material's outlined field draws a border and a floating label, which is two pieces of chrome
 * around a box that is already a distinct colour from the page. At the size a settings row wants,
 * that reads as a form embedded in a list rather than as one row.
 *
 * The height is fixed rather than intrinsic, so entering text does not change the row's height and
 * push everything below it down by a line.
 */
@Composable
internal fun PillTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    isPassword: Boolean = false,
    container: Color = MaterialTheme.colorScheme.surfaceVariant,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: @Composable (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(container, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            enabled = enabled,
            textStyle = MaterialTheme.typography.bodyMedium.copy(
                color = MaterialTheme.colorScheme.onBackground,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.accentText),
            visualTransformation =
            if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            modifier = Modifier.fillMaxWidth(),
        )

        if (trailing != null) {
            Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
        }
    }
}

/**
 * The search field: the same box as [PillTextField], with a magnifier, a clear button, and a fixed
 * height.
 *
 * The magnifier is both the leading icon and the submit button, which is why it is a circle with its
 * own touch target rather than a glyph inside the field's padding. Pressing it runs the search and
 * drops the keyboard, because a search that leaves the keyboard up hides the results it just
 * produced.
 */
@Composable
internal fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Songs, albums, artists",
) {
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    fun submit() {
        onSubmit()
        focusManager.clearFocus()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            // Deliberately asymmetric: the magnifier's circular target needs room on its left that
            // square corners would not.
            .padding(start = PAGE_GUTTER, end = PAGE_GUTTER, bottom = 4.dp)
            .height(46.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
            .padding(start = 8.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(CircleShape)
                .background(
                    if (query.isNotBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.12f)
                    } else {
                        Color.Transparent
                    },
                    CircleShape,
                )
                .then(
                    if (query.isNotBlank()) {
                        Modifier.clickable { submit() }
                    } else {
                        Modifier
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        Spacer(Modifier.width(4.dp))

        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(
                    color = MaterialTheme.colorScheme.onBackground,
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.accentText),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submit() }),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (query.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickable {
                        // Clearing is an edit rather than a dismissal: the field keeps focus and the
                        // keyboard stays up, because the next thing typed is another search.
                        onQueryChange("")
                        focusRequester.requestFocus()
                        keyboard?.show()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * A row that answers a long press as well as a tap.
 *
 * Long press is the only gesture with room for a per-item menu on a phone, and every list of tracks
 * in the app needs one.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun Modifier.rowClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
): Modifier = this.then(
    if (onLongClick != null) {
        Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
    } else {
        Modifier.clickable(onClick = onClick)
    },
)

/** A circle that carries a glyph, sized so the touch target is not the glyph's own bounds. */
@Composable
internal fun CircleGlyph(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    glyphSize: Dp = 20.dp,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    enabled: Boolean = true,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides tint) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(glyphSize),
            )
        }
    }
}

/**
 * How much room the floating bars take at the foot of a page.
 *
 * Measured rather than guessed: the bar is its own height plus the system navigation inset, and the
 * mini player adds its own above that. A list that does not reserve this much has its last rows
 * sitting under the glass, where they are both unreachable and still visible — the worst of both.
 */
private val BARS_HEIGHT: Dp = 78.dp

private val MINI_PLAYER_HEIGHT: Dp = 64.dp

/** The gap between the mini player and the tab bar, plus a little for the fade above them. */
private val BARS_CLEARANCE: Dp = 28.dp

/**
 * The bottom padding a scrolling list needs to keep its last row clear of the floating bars.
 *
 * [withMiniPlayer] because the bar is only sometimes there, and a list that reserved for it
 * permanently would leave a dead band at the foot of every page with nothing playing.
 */
@Composable
internal fun listBottomPadding(withMiniPlayer: Boolean): Dp =
    BARS_HEIGHT + BARS_CLEARANCE + if (withMiniPlayer) MINI_PLAYER_HEIGHT else 0.dp

/** The same, as content padding, for a list that wants a little more of its own below that. */
@Composable
internal fun listContentPadding(extra: Dp = 16.dp, withMiniPlayer: Boolean = false): PaddingValues =
    PaddingValues(bottom = listBottomPadding(withMiniPlayer) + extra)
