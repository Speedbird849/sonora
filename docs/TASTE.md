# Taste engine

An on-device listening-taste model that records what is played, learns which track follows
which, and drives an Autoplay queue refill. No server, no account, no network beyond the
existing YouTube Music client. This document is both the design and the tuning guide.

## Recon: where the pieces live

### Where the current track is known

- `backend/SonoraPlayer.kt` holds the `MediaController` to the `PlaybackService` and mirrors
  the player state into `SonoraPlayer.state` (`PlaybackState.track`). Its `Player.Listener`
  sees every `onMediaItemTransition`, whoever caused it (tap, next, shuffle, auto-advance),
  which is why it is the one place that knows everything actually listened to.
- The player itself is built in `playback/PlaybackService.kt` (`ExoPlayer`) and wrapped in a
  `MediaSession`. `SonoraService.kt` is the *P2P* foreground service and owns no player.
- `SonoraPlayer.onTrackStarted` is the existing hook the backend uses to record play history.

### Where the queue is mutated

- `SonoraPlayer` owns queue edits: `playNow`, `insertAt` (`playNext` / `addToQueue`),
  `moveInQueue`, `removeFromQueue`. All queue edits are applied to the `MediaController` on
  `Dispatchers.Main.immediate` because `MediaController` verifies its creating thread.
- Autoplay must only ever *append* — never reorder or remove what the user queued.

### How YTM and Soulseek tracks are represented

- `backend/LibraryTrack.kt` is the one type used everywhere. `file != null` means a
  downloaded file (Soulseek or device music); `remote != null` (`YtmTrack`) means a YouTube
  Music stream. `key` is the file path for a download and `ytm:<videoId>` for a stream.
- `backend/SavedTracks.kt` / `LibraryTrack.fromRemote` carry streamed tracks that were kept
  without downloading.
- Because the `key` differs by source, the taste engine keys on a *normalized*
  `artist|title` (`TrackRef.key`) so the YTM and Soulseek copies of one song are the same
  track.

### YTM radio

- `ytm/YtmSearch.kt` and `ytm/YtmBrowse.kt` are plain InnerTube calls (`WEB_REMIX`).
- There was no "next / radio" wrapper; `ytm/YtmRadio.kt` adds one (`videoId -> related
  tracks`) and is the engine's cold-start and exploration source.

## Algorithm

### Identity

`TrackRef.key` is the normalized `artist|title`. Normalization lowercases, strips
`(...)`/`[...]`, strips punctuation, drops remaster/live/feat. decorations, folds unicode
(accents) and collapses whitespace. So `Song (Remastered 2011)` by `Björk` and `Song` by
`Bjork` collapse to one key.

### Decay

A counter is stored as `Decayed(value, at)`. Reads apply
`value * 0.5^((now - at) / halfLife)`; writes decay first and then add the new signal,
stamping `at = now`. Half-lives used by the engine:

| Signal          | Half-life |
| --------------- | --------- |
| track weight    | 30 d      |
| artist affinity  | 30 d      |
| tag affinity     | 45 d      |
| A -> B edge      | 60 d      |

### Signal per play

Computed from the played ratio (real played milliseconds over duration) and the absolute
time listened:

| Condition                          | Signal |
| ---------------------------------- | ------ |
| ratio >= 0.8 (completed)           | +1.0   |
| ratio >= 0.5                       | +0.4   |
| ratio < 0.25 and < 30 s listened   | -0.8   |
| ratio < 0.25 otherwise             | -0.5   |
| in between (0.25 .. < 0.5)         |  0.0   |
| liked                              | max(base, 1.0) + 0.5 |

Only real played time is banked: the tracker accumulates while `isPlaying` and banks it on
pause / transition / stop. Wall-clock time with the app paused counts for nothing.

### Transitions

An edge `prev -> cur` is written only when **all** hold: `prev != cur`, `prev` had a
positive signal, `cur` has a non-zero signal, and the gap is under 30 minutes (a session
boundary — a track resumed the next morning is not a transition). A skip records a negative
edge so a bad pick demotes itself.

### Origin

A play is tagged `USER` or `AUTOPLAY`. Autoplay-originated positives count at `0.5` weight
so the engine cannot reward itself, which is what would otherwise collapse the queue onto a
handful of picks.

### Scoring and selection

`TasteEngine.next(cur, count, external)`:

- Candidates: the top 15 out-edges of `cur`, completed tracks of the top 5 artists (top 25),
  plus `external(cur)` = YTM radio.
- Excluded: the last 40 played keys and `cur` itself.
- Score =
  `edge1 + 0.4*edge2 + 0.6*tanh(artist/2) + 0.5*tanh(meanTag/2) + 0.2*ln(1+completes)`
  `+ 0.3*(liked?1:0) + 0.15*(never played) - 0.8*skipRate(plays>=2)`
  `- 0.5*(times artist appears in the last 3)`.
- Selection is softmax over the top 8 with temperature `0.35`, then the context rolls
  forward (the picked track becomes the next `cur`) and the process repeats. Never
  deterministic, never repeats inside a batch.

Cold start (empty model) returns the external list unchanged, so a fresh install still plays.

### Pruning

Before every save, each node keeps its top 30 edges by absolute decayed weight above
`0.05`; the track map is capped at 20,000 entries, dropping the least-recently played
low-signal tracks first.

### Persistence

`TasteStore` writes `taste.json` in `filesDir`, debounced by 3 s, atomically (temp file +
rename). The previous good document is kept as `taste.json.bak`. A corrupt main file falls
back to the backup, then to an empty model. Every mutation is serialized through one
`Mutex`. All of this is off the main thread.

## Tuning

Every constant is in `TasteEngine.kt` (`HALF_LIFE_*`, signal thresholds, score weights,
`SOFTMAX_TEMPERATURE`, `RECENT_WINDOW`, `MAX_EDGES_PER_NODE`, `MAX_TRACKS`). Change one,
run `./gradlew :app:testDebugUnitTest`, and watch the simulation test in
`TasteEngineSimulationTest.kt` for the in-cluster / exploration balance.
