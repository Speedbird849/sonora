package dev.sonora.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.animateFloatAsState
import dev.sonora.lyrics.LyricsStore
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.sonora.backend.AudioQuality
import dev.sonora.backend.RepeatMode
import dev.sonora.backend.SearchQueries
import dev.sonora.backend.SonoraBackend
import dev.sonora.backend.SonoraPlayer
import dev.sonora.ui.theme.accentText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    context: android.content.Context,
    onClose: () -> Unit,
    isLiked: Boolean,
    onToggleLike: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenArtist: (String) -> Unit = {},
    onOpenAlbum: (String) -> Unit = {},
    /** Raised when something needed the peer network and there was no session to ask. */
    onNeedPeers: () -> Unit = {},
) {
    val playback by SonoraPlayer.state.collectAsState()
    val upNext by SonoraPlayer.upNext.collectAsState()
    // The queue is a panel over the player rather than a page, so it is state here and not a screen
    // the caller has to know about. A listener who opens it is still listening.
    var queueOpen by remember { mutableStateOf(false) }
    // Said rather than acted on: being moved to a sign-in form from a tap inside a player, with
    // nothing said, is indistinguishable from the app having decided to sign you out.
    var connectPrompt by remember { mutableStateOf(false) }
    // The words, over the sleeve. Held here rather than in the app so the pane and the artwork are
    // two states of one thing and cannot disagree about which is showing.
    var lyricsOpen by remember { mutableStateOf(false) }
    val lyrics by LyricsStore.current.collectAsState()
    val track = playback.track ?: return

// Asked for as the pane opens, not when the track starts: a request made for a track nobody is
    // going to read the words of is a request for nothing.
    LaunchedEffect(lyricsOpen, track.key, playback.durationMs) {
        if (lyricsOpen) {
            LyricsStore.request(track, playback.durationMs)
        }
    }

    // Non-null only while a finger is down on the bar. Held locally so the polled position cannot
    // drag the handle back out from under the drag.
    var scrubbing by remember(track.file) { mutableStateOf<Float?>(null) }
    val duration = playback.durationMs
    val played = scrubbing ?: if (duration > 0L) {
        (playback.positionMs.toFloat() / duration).coerceIn(0f, 1f)
    } else {
        0f
    }

    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val screenWidthPx = with(density) { configuration.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { configuration.screenHeightDp.dp.toPx() }
    val dismissThresholdPx = with(density) { 120.dp.toPx() }
    val switchThresholdPx = with(density) { 70.dp.toPx() }

    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val animOffsetY = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    var isDismissing by remember { mutableStateOf(false) }
    val sheetCornerShape = remember { RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp) }

    val currentOffset = if (isAnimating) animOffsetY.value else dragOffsetY

    var totalDragX by remember { mutableFloatStateOf(0f) }
    val artOffset = remember { Animatable(0f) }
    var isSwitching by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    val verticalDragState = rememberDraggableState { delta ->
        if (isDismissing) return@rememberDraggableState
        if (isAnimating) {
            dragOffsetY = animOffsetY.value
            isAnimating = false
        }
        dragOffsetY = (dragOffsetY + delta).coerceIn(0f, screenHeightPx)
    }

    val onVerticalDragStopped: suspend kotlinx.coroutines.CoroutineScope.(Float) -> Unit = { velocity ->
        if (!isDismissing) {
            val offset = dragOffsetY
        val shouldDismiss = (offset > dismissThresholdPx && velocity > -400f) || velocity > 800f
        if (shouldDismiss) {
            isDismissing = true
            isAnimating = true
            coroutineScope.launch {
                try {
                    animOffsetY.snapTo(offset)
                    animOffsetY.animateTo(
                        targetValue = screenHeightPx,
                        initialVelocity = velocity.coerceAtLeast(0f),
                        animationSpec = tween(
                            durationMillis = 200,
                            easing = FastOutLinearInEasing,
                        ),
                    )
                } catch (_: Exception) {
                } finally {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                        onClose()
                    }
                }
            }
        } else {
            isAnimating = true
            coroutineScope.launch {
                try {
                    animOffsetY.snapTo(offset)
                    animOffsetY.animateTo(
                        targetValue = 0f,
                        initialVelocity = velocity,
                        animationSpec = spring(
                            dampingRatio = Spring.DampingRatioLowBouncy,
                            stiffness = Spring.StiffnessMediumLow,
                        ),
                    )
                } finally {
                    dragOffsetY = 0f
                    isAnimating = false
                }
            }
        }
    }
    }

    fun animateNext() {
        if (isSwitching) return
        isSwitching = true
        coroutineScope.launch {
            artOffset.animateTo(
                targetValue = -screenWidthPx,
                animationSpec = tween(180, easing = FastOutLinearInEasing),
            )
            SonoraPlayer.next()
            artOffset.snapTo(screenWidthPx)
            artOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(250, easing = FastOutSlowInEasing),
            )
            totalDragX = 0f
            isSwitching = false
        }
    }

    fun animatePrevious() {
        if (isSwitching) return
        isSwitching = true
        coroutineScope.launch {
            artOffset.animateTo(
                targetValue = screenWidthPx,
                animationSpec = tween(180, easing = FastOutLinearInEasing),
            )
            SonoraPlayer.previous()
            artOffset.snapTo(-screenWidthPx)
            artOffset.animateTo(
                targetValue = 0f,
                animationSpec = tween(250, easing = FastOutSlowInEasing),
            )
            totalDragX = 0f
            isSwitching = false
        }
    }

    Surface(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = currentOffset
                if (currentOffset > 0f) {
                    clip = true
                    shape = sheetCornerShape
                }
            },
        color = MaterialTheme.colorScheme.background,
    ) {
        // The sleeve, blown up and blurred into the background.
        //
        // The one thing that makes a player look like it belongs to the music rather than to the
        // phone: the whole screen takes its colour from the record, so a warm sleeve makes the
        // controls look warm and a black one leaves them on plain black. Drawn from the same
        // bitmap as the artwork above it, so there is nothing extra to fetch and no chance of the
        // two disagreeing about what the record looks like.
        ArtworkBackdrop(track = track)

        // Asked of the player itself, so the sheet belongs to the screen that raised it.
        ConnectToPeersSheet(
            open = connectPrompt,
            onConnect = {
                connectPrompt = false
                onNeedPeers()
            },
            onDismiss = { connectPrompt = false },
        )

        if (queueOpen) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.94f)),
            ) {
                QueuePanel(
                    upNext = upNext,
                    onPlayFrom = { index -> SonoraPlayer.play(context, upNext.queue, index) },
                    onRemove = { index -> SonoraPlayer.removeFromQueue(index) },
                    onMove = { from, to -> SonoraPlayer.moveInQueue(from, to) },
                    onClose = { queueOpen = false },
                )
            }
        } else Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(start = 24.dp, top = 4.dp, end = 24.dp, bottom = 24.dp)
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = !lyricsOpen,
                    state = verticalDragState,
                    onDragStopped = onVerticalDragStopped,
                ),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    orientation = Orientation.Vertical,
                    enabled = lyricsOpen,
                    state = verticalDragState,
                    onDragStopped = onVerticalDragStopped,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = "Close player")
            }
            Text(
                text = "Now Playing",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.width(6.dp))

            LikeGlyph(liked = isLiked, onClick = onToggleLike)

            Spacer(Modifier.width(8.dp))

            CircleGlyph(
                icon = Icons.AutoMirrored.Filled.PlaylistAdd,
                contentDescription = "Add to playlist",
                onClick = onAddToPlaylist,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // The sleeve and the words, one over the other. Crossfaded rather than swapped, so
            // turning the lyrics on is a change of what the screen is *about* rather than a redraw:
            // a hard cut reads as a different page, and this is the same page about the same song.
            val artAlpha by animateFloatAsState(
                targetValue = if (lyricsOpen) 0f else 1f,
                animationSpec = tween(340),
                label = "artAlpha",
            )
            val wordsAlpha by animateFloatAsState(
                targetValue = if (lyricsOpen) 1f else 0f,
                animationSpec = tween(340),
                label = "wordsAlpha",
            )

            // The sleeve and the words share one square, crossfaded. Stacked as two squares they
            // were two full-width blocks in a column, which is twice the height the screen has: the
            // second was squeezed to nothing and drew a music note where the cover should be.
            //
            // The same square the sleeve occupied, so the words take the cover's place rather than
            // the cover's place *and* the room below it. A pane measured against the whole column
            // pushes the transport off the bottom of the screen, which is where the thing that
            // turns the lyrics off is.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f),
            ) {
                // A track that could not be fetched says so, in the place the picture would be, and
                // offers the two things a listener can actually do about it. A spinner that never stops
                // is worse than nothing: it says "working on it" for as long as it is on screen.
                val problem = playback.problem
                if (problem != null) {
                    UnplayableState(
                        track = track,
                        reason = problem,
                        onRetry = { SonoraPlayer.play(context, track) },
                        onSkip = { SonoraPlayer.next() },
                        modifier = Modifier.fillMaxSize(),
                    )
                }

                if (artAlpha > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { translationX = artOffset.value }
                            .graphicsLayer {
                                alpha = if (problem != null) 0f else artAlpha
                                // The sleeve shrinks a little as the words take over, so the two are not
                                // fighting for the same square.
                                val scale = 1f - (1f - artAlpha) * 0.08f
                                scaleX = scale
                                scaleY = scale
                            }
                            .draggable(
                                orientation = Orientation.Horizontal,
                                enabled = !isSwitching && !lyricsOpen,
                                state = rememberDraggableState { delta ->
                                    totalDragX += delta
                                    coroutineScope.launch {
                                        artOffset.snapTo(totalDragX)
                                    }
                                },
                                onDragStopped = { velocity ->
                                    if (totalDragX < -switchThresholdPx || velocity < -400f) {
                                        animateNext()
                                    } else if (totalDragX > switchThresholdPx || velocity > 400f) {
                                        animatePrevious()
                                    } else {
                                        coroutineScope.launch {
                                            artOffset.animateTo(
                                                targetValue = 0f,
                                                animationSpec = spring(
                                                    dampingRatio = Spring.DampingRatioLowBouncy,
                                                    stiffness = Spring.StiffnessMedium,
                                                ),
                                            )
                                            totalDragX = 0f
                                        }
                                    }
                                },
                            )
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Asked for at sleeve size rather than row size. The default is a thumbnail sized
                        // for a list row, and stretching one of those over an artwork that fills the screen
                        // is what makes a cover go soft — the pixels are simply not there, and no amount of
                        // decoding more carefully would have found them.
                        val artwork = rememberTrackArtwork(track, px = PLAYER_ART_PX)
                        if (artwork != null) {
                            Image(
                                bitmap = artwork,
                                contentDescription = "Album artwork",
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Filled.MusicNote,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(72.dp),
                            )
                        }
                    }
                }

                if (wordsAlpha > 0f) {
                    LyricsPane(
                        lyrics = lyrics,
                        positionMs = playback.positionMs,
                        isPlaying = playback.isPlaying,
                        onSeek = { SonoraPlayer.seekTo(it) },
                        modifier = Modifier.graphicsLayer { alpha = wordsAlpha },
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp),
            ) {
                Text(
                    text = track.title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val hasArtist = !track.artist.isNullOrBlank()
                    val hasAlbum = !track.album.isNullOrBlank()

                    if (hasArtist && hasAlbum) {
                        BlinkableText(
                            text = track.artist!!,
                            onClick = { onOpenArtist(track.artist) },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Text(
                            text = "  ·  ",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        BlinkableText(
                            text = track.album!!,
                            onClick = { onOpenAlbum(track.album) },
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else if (hasArtist) {
                        BlinkableText(
                            text = track.artist!!,
                            onClick = { onOpenArtist(track.artist) },
                        )
                    } else if (hasAlbum) {
                        BlinkableText(
                            text = track.album!!,
                            onClick = { onOpenAlbum(track.album) },
                        )
                    }
                }
                val quality = remember(track.file, duration) { AudioQuality.from(track.file, duration) }
                if (quality.isNotBlank()) {
                    Text(
                        text = quality,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
            ) {
                // A bar with no knob that thickens under the finger, rather than a Material
                // slider. The knob is a thing to aim at on a full-width control, and on a phone
                // the finger is already sitting exactly where it is; what is needed instead is a
                // line that is easy to see and hard to miss, which is what growing it while
                // dragging is for.
                PlayerScrubber(
                    positionMs = (played.toDouble() * duration).toLong(),
                    durationMs = duration,
                    // A track with no known duration cannot be seeked into.
                    onSeek = { SonoraPlayer.seekTo(it) },
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Only the three transport buttons. Shuffle and repeat are not transport — they
                // change what the playhead will meet rather than moving it — and they live in the
                // capsule below. Two controls for one state is worse than one.
                IconButton(
                    onClick = { animatePrevious() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipPrevious,
                        contentDescription = "Previous",
                        modifier = Modifier.size(32.dp),
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.togglePlayPause() },
                    modifier = Modifier
                        .size(88.dp)
                        .background(MaterialTheme.colorScheme.primary, CircleShape),
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(42.dp),
                    )
                }
                IconButton(
                    onClick = { animateNext() },
                    modifier = Modifier.size(64.dp),
                ) {
                    Icon(
                        Icons.Filled.SkipNext,
                        contentDescription = "Next",
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))

            // Volume, in the same thin shape as the scrubber and directly under it, so the two read
            // as one control rather than as a pair. Asked for above the row of actions rather than
            // tucked into one of its corners: it is the control a listener reaches for constantly
            // and without looking, which means it has to be in the same place every time.
            val (volume, onVolumeChange) = rememberDeviceVolume()
            VolumeRow(
                volume = volume,
                onVolumeChange = onVolumeChange,
                modifier = Modifier.padding(horizontal = 24.dp),
            )

            Spacer(Modifier.height(10.dp))

            // The row under the transport: a disc at each end and the playback modes between them.
            // Kept apart from the transport because they are not transport — nothing here moves the
            // playhead, they change what the playhead will meet, and putting them among the skip
            // buttons invites that confusion.
            PlayerActionRow(
                lyricsOpen = lyricsOpen,
                onToggleLyrics = {
                    // The two panes are one screen, so opening one closes the other.
                    lyricsOpen = !lyricsOpen
                    if (lyricsOpen) queueOpen = false
                },
                onFindLossless = {
                    // Peers only, never YouTube: what is being asked for is a file that can be
                    // kept, and a stream above it would be the wrong answer to the same question.
                    val asked = SonoraBackend.searchPeers(
                        context,
                        SearchQueries.forTrack(track.title, track.artist.orEmpty()),
                    )
                    if (!asked) connectPrompt = true
                },
                queueOpen = queueOpen,
                onToggleQueue = { queueOpen = !queueOpen },
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                ActionCapsule {
                    CapsuleSegment(
                        icon = Icons.Filled.Shuffle,
                        contentDescription = if (playback.isShuffled) "Turn shuffle off" else "Turn shuffle on",
                        onClick = onToggleShuffle,
                        active = playback.isShuffled,
                    )
                    CapsuleSegment(
                        icon = Icons.Filled.AutoAwesome,
                        contentDescription = if (playback.autoplay) {
                            "Turn autoplay off"
                        } else {
                            "Turn autoplay on"
                        },
                        onClick = onToggleAutoplay,
                        active = playback.autoplay,
                    )
                    CapsuleSegment(
                        icon = if (playback.repeatMode == RepeatMode.One) {
                            Icons.Filled.RepeatOne
                        } else {
                            Icons.Filled.Repeat
                        },
                        contentDescription = when (playback.repeatMode) {
                            RepeatMode.Off -> "Turn repeat on"
                            RepeatMode.All -> "Turn repeat-one on"
                            RepeatMode.One -> "Turn repeat off"
                        },
                        onClick = onCycleRepeat,
                        active = playback.repeatMode != RepeatMode.Off,
                        showDivider = false,
                    )
                }
            }
        }
    }
}
}

private fun formatMillis(value: Long): String {
    val totalSeconds = (value / 1000L).coerceAtLeast(0L)
    return "${totalSeconds / 60}:${(totalSeconds % 60).toString().padStart(2, '0')}"
}

@Composable
private fun BlinkableText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val flash = remember { Animatable(0f) }
    var isBlinking by remember { mutableStateOf(false) }
    val baseColor = MaterialTheme.colorScheme.onSurfaceVariant
    val currentColor = lerp(baseColor, MaterialTheme.colorScheme.onSurface, flash.value)

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        color = currentColor,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
        ) {
            if (isBlinking) return@clickable
            isBlinking = true
            coroutineScope.launch {
                flash.animateTo(1f, animationSpec = tween(durationMillis = 80))
                onClick()
                flash.animateTo(0f, animationSpec = tween(durationMillis = 120))
                isBlinking = false
            }
        },
    )
}

