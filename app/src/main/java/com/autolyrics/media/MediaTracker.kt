package com.autolyrics.media

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.autolyrics.lyrics.LrcLibClient
import com.autolyrics.lyrics.LrcParser
import com.autolyrics.lyrics.LyricsCache
import com.autolyrics.lyrics.LyricsTranslator
import com.autolyrics.lyrics.MetadataCleaner
import com.autolyrics.lyrics.NetEaseClient
import com.autolyrics.lyrics.SyncLrcClient
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import com.autolyrics.model.TrackInfo
import com.autolyrics.util.AlbumColorExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaTracker private constructor(context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences =
        context.getSharedPreferences("auto_lyrics_prefs", Context.MODE_PRIVATE)
    private val lyricsCache = LyricsCache(context)

    private val _state = MutableStateFlow(LyricsState())
    val state: StateFlow<LyricsState> = _state.asStateFlow()

    private var activeController: MediaController? = null
    private var lastPositionMs: Long = 0
    private var lastPositionUpdateTime: Long = 0
    private var playbackSpeed: Float = 1.0f
    private var fetchJob: Job? = null
    private var prefetchJob: Job? = null
    private var artJob: Job? = null
    private var translationJob: Job? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingArt: Bitmap? = null
    private var lyricsOffsetMs: Long = 0L

    /** Per-song lyric timing, keyed by "title|artist". */
    private val songOffsets: SharedPreferences =
        context.getSharedPreferences("song_offsets", Context.MODE_PRIVATE)

    private fun songKey(track: TrackInfo?): String? {
        track ?: return null
        return "${track.title.lowercase().trim()}|${track.artist.lowercase().trim()}"
    }

    init {
        scope.launch(Dispatchers.IO) { lyricsCache.prune() }
    }

    private fun loadSongOffset(track: TrackInfo?): Long {
        val key = songKey(track) ?: return 0L
        return songOffsets.getLong(key, 0L)
    }

    /** "title|artist" key of the song playing now (null if none). */
    fun currentSongKey(): String? = songKey(_state.value.track)

    /** Change the current song's timing in memory only (used when it was already saved elsewhere). */
    fun setOffsetWithoutSaving(ms: Long) {
        lyricsOffsetMs = ms
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    /** Re-read the current song's saved timing (e.g. after restoring a backup). */
    fun reloadSongOffset() {
        lyricsOffsetMs = loadSongOffset(_state.value.track)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    private fun saveSongOffset(ms: Long) {
        val key = songKey(_state.value.track) ?: return
        if (ms == 0L) songOffsets.edit().remove(key).apply()
        else songOffsets.edit().putLong(key, ms).apply()
    }

    private val positionChecker = object : Runnable {
        override fun run() {
            updateCurrentPosition()
            if (_state.value.isPlaying) {
                handler.postDelayed(this, 150)
            }
        }
    }

    private val trackChangeRunnable = Runnable {
        val track = pendingTrack ?: return@Runnable
        val art = pendingArt
        val current = _state.value.track
        if (track.title == current?.title && track.artist == current.artist) return@Runnable

        translationJob?.cancel()
        lyricsOffsetMs = loadSongOffset(track)
        _state.value = _state.value.copy(
            track = track,
            offsetMs = lyricsOffsetMs,
            lines = emptyList(),
            currentIndex = -1,
            currentWordIndex = -1,
            status = LyricsStatus.LOADING,
            source = "",
            albumArt = art,
            albumColors = null,
            translatedLines = null,
            detectedLanguage = null
        )
        fetchLyrics(track)
        extractAlbumColors(art)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            handleMetadataChanged(metadata)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            handlePlaybackStateChanged(state)
        }

        override fun onSessionDestroyed() {
            onMediaSessionChanged(null)
        }
    }

    // Timing adjustments are remembered for the current song only.
    fun adjustOffset(deltaMs: Long) {
        lyricsOffsetMs += deltaMs
        saveSongOffset(lyricsOffsetMs)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resetOffset() {
        lyricsOffsetMs = 0L
        saveSongOffset(0L)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun resumePlayback() {
        activeController?.transportControls?.play()
    }

    fun setOffset(ms: Long) {
        lyricsOffsetMs = ms
        saveSongOffset(lyricsOffsetMs)
        _state.value = _state.value.copy(offsetMs = lyricsOffsetMs)
        updateCurrentPosition()
    }

    fun getCurrentPositionMs(): Long {
        val basePos = if (!_state.value.isPlaying) {
            lastPositionMs
        } else {
            val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime
            lastPositionMs + (elapsed * playbackSpeed).toLong()
        }
        return basePos + lyricsOffsetMs
    }

    private fun updateCurrentPosition() {
        val currentState = _state.value
        val lines = currentState.lines
        if (lines.isEmpty() || currentState.status != LyricsStatus.FOUND) return

        val posMs = getCurrentPositionMs()

        var newLineIndex = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) {
                newLineIndex = i
            } else {
                break
            }
        }

        var newWordIndex = -1
        if (newLineIndex >= 0) {
            val words = lines[newLineIndex].words
            if (words.isNotEmpty()) {
                for (i in words.indices) {
                    if (words[i].timeMs <= posMs) {
                        newWordIndex = i
                    } else {
                        break
                    }
                }
            }
        }

        if (newLineIndex != currentState.currentIndex || newWordIndex != currentState.currentWordIndex) {
            _state.value = currentState.copy(
                currentIndex = newLineIndex,
                currentWordIndex = newWordIndex
            )
        }
    }

    /** The player we are currently following (used for play/pause from the car). */
    fun activeController(): MediaController? = activeController

    /** Session token of the player we are currently following (null if none). */
    fun activeSessionToken() = activeController?.sessionToken

    fun onMediaSessionChanged(controller: MediaController?) {
        // Same player as before: just re-read its state (keeps position in sync)
        // instead of tearing down and re-registering callbacks.
        if (controller != null && activeController != null &&
            controller.sessionToken == activeController?.sessionToken
        ) {
            handleMetadataChanged(controller.metadata)
            handlePlaybackStateChanged(controller.playbackState)
            return
        }

        activeController?.unregisterCallback(mediaCallback)
        activeController = controller

        if (controller == null) {
            handler.removeCallbacks(positionChecker)
            handler.removeCallbacks(trackChangeRunnable)
            artJob?.cancel()
            _state.value = LyricsState(offsetMs = lyricsOffsetMs)
            return
        }

        controller.registerCallback(mediaCallback)
        handleMetadataChanged(controller.metadata)
        handlePlaybackStateChanged(controller.playbackState)
    }

    private fun handleMetadataChanged(metadata: MediaMetadata?) {
        if (metadata == null) return

        val rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            ?: return
        val rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: ""
        val rawAlbum = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

        val title = MetadataCleaner.cleanTitle(rawTitle)
        val artist = MetadataCleaner.cleanArtist(rawArtist)
        val album = MetadataCleaner.cleanAlbum(rawAlbum)

        val art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)

        val newTrack = TrackInfo(title, artist, album, duration)
        val current = _state.value.track

        if (current != null && newTrack.title == current.title && newTrack.artist == current.artist) {
            if (art != null && _state.value.albumArt == null) {
                _state.value = _state.value.copy(albumArt = art)
                extractAlbumColors(art)
            }
            return
        }

        pendingTrack = newTrack
        pendingArt = art
        handler.removeCallbacks(trackChangeRunnable)
        handler.postDelayed(trackChangeRunnable, 600)
    }

    private fun extractAlbumColors(bitmap: Bitmap?) {
        artJob?.cancel()
        if (bitmap == null) return
        artJob = scope.launch(Dispatchers.Default) {
            val colors = AlbumColorExtractor.extract(bitmap)
            withContext(Dispatchers.Main) {
                _state.value = _state.value.copy(albumColors = colors)
            }
        }
    }

    private fun handlePlaybackStateChanged(pbState: PlaybackState?) {
        if (pbState == null) return

        val isPlaying = pbState.state == PlaybackState.STATE_PLAYING
        lastPositionMs = pbState.position
        lastPositionUpdateTime = pbState.lastPositionUpdateTime
        if (lastPositionUpdateTime == 0L) {
            lastPositionUpdateTime = SystemClock.elapsedRealtime()
        }
        playbackSpeed = if (pbState.playbackSpeed > 0) pbState.playbackSpeed else 1.0f

        _state.value = _state.value.copy(isPlaying = isPlaying)

        handler.removeCallbacks(positionChecker)
        if (isPlaying) {
            handler.post(positionChecker)
        }
    }

    private fun fetchLyrics(track: TrackInfo) {
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            try {
                val cached = lyricsCache.get(track.title, track.artist)
                if (cached != null) {
                    val (lines, status, source) = cached
                    withContext(Dispatchers.Main) {
                        if (_state.value.track != track) return@withContext
                        _state.value = _state.value.copy(
                            lines = lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = status,
                            source = "$source (cached)"
                        )
                        if (status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(lines, track)
                    }

                    val cacheAge = lyricsCache.getAge(track.title, track.artist)
                    if (cacheAge < CACHE_REFRESH_MS || lyricsCache.isPinned(track.title, track.artist)) {
                        prefetchNextSong()
                        return@launch
                    }
                }

                val result = fetchFromAllSources(track)

                withContext(Dispatchers.Main) {
                    if (_state.value.track != track) return@withContext

                    if (result != null) {
                        lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source)
                        _state.value = _state.value.copy(
                            lines = result.lines,
                            currentIndex = -1,
                            currentWordIndex = -1,
                            status = result.status,
                            source = result.source
                        )
                        if (result.status == LyricsStatus.FOUND) {
                            updateCurrentPosition()
                        }
                        translateIfNeeded(result.lines, track)
                    } else if (cached == null) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.NOT_FOUND,
                            source = ""
                        )
                    }
                }

                prefetchNextSong()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track && _state.value.status == LyricsStatus.LOADING) {
                        _state.value = _state.value.copy(
                            status = LyricsStatus.ERROR,
                            source = ""
                        )
                    }
                }
            }
        }
    }

    private fun prefetchNextSong() {
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            try {
                val controller = activeController ?: return@launch
                val queue = controller.queue ?: return@launch
                val currentTitle = _state.value.track?.title ?: return@launch
                val currentArtist = _state.value.track?.artist ?: return@launch

                var foundCurrent = false
                for (item in queue) {
                    val desc = item.description
                    val title = MetadataCleaner.cleanTitle(desc.title?.toString() ?: continue)
                    val artist = MetadataCleaner.cleanArtist(desc.subtitle?.toString() ?: "")

                    if (!foundCurrent) {
                        if (title.equals(currentTitle, ignoreCase = true) &&
                            artist.equals(currentArtist, ignoreCase = true)
                        ) {
                            foundCurrent = true
                        }
                        continue
                    }

                    if (lyricsCache.get(title, artist) != null) return@launch

                    val nextTrack = TrackInfo(title, artist, "", 0)
                    val result = fetchFromAllSources(nextTrack)
                    if (result != null) {
                        lyricsCache.put(title, artist, result.lines, result.status, result.source)
                    }
                    return@launch
                }
            } catch (_: Exception) {
                // prefetch failures are non-fatal
            }
        }
    }

    internal data class FetchResult(
        val lines: List<LyricLine>,
        val status: LyricsStatus,
        val source: String
    )

    /**
     * Tries every source in order: SyncLRC -> LRCLIB -> NetEase.
     * Synced lyrics from any source beat plain (unsynced) lyrics; a later source
     * is only asked if the earlier ones had no synced lyrics.
     */
    private fun fetchFromAllSources(track: TrackInfo): FetchResult? {
        // Try the song as reported, then cleaner versions of the title/artist
        // (first artist only, brackets removed, "Artist - Song" split).
        var fallback: FetchResult? = null
        for ((title, artist) in MetadataCleaner.searchVariants(track.title, track.artist)) {
            val result = fetchFromSourcesOnce(track.copy(title = title, artist = artist))
            if (result?.status == LyricsStatus.FOUND) return result
            if (fallback == null) fallback = result
        }
        return fallback
    }

    private fun fetchFromSourcesOnce(track: TrackInfo): FetchResult? {
        val syncLrc = fetchFromSyncLrc(track)
        if (syncLrc?.status == LyricsStatus.FOUND) return syncLrc

        val lrcLib = fetchFromLrcLib(track)
        if (lrcLib?.status == LyricsStatus.FOUND) return lrcLib

        val netEase = fetchFromNetEase(track)
        if (netEase?.status == LyricsStatus.FOUND) return netEase

        return syncLrc ?: lrcLib ?: netEase
    }

    private fun fetchFromNetEase(track: TrackInfo): FetchResult? {
        val result = try {
            NetEaseClient.getLyrics(track.title, track.artist, track.durationMs)
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.synced) {
            val lines = LrcParser.parse(result.lyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            return if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "NetEase · Synced") else null
        }

        val lines = result.lyrics.lines()
            .filter { it.isNotBlank() }
            .map { text -> LyricLine(0L, text) }
        return if (lines.isNotEmpty()) FetchResult(lines, LyricsStatus.PLAIN_ONLY, "NetEase · Plain") else null
    }

    private fun fetchFromSyncLrc(track: TrackInfo): FetchResult? {
        val result = try {
            SyncLrcClient.getLyrics(track.title, track.artist)
        } catch (_: Exception) {
            null
        } ?: return null

        return when (result.type) {
            SyncLrcClient.LyricsType.KARAOKE -> {
                val lines = LrcParser.parseKaraoke(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Karaoke")
                else null
            }
            SyncLrcClient.LyricsType.SYNCED -> {
                val lines = LrcParser.parse(result.lyrics)
                val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
                if (hasRealText) FetchResult(lines, LyricsStatus.FOUND, "SyncLRC · Synced")
                else null
            }
            SyncLrcClient.LyricsType.PLAIN -> {
                val lines = result.lyrics.lines()
                    .filter { it.isNotBlank() }
                    .map { text -> LyricLine(0L, text) }
                if (lines.isNotEmpty()) FetchResult(lines, LyricsStatus.PLAIN_ONLY, "SyncLRC · Plain")
                else null
            }
        }
    }

    private fun fetchFromLrcLib(track: TrackInfo): FetchResult? {
        val durationSec = if (track.durationMs > 0) (track.durationMs / 1000).toInt() else 0

        val result = try {
            LrcLibClient.getLyrics(
                trackName = track.title,
                artistName = track.artist,
                albumName = track.album,
                durationSec = durationSec
            )
        } catch (_: Exception) {
            null
        } ?: return null

        if (result.syncedLyrics != null) {
            val lines = LrcParser.parse(result.syncedLyrics)
            val hasRealText = lines.any { it.text != "♪" && it.text.isNotBlank() }
            if (hasRealText) return FetchResult(lines, LyricsStatus.FOUND, "LRCLIB · Synced")
        }

        if (result.plainLyrics != null) {
            val lines = result.plainLyrics.lines()
                .filter { it.isNotBlank() }
                .map { text -> LyricLine(0L, text) }
            if (lines.isNotEmpty()) return FetchResult(lines, LyricsStatus.PLAIN_ONLY, "LRCLIB · Plain")
        }

        return null
    }

    private fun translateIfNeeded(lines: List<LyricLine>, track: TrackInfo) {
        if (!prefs.getBoolean("translation_enabled", false)) return
        translationJob?.cancel()
        translationJob = scope.launch(Dispatchers.IO) {
            try {
                val result = LyricsTranslator.translateLines(lines) ?: return@launch
                withContext(Dispatchers.Main) {
                    if (_state.value.track == track) {
                        _state.value = _state.value.copy(
                            translatedLines = result.translatedLines,
                            detectedLanguage = result.detectedLanguage
                        )
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // ---- "Wrong lyrics?" picker ----

    /** One alternative set of lyrics the user can choose from. */
    class LyricsCandidate internal constructor(
        val title: String,
        val artist: String,
        val durationSec: Int,
        /** true/false when known up front (LRCLIB); null when only known after loading (NetEase). */
        val synced: Boolean?,
        val source: String,
        internal val loader: () -> FetchResult?
    )

    /** Searches LRCLIB and NetEase more broadly than the automatic lookup and returns the options. */
    suspend fun findLyricsCandidates(): List<LyricsCandidate> = withContext(Dispatchers.IO) {
        val track = _state.value.track ?: return@withContext emptyList()
        val primary = MetadataCleaner.primaryArtist(track.artist)
        val stripped = MetadataCleaner.stripTitleExtras(track.title)

        // LRCLIB returns the lyrics with the search results
        val lrcLib = LinkedHashMap<Int, LrcLibClient.LrcLibResponse>()
        val lrcResults = try {
            LrcLibClient.search(track.title, track.artist) +
                LrcLibClient.search(stripped, primary) +
                LrcLibClient.searchQuery("$stripped $primary")
        } catch (_: Exception) {
            emptyList()
        }
        for (r in lrcResults) {
            val id = r.id ?: continue
            if (r.syncedLyrics == null && r.plainLyrics == null) continue
            lrcLib.putIfAbsent(id, r)
        }

        fun lrcCandidate(r: LrcLibClient.LrcLibResponse) = LyricsCandidate(
            title = r.trackName ?: "?",
            artist = r.artistName ?: "",
            durationSec = r.duration ?: 0,
            synced = r.syncedLyrics != null,
            source = "LRCLIB",
            loader = {
                val synced = r.syncedLyrics?.let { LrcParser.parse(it) }
                    ?.takeIf { lines -> lines.any { it.text != "♪" && it.text.isNotBlank() } }
                if (synced != null) {
                    FetchResult(synced, LyricsStatus.FOUND, "LRCLIB · Synced")
                } else {
                    r.plainLyrics?.lines()?.filter { it.isNotBlank() }?.map { LyricLine(0L, it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { FetchResult(it, LyricsStatus.PLAIN_ONLY, "LRCLIB · Plain") }
                }
            }
        )

        val netEase = try {
            NetEaseClient.searchSongs("$stripped $primary").take(8)
        } catch (_: Exception) {
            emptyList()
        }
        val netEaseCandidates = netEase.mapNotNull { song ->
            val id = song.id ?: return@mapNotNull null
            LyricsCandidate(
                title = song.name ?: "?",
                artist = song.artists.orEmpty().mapNotNull { it.name }.joinToString(", "),
                durationSec = ((song.duration ?: 0L) / 1000).toInt(),
                synced = null,
                source = "NetEase",
                loader = {
                    NetEaseClient.lyricsForSong(id)?.let { res ->
                        if (res.synced) {
                            LrcParser.parse(res.lyrics)
                                .takeIf { lines -> lines.any { it.text != "♪" && it.text.isNotBlank() } }
                                ?.let { FetchResult(it, LyricsStatus.FOUND, "NetEase · Synced") }
                        } else {
                            res.lyrics.lines().filter { it.isNotBlank() }.map { LyricLine(0L, it) }
                                .takeIf { it.isNotEmpty() }
                                ?.let { FetchResult(it, LyricsStatus.PLAIN_ONLY, "NetEase · Plain") }
                        }
                    }
                }
            )
        }

        val lrcSynced = lrcLib.values.filter { it.syncedLyrics != null }.map { lrcCandidate(it) }
        val lrcPlain = lrcLib.values.filter { it.syncedLyrics == null }.map { lrcCandidate(it) }
        (lrcSynced + netEaseCandidates + lrcPlain).take(25)
    }

    /**
     * Loads the chosen lyrics, shows them, and pins them for this song so the
     * automatic lookup never replaces them. Calls [onDone] with true on success.
     */
    fun applyLyricsCandidate(candidate: LyricsCandidate, onDone: (Boolean) -> Unit) {
        val track = _state.value.track ?: return onDone(false)
        fetchJob?.cancel()
        fetchJob = scope.launch(Dispatchers.IO) {
            val result = try { candidate.loader() } catch (_: Exception) { null }
            withContext(Dispatchers.Main) {
                if (result == null || _state.value.track != track) {
                    onDone(false)
                    return@withContext
                }
                lyricsCache.put(track.title, track.artist, result.lines, result.status, result.source, pinned = true)
                _state.value = _state.value.copy(
                    lines = result.lines,
                    currentIndex = -1,
                    currentWordIndex = -1,
                    status = result.status,
                    source = "${result.source} (your pick)",
                    translatedLines = null,
                    detectedLanguage = null
                )
                if (result.status == LyricsStatus.FOUND) updateCurrentPosition()
                translateIfNeeded(result.lines, track)
                onDone(true)
            }
        }
    }

    /** Forget the hand-picked lyrics for this song and look them up automatically again. */
    fun useAutomaticLyrics() {
        val track = _state.value.track ?: return
        lyricsCache.delete(track.title, track.artist)
        _state.value = _state.value.copy(
            lines = emptyList(), currentIndex = -1, currentWordIndex = -1,
            status = LyricsStatus.LOADING, source = "",
            translatedLines = null, detectedLanguage = null
        )
        fetchLyrics(track)
    }

    /** Delete saved lyrics (hand-picked ones are kept). Returns how many were removed. */
    suspend fun clearSavedLyrics(): Int = withContext(Dispatchers.IO) { lyricsCache.clearUnpinned() }

    companion object {
        private const val CACHE_REFRESH_MS = 7L * 24 * 60 * 60 * 1000

        @Volatile
        private var instance: MediaTracker? = null

        fun init(context: Context) {
            getInstance(context)
        }

        fun getInstance(context: Context): MediaTracker {
            return instance ?: synchronized(this) {
                instance ?: MediaTracker(context.applicationContext).also { instance = it }
            }
        }
    }
}
