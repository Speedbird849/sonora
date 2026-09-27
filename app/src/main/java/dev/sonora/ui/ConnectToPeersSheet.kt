package dev.sonora.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * What to say when something needs the peer network and there isn't one.
 *
 * A sheet rather than a message, because the only useful thing to do about it is connect, and a
 * message leaves the listener to find the tab themselves. Named after the thing it is about rather
 * than the action that was refused, so the same sheet serves the download button, a row's menu and
 * anything else that needs files.
 */
@Composable
internal fun ConnectToPeersSheet(
    /** Whether to show it at all. A sheet is a window, not a card, so it must be asked for. */
    open: Boolean,
    onConnect: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!open) return

    SonoraSheet(onDismiss = onDismiss, modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 22.dp)
                .padding(bottom = 20.dp),
        ) {
            SheetHeading("Connect to the network")

            Text(
                text = "Lossless files are on other people's computers, not on YouTube Music. " +
                    "Signing in is what lets the app ask for them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Button(
                onClick = onConnect,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                shape = RoundedCornerShape(13.dp),
            ) {
                Text("Connect", style = MaterialTheme.typography.titleMedium)
            }

            TextButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Not now",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
