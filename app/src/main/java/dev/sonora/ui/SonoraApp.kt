package dev.sonora.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sonora.ui.theme.accentText
import dev.sonora.backend.BackendState
import dev.sonora.backend.LibraryTrack
import dev.sonora.backend.Playlists
import dev.sonora.backend.SonoraBackend
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.HazeState
import androidx.compose.foundation.layout.widthIn
import dev.sonora.spotify.SpotifyImportRunner
import dev.sonora.backend.SonoraPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * App root. Navigation and the feature graph hang off here.
 */
@Composable
fun SonoraApp() {
    val context = LocalContext.current
    val state by SonoraBackend.state.collectAsState()


    // One launcher for all of them: Android shows these one dialog at a time, so firing separate
    // requests in the same frame would silently drop all but the first.
    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }

    LaunchedEffect(Unit) {
        val wanted = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
                add(Manifest.permission.READ_MEDIA_AUDIO)
            } else {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)

                // Below API 30 this is also what lets downloads land in the shared Music folder.
                // Refused, downloads fall back to app-private storage rather than failing.
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }
        }

        permissions.launch(wanted.toTypedArray())
    }

    // A remembered login connects without being asked — that is the whole point of remembering it.
    // The screen below is the fallback for when it does not work.
    LaunchedEffect(Unit) {
        if (state !is BackendState.Idle) return@LaunchedEffect

        val saved = withContext(Dispatchers.IO) { SonoraBackend.savedLogin(context) }
        saved?.let { SonoraBackend.connect(context, it.username, it.password, remember = true) }
    }

    // The Soulseek session is not the app's front door.
    //
    // YouTube Music search, streaming and the Spotify import all work without a Soulseek account —
    // they are plain HTTPS to a public catalogue. Gating the whole app on a login to a file-sharing
    // network would mean a listener who only wants to search and listen to YouTube could not open
    // the app at all, and the network is where downloads come from rather than where music is found.
    //
    // So the tabs are drawn in every state, and connecting becomes one of them: what the network is
    // for, offered rather than required. The session's own state still decides what the Network tab
    // shows, so a failed or dropped connection is visible rather than swallowed.
    MainTabs(state = state)
}

private enum class MainTab(val label: String) {
    Home("Home"),
    Search("Search"),
    Library("Library"),
    Settings("Settings"),
    /**
     * The Soulseek network, and where a listener connects to it.
     *
     * A tab rather than a front door: the catalogue does not need an account, so the network is
     * where downloads come from rather than a condition for opening the app.
     */
    Network("Network"),
}

/** The bar's tabs, in the order they are drawn. Built once because the list never changes. */
private val tabs: List<BottomTab> = MainTab.entries.map { BottomTab(it.label, it.icon()) }

private fun MainTab.icon(): ImageVector = when (this) {
    MainTab.Home -> Icons.Filled.Home
    MainTab.Search -> Icons.Filled.Search
    MainTab.Library -> Icons.AutoMirrored.Filled.List
    MainTab.Settings -> Icons.Filled.Settings
    MainTab.Network -> Icons.Filled.CloudDownload
}

