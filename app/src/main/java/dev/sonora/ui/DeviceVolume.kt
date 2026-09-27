package dev.sonora.ui

import android.content.Context
import android.media.AudioManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext

/**
 * The device's media volume, as something a slider can hold.
 *
 * The system's own stream rather than a per-app one, because that is where a listener's volume
 * already is: a slider that started somewhere else would be a second volume, and the phone's own
 * buttons would move a different one from the one on this screen.
 *
 * Read once and then kept in step by the platform's broadcast, rather than re-read on every
 * recomposition — the value changes from outside the app constantly (a notification lowers it, a
 * headset unplugs and restores it), and polling for it would mean a binder round trip per frame.
 */
@Composable
internal fun rememberDeviceVolume(): Pair<Float, (Float) -> Unit> {
    val context = LocalContext.current
    val audio = remember(context) {
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    }

    val max = remember(audio) { audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1 }
    var level by remember(audio) { mutableFloatStateOf(currentVolume(audio, max)) }

    DisposableEffect(audio) {
        if (audio == null) return@DisposableEffect onDispose { }

        // A receiver, not a polled value: the broadcast already arrives on the main thread, so
        // reading straight into the state is cheaper than a coroutine hop and cannot reorder a
        // fast press and release.
        val listener = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: android.content.Intent?) {
                level = currentVolume(audio, max)
            }
        }

        val filter = android.content.IntentFilter("android.media.VOLUME_CHANGED_ACTION")
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(listener, filter, android.content.Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(listener, filter)
        }

        onDispose {
            runCatching { context.unregisterReceiver(listener) }
        }
    }

    return level to { wanted ->
        val clamped = wanted.coerceIn(0f, 1f)
        level = clamped
        audio?.setStreamVolume(
            AudioManager.STREAM_MUSIC,
            (clamped * max).toInt().coerceIn(0, max),
            0,
        )
    }
}

private fun currentVolume(audio: AudioManager?, max: Int): Float {
    val raw = audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0
    return (raw.toFloat() / max).coerceIn(0f, 1f)
}
