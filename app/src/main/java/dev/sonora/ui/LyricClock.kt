package dev.sonora.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.abs

/**
 * Reconciles the frame-driven lyric clock with the player's slower position reports.
 *
 * The player is sampled periodically (~500ms), while lyrics advance once per frame. Comparing a
 * fresh report directly with the already-advanced display clock mistakes delivery delay for a seek
 * and rewinds the words. Instead, compare it with where the previous report should have reached in
 * the elapsed wall-clock time. Ordinary polling and main-thread delays stay monotonic; an actual
 * seek is a large discontinuity and resets at once.
 */
internal class LyricClockReconciler(
    initialReportedMs: Long,
    initialObservedAtMs: Long,
    initialPlaying: Boolean,
) {
    private var lastReportedMs = initialReportedMs
    private var lastObservedAtMs = initialObservedAtMs
    private var wasPlaying = initialPlaying

    fun reconcile(
        displayedMs: Long,
        reportedMs: Long,
        observedAtMs: Long,
        isPlaying: Boolean,
    ): Long {
        val elapsedMs = (observedAtMs - lastObservedAtMs).coerceAtLeast(0L)
        val expectedMs = lastReportedMs + if (wasPlaying) elapsedMs else 0L
        val discontinuity = !isPlaying || abs(reportedMs - expectedMs) > SEEK_DISCONTINUITY_MS

        lastReportedMs = reportedMs
        lastObservedAtMs = observedAtMs
        wasPlaying = isPlaying

        return if (discontinuity) reportedMs else maxOf(displayedMs, reportedMs)
    }
}

private const val SEEK_DISCONTINUITY_MS = 1_250L

/**
 * Whether the app is currently in the foreground and visible to the user.
 *
 * Gating the frame clock on this prevents unnecessary render loops when the screen is off or
 * the app is backgrounded.
 */
@Composable
fun rememberIsForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {
        mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
    }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { owner, _ ->
            foreground = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return foreground
}

/**
 * A nanosecond frame-accurate lyric clock interpolating between player position updates.
 *
 * Returned as [MutableLongState] so reading it inside draw lambdas / graphicsLayer runs only
 * the draw phase each frame without triggering full recompositions.
 */
@Composable
internal fun rememberLyricClock(
    trackKey: Any,
    positionMs: Long,
    isPlaying: Boolean,
): MutableLongState {
    val startedAtMs = remember(trackKey) { SystemClock.elapsedRealtime() }
    val clock = remember(trackKey) { mutableLongStateOf(positionMs) }
    val reconciler = remember(trackKey) {
        LyricClockReconciler(positionMs, startedAtMs, isPlaying)
    }

    val foreground = rememberIsForeground()
    LaunchedEffect(positionMs, isPlaying, foreground) {
        clock.longValue = reconciler.reconcile(
            displayedMs = clock.longValue,
            reportedMs = positionMs,
            observedAtMs = SystemClock.elapsedRealtime(),
            isPlaying = isPlaying,
        )
        if (!isPlaying || !foreground) return@LaunchedEffect
        val firstFrame = withFrameMillis { it }
        while (true) {
            withFrameMillis { frame ->
                clock.longValue = maxOf(clock.longValue, positionMs + frame - firstFrame)
            }
        }
    }
    return clock
}
