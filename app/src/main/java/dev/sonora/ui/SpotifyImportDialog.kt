package dev.sonora.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.spotify.SpotifyDraft
import dev.sonora.spotify.SpotifyImportState

/**
 * Brings a Spotify playlist in as a playlist here.
 *
 * A dialog rather than a screen because it is three steps with nothing to navigate between: paste a
 * link, watch it work, confirm. The middle step is a real wait — a hundred tracks is a hundred
 * searches — so it gets a determinate bar and a count, and the confirm step shows what was found
 * *and* what was not.
 *
 * The shortfall is the point of the preview. Spotify and YouTube do not hold the same catalogue, so
 * some tracks will find nothing, and quietly dropping them would report an import that is not what
 * was asked for. They are listed, struck through, so the number is visible before the playlist is
 * written rather than after.
 */
@Composable
internal fun SpotifyImportDialog(
    state: SpotifyImportState,
    onStart: (String) -> Unit,
    onConfirm: (SpotifyDraft) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        title = { Text("Import from Spotify") },
        text = {
            when (state) {
                is SpotifyImportState.Idle -> ImportForm(onStart, null)

                is SpotifyImportState.Failed -> ImportForm(onStart, state.reason)

                SpotifyImportState.Reading -> Working("Reading the playlist…", null)

                is SpotifyImportState.Matching -> Working(
                    "Looking for each track… ${state.done} of ${state.total}",
                    fraction(state.done, state.total),
                )

                is SpotifyImportState.Writing -> Working(
                    "Building the playlist… ${state.done} of ${state.total}",
                    fraction(state.done, state.total),
                )

                is SpotifyImportState.Ready -> ImportPreview(state.draft)

                is SpotifyImportState.Done -> Column(
                    Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        // A single-track playlist is a real thing to import, and "Added 1 tracks"
                        // is the kind of thing a listener reads as a bug in the count.
                        text = when {
                            state.added == state.requested && state.added == 1 -> "Added 1 track."
                            state.added == state.requested -> "Added ${state.added} tracks."
                            else -> "Added ${state.added} of ${state.requested}."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onDismiss) { Text("Close") }
                }
            }
        },
        confirmButton = {
            if (state is SpotifyImportState.Ready) {
                TextButton(onClick = { onConfirm(state.draft) }) { Text("Add ${state.draft.matched.size}") }
            }
        },
    )
}

private fun fraction(done: Int, total: Int) =
    if (total > 0) done.toFloat() / total else 0f

@Composable
private fun ImportForm(onStart: (String) -> Unit, failure: SpotifyImportState.Reason?) {
    var link by remember { mutableStateOf("") }
    val submit = { if (link.isNotBlank()) onStart(link.trim()) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            text = "Paste a Spotify playlist or album link. It is read from Spotify's own public " +
                "page — no account, and nothing is downloaded unless you ask.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = link,
            onValueChange = { link = it },
            label = { Text("Playlist or album link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { submit() }),
        )
        if (failure != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = failure.message(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(14.dp))
        Button(
            onClick = submit,
            enabled = link.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Import")
        }
    }
}

private fun SpotifyImportState.Reason.message(): String = when (this) {
    SpotifyImportState.Reason.BadLink -> "That isn't a Spotify playlist or album link."
    SpotifyImportState.Reason.Unreadable -> "Couldn't read that playlist — it may be private."
    SpotifyImportState.Reason.Unreachable -> "Couldn't reach Spotify. Try again."
    SpotifyImportState.Reason.NothingMatched -> "None of those tracks could be found."
}

@Composable
private fun Working(message: String, fraction: Float?) {
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (fraction == null) {
            CircularProgressIndicator(modifier = Modifier.size(26.dp), strokeWidth = 2.5.dp)
        } else {
            // Determinate, because this is a hundred searches and an indeterminate spinner for
            // fifteen seconds reads as a hang rather than as work.
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(18.dp))
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ImportPreview(draft: SpotifyDraft) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = draft.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (draft.owner != null) {
            Text(
                text = draft.owner,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "${draft.matched.size} of ${draft.matches.size} tracks will be added",
            style = MaterialTheme.typography.bodyMedium,
        )
        if (draft.missed.isNotEmpty()) {
            Text(
                text = "${draft.missed.size} could not be found on YouTube Music",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (draft.atTrackLimit) {
            // Said here rather than left to be found later: there is no way to ask Spotify for the
            // rest, so the only place this can be honest is the screen where the number is acted on.
            Text(
                text = "Spotify returns at most 100 tracks from one link",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // Bounded rather than unbounded: a LazyColumn in a dialog is measured with no height limit
        // and throws, and a hundred rows would run off the screen.
        LazyColumn(Modifier.heightIn(max = 220.dp)) {
            itemsIndexed(draft.matches) { _, match ->
                val missed = match.track == null
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(36.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surface),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = match.spotify.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (missed) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onBackground
                            },
                            textDecoration = if (missed) TextDecoration.LineThrough else null,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = match.spotify.artist,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