/** Shown above the tabs whenever something is loaded, on either screen. */
@Composable
private fun NowPlayingBar(onOpen: () -> Unit) {
    val playback by SonoraPlayer.state.collectAsState()
    val track = playback.track ?: return
    val density = LocalDensity.current
    val skipThresholdPx = with(density) { 50.dp.toPx() }
    val maxDragOffsetPx = with(density) { 12.dp.toPx() }
    var totalDrag by remember { mutableFloatStateOf(0f) }
    val dragOffset = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer { translationX = dragOffset.value }
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            totalDrag += delta
                            val target = (totalDrag * 0.12f).coerceIn(-maxDragOffsetPx, maxDragOffsetPx)
                            coroutineScope.launch {
                                dragOffset.snapTo(target)
                            }
                        },
                        onDragStopped = { velocity ->
                            if (totalDrag < -skipThresholdPx || velocity < -500f) {
                                SonoraPlayer.next()
                            } else if (totalDrag > skipThresholdPx || velocity > 500f) {
                                SonoraPlayer.previous()
                            }
                            totalDrag = 0f
                            coroutineScope.launch {
                                dragOffset.animateTo(
                                    targetValue = 0f,
                                    animationSpec = spring(
                                        dampingRatio = Spring.DampingRatioLowBouncy,
                                        stiffness = Spring.StiffnessMedium,
                                    ),
                                )
                            }
                        },
                    )
                    .clickable(onClick = onOpen)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    val artwork = rememberTrackArtwork(track)
                    if (artwork != null) {
                        Image(
                            bitmap = artwork,
                            contentDescription = "Album artwork",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp),
                ) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    listOfNotNull(track.artist, track.album).joinToString(" \u00b7 ").let {
                        if (it.isNotEmpty()) {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                IconButton(
                    onClick = { SonoraPlayer.previous() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                IconButton(
                    onClick = { SonoraPlayer.togglePlayPause() },
                    modifier = Modifier.size(40.dp),
                ) {
                    Icon(
                        imageVector = if (playback.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playback.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.accentText,
                    )
                }
                IconButton(
                    onClick = { SonoraPlayer.next() },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
            }

            val progress = if (playback.durationMs > 0L) {
                (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
            } else {
                0f
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = MaterialTheme.colorScheme.accentText,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
            }
    }
}

@Composable
private fun ConnectScreen(state: BackendState) {
    val context = LocalContext.current
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var remember by remember { mutableStateOf(false) }

    // Filled in before the fields are read, so a login that is remembered does not have to be typed
    // again after a failure — which is the case this screen is actually reached in.
    LaunchedEffect(Unit) {
        val saved = withContext(Dispatchers.IO) { SonoraBackend.savedLogin(context) }
            ?: return@LaunchedEffect

        username = saved.username
        password = saved.password
        remember = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "Sonora", style = MaterialTheme.typography.headlineMedium)

        when (state) {
            BackendState.Idle -> {
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Soulseek username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = remember, onCheckedChange = { remember = it })

                    Column {
                        Text(text = "Save login", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "Encrypted on this device",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = {
                        SonoraBackend.connect(context, username.trim(), password, remember)
                    },
                    enabled = username.isNotBlank() && password.isNotBlank(),
                ) {
                    Text("Connect")
                }

                Text(
                    text = "An unknown username is registered on first sign-in.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            BackendState.Connecting -> {
                Text("Connecting\u2026", style = MaterialTheme.typography.bodyMedium)
                CircularProgressIndicator()
            }

            is BackendState.Failed -> {
                Text(
                    text = "Could not connect",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(state.reason, style = MaterialTheme.typography.bodyMedium)
                Button(onClick = { SonoraBackend.disconnect(context) }) {
                    Text("Back")
                }
            }

            // Connected: the network is up, so this tab is a status page rather than a form. It is
            // deliberately not blank — a tab that goes empty the moment it stops being useful is
            // indistinguishable from a broken one.
            is BackendState.Connected -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Connected", style = MaterialTheme.typography.headlineSmall)
                Text(
                    text = state.greeting.ifBlank { "Signed in to Soulseek" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    // Downloads come from here. Search and playback do not, which is why this is
                    // a tab rather than the way in.
                    text = "Searching and streaming work without this. " +
                        "Connecting is for finding lossless files on the network.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { SonoraBackend.disconnect(context) }) { Text("Disconnect") }
            }
        }
    }
}


/**
 * The app's tabs, whether or not the network is connected.
 *
 * The same drawing serves both cases: [state] says what the network session is doing, and the
 * Network tab shows it. What the network is for is one tab, not a gate in front of the app.
 */
@Composable
private fun MainTabs(state: BackendState) {
        // Held here rather than at the root: the tabs only need it once they are being drawn, and
        // the branch that decides whether to draw them has no Context to hand.
        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        // The import's own state, remembered here so a run survives a tab switch and a reconnect.
        // The write half is handed in rather than reached for, because creating a playlist needs a
        // Context this object only has once the tabs are being drawn.
        val importRunner = remember {
            SpotifyImportRunner { draft ->
                SonoraBackend.importSpotifyPlaylist(context, draft.title, draft.matched)
                    ?: error("the import produced no playlist")
            }
        }

        // Home first: it is where resuming and finding new music both start.
        var tab by remember { mutableStateOf(MainTab.Home) }
        var playerOpen by remember { mutableStateOf(false) }
        var addTarget by remember { mutableStateOf<LibraryTrack?>(null) }
        // The Spotify import is a sequence with its own state, so it outlives the screen that
        // opened it — a listener who switches tabs mid-import should not lose their progress.
        var spotifyImport by remember { mutableStateOf(false) }

        // Held here rather than inside the Library so a playlist card on Home can open it.
        var openPlaylistId by remember { mutableStateOf<String?>(null) }
        var openArtistName by remember { mutableStateOf<String?>(null) }
        var openAlbumName by remember { mutableStateOf<String?>(null) }
        val playback by SonoraPlayer.state.collectAsState()
        val playlists by SonoraBackend.playlists.collectAsState()
        val likedKeys = remember(playlists) { Playlists.likedKeys(playlists) }

        // Binds to the playback service once the app is in use, so the first tap on a track
        // is not waiting on a connection.
        LaunchedEffect(Unit) { SonoraPlayer.connect(context) }

        // Playlists outlive the session, so they are read once when the app is usable rather
        // than on every visit to the Library.
        LaunchedEffect(Unit) { SonoraBackend.refreshPlaylists(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSettings(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSearchHistory(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshPlayHistory(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshSaved(context) }
        LaunchedEffect(Unit) { SonoraBackend.refreshRecentTracks(context) }

        LaunchedEffect(playback.track, playback.isPlaying) {
            while (playback.track != null) {
                SonoraPlayer.syncPosition()
                delay(500)
            }
        }

    // What the bar says and whether it offers a way back. Both come from what is open rather than
    // from the tab: a playlist opened from Home is not a tab, and its own name is the only title
    // that means anything while it is being read.
    val chromeBack: (() -> Unit)? = when {
        openPlaylistId != null -> {
            { openPlaylistId = null }
        }

        openArtistName != null -> {
            { openArtistName = null }
        }

        openAlbumName != null -> {
            { openAlbumName = null }
        }

        else -> null
    }

    // One Haze state for the whole page. The bars and the top bar all sample the same source, which
    // is what lets a cover scrolling past show through the mini player and the top bar in the same
    // frame — two states would sample two different snapshots and the page would tear between them.
    val hazeState = remember { HazeState() }

    Box(modifier = Modifier.fillMaxSize()) {
        // The page is the blur source. Every frosted surface in the app samples this subtree, so
        // anything drawn outside it is invisible to the glass.
        Box(modifier = Modifier.fillMaxSize().hazeSource(hazeState)) {
            // Inset by the status bar only, so a page's first row starts clear of the clock and
            // then scrolls *under* the frosted bar. Padding for the bar's full height instead would
            // leave an empty strip above every page — the bar carries nothing, so that strip is
            // dead space, and the glass only reads as glass once something is behind it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            ) {
            when (tab) {
                MainTab.Home -> HomeScreen(
                        onImportSpotify = { spotifyImport = true },
                        onRunSearch = { term ->
                            // A suggestion on Home is really a pre-filled search, so this is
                            // the whole action: go to Search and run it.
                            tab = MainTab.Search
                            SonoraBackend.search(context, term)
                        },
                        onOpenPlaylist = { id ->
                            // The same handoff in the other direction: the playlist is shown by
                            // the Library, so go there and ask it for that one.
                            openPlaylistId = id
                            tab = MainTab.Library
                        },
                    )
                MainTab.Search -> SearchScreen()

                MainTab.Library -> LibraryScreen(
                        onRunSearch = { term ->
                            tab = MainTab.Search
                            SonoraBackend.search(context, term)
                        },
                        openPlaylistId = openPlaylistId,
                        onOpenPlaylist = { openPlaylistId = it },
                        onClosePlaylist = { openPlaylistId = null },
                        openArtistName = openArtistName,
                        onCloseArtist = { openArtistName = null },
                        openAlbumName = openAlbumName,
                        onCloseAlbum = { openAlbumName = null },
                    )
                MainTab.Settings -> SettingsScreen()

                // The connect screen, reachable at any time, and showing the session once
                // there is one.
                MainTab.Network -> ConnectScreen(state = state)
            }
            }
        }

        // The bar and the pane it is drawn over. Underneath the page rather than inside it, so the
        // blur has something to sample.
        TopBarBlur(hazeState = hazeState, modifier = Modifier.align(Alignment.TopCenter))
        FrostedTopBar(
            hazeState = hazeState,
            modifier = Modifier.align(Alignment.TopCenter),
            // A mark, not a title. Every page below has a large heading that says what it is and
            // scrolls away with the page; a second name up here would be a smaller copy of it that
            // does not move, so the two disagree the moment the heading leaves.
            leading = { SonoraWordmark() },
            onBack = chromeBack,
        )

        // The floor the bars stand on, drawn over the page and under everything else. Without it a
        // track row scrolling past the tab bar is still fully opaque right up to the pill's edge,
        // which is what makes a floating bar look pasted on rather than sitting in the page.
        BottomFadeScrim(
            modifier = Modifier.align(Alignment.BottomCenter),
            withMiniPlayer = playback.track != null,
        )

        // The bars, bottom-anchored in one column so they share an edge and a width cap. Two
        // independently-sized bars would not line up on a wide screen.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .widthIn(max = FLOATING_BAR_MAX_WIDTH)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val track = playback.track
            if (track != null) {
                MiniPlayer(
                    track = track,
                    isPlaying = playback.isPlaying,
                    isResolving = playback.isResolving,
                    onPlayPause = { SonoraPlayer.togglePlayPause() },
                    onNext = { SonoraPlayer.next() },
                    onPrevious = { SonoraPlayer.previous() },
                    onExpand = { playerOpen = true },
                    hazeState = hazeState,
                )
                Spacer(Modifier.height(8.dp))
            }

            FloatingTabBar(
                tabs = remember { tabs },
                selectedIndex = MainTab.entries.indexOf(tab),
                onTabSelected = { index ->
                    val entry = MainTab.entries[index]
                    // Tapping Search while already on it clears the search, which is also what
                    // brings the recent queries back into view.
                    if (tab == entry && entry == MainTab.Search) {
                        SonoraBackend.clearSearch()
                    } else {
                        // Leaving the Library closes whatever it had open. That state used to live
                        // inside it and reset this way, and holding it up here should not change
                        // what the user sees.
                        if (entry != MainTab.Library) {
                            openPlaylistId = null
                            openArtistName = null
                            openAlbumName = null
                        }
                        tab = entry
                    }
                },
                hazeState = hazeState,
            )
        }

            AnimatedVisibility(
                visible = playerOpen,
                enter = slideInVertically(
                    initialOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(
                        durationMillis = 240,
                        easing = CubicBezierEasing(0.1f, 1f, 0.1f, 1f),
                    ),
                ) + fadeIn(
                    animationSpec = tween(durationMillis = 180),
                ),
                exit = slideOutVertically(
                    targetOffsetY = { fullHeight -> fullHeight },
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
                    ),
                ) + fadeOut(
                    animationSpec = tween(durationMillis = 160),
                ),
            ) {
                BackHandler { playerOpen = false }
                NowPlayingScreen(
                    onClose = { playerOpen = false },
                    isLiked = playback.track?.let { it.key in likedKeys } == true,
                    onToggleLike = {
                        playback.track?.let { SonoraBackend.toggleLiked(context, it) }
                    },
                    onToggleShuffle = { SonoraPlayer.toggleShuffle() },
                    onCycleRepeat = { SonoraPlayer.cycleRepeat() },
                    onAddToPlaylist = { addTarget = playback.track },
                    onOpenArtist = { artistName ->
                        openArtistName = artistName
                        openAlbumName = null
                        openPlaylistId = null
                        tab = MainTab.Library
                        playerOpen = false
                    },
                    onOpenAlbum = { albumName ->
                        openAlbumName = albumName
                        openArtistName = null
                        openPlaylistId = null
                        tab = MainTab.Library
                        playerOpen = false
                    },
                )
            }
        }

        if (spotifyImport) {
            SpotifyImportDialog(
                state = importRunner.state.collectAsState().value,
                onStart = { link -> importRunner.start(scope, link) },
                onConfirm = { draft ->
                    scope.launch { importRunner.confirm(draft) }
                },
                onDismiss = {
                    spotifyImport = false
                    importRunner.dismiss()
                },
            )
        }

        AddToPlaylistFlow(
            track = addTarget,
            playlists = playlists,
            onDismiss = { addTarget = null },
            onAdd = { playlist, track ->
                SonoraBackend.addToPlaylist(context, playlist.id, track)
                addTarget = null
            },
            onCreateWithTrack = { name, track ->
                SonoraBackend.createPlaylist(context, name, track)
                addTarget = null
            },
        )
    }
