package com.autolyrics.auto

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import com.autolyrics.R
import com.autolyrics.media.CarDelayLearner
import com.autolyrics.media.MediaTracker
import com.autolyrics.model.LyricLine
import com.autolyrics.model.LyricsState
import com.autolyrics.model.LyricsStatus
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

class LyricsBrowserService : MediaBrowserServiceCompat() {

    private data class SyncStep(val label: String, val deltaMs: Long, val icon: Int)

    private lateinit var mediaSession: MediaSessionCompat
    private lateinit var mediaTracker: MediaTracker
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private var lastNotifyTime = 0L
    private var pendingNotify = false
    private var displayedWindowStart = -1
    private var displayedWindowEnd = -1
    private var displayedCurrentIdx = -1
    private var displayedStatus: LyricsStatus? = null
    private var displayedTrackTitle: String? = null
    private var lastSubtitleText: String? = null
    private var lastAlbumArt: Bitmap? = null

    private var aaKaraokeEnabled = true
    private var aaBigLyrics = true
    private var aaOffsetMs = 0L

    private var lastKaraokeLineIdx = -1
    private var lastKaraokeWordIdx = -1
    private var lastKaraokeText: String? = null

    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { sp, key ->
            when (key) {
                "aa_karaoke_enabled" -> {
                    aaKaraokeEnabled = sp.getBoolean(key, true)
                    forceRefresh()
                }
                "aa_offset_ms" -> {
                    aaOffsetMs = sp.getLong(key, 0L)
                    forceRefresh()
                }
                "aa_big_lyrics" -> {
                    aaBigLyrics = sp.getBoolean(key, true)
                    lastSubtitleText = null
                    updateMediaSession(mediaTracker.state.value)
                }
            }
        }

    companion object {
        private const val ROOT_ID = "root"
        private const val LYRICS_ID = "lyrics_tab"
        private const val SYNC_MENU_ID = "sync_menu"
        private const val SYNC_RESET_ID = "sync_reset"

        /** Sync tab buttons: media id -> step (label, change in ms, white icon). */
        private val SYNC_STEPS = linkedMapOf(
            "sync_minus_500" to SyncStep("− 0.5s", -500L, R.drawable.ic_aa_back_big),
            "sync_minus_100" to SyncStep("− 0.1s", -100L, R.drawable.ic_aa_back_small),
            "sync_plus_100" to SyncStep("+ 0.1s", 100L, R.drawable.ic_aa_fwd_small),
            "sync_plus_500" to SyncStep("+ 0.5s", 500L, R.drawable.ic_aa_fwd_big)
        )
        private const val ICON_PX = 96

        /** Lyrics tab: the current line plus the next 3. */
        private const val WINDOW_SIZE = 4
        private const val PLAIN_WINDOW_SIZE = 4

        // Android Auto content-style hints: show everything as plain lists
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val PAD_WIDTH = 60
        private const val NOTIFY_THROTTLE_MS = 500L
        private const val BROWSE_KARAOKE_WINDOW_MS = 600L
        private const val SUBTITLE_KARAOKE_WINDOW_MS = 300L
        private const val SESSION_REFRESH_MS = 1500L
        private const val PLAIN_LOOP_DELAY_MS = 2000L
    }

    override fun onCreate() {
        super.onCreate()
        mediaTracker = MediaTracker.getInstance(this)

        val prefs = getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
        aaKaraokeEnabled = prefs.getBoolean("aa_karaoke_enabled", true)
        aaOffsetMs = prefs.getLong("aa_offset_ms", 0L)
        aaBigLyrics = prefs.getBoolean("aa_big_lyrics", true)
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        mediaSession = MediaSessionCompat(this, "AutoLyrics").apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = mediaSession.sessionToken

        scope.launch {
            mediaTracker.state.collectLatest { state ->
                updateMediaSession(state)
                throttledNotifyChildren(state)
            }
        }

        scope.launch {
            while (isActive) {
                delay(200)
                updateSubtitleKaraoke()
            }
        }

        scope.launch {
            while (isActive) {
                delay(PLAIN_LOOP_DELAY_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
                    throttledNotifyChildren(state)
                }
            }
        }

        scope.launch {
            while (isActive) {
                delay(SESSION_REFRESH_MS)
                val state = mediaTracker.state.value
                if (state.isPlaying && state.track != null) {
                    mediaSession.isActive = true
                    mediaSession.setPlaybackState(buildPlaybackState(state))
                }
            }
        }
    }

    override fun onDestroy() {
        getSharedPreferences("auto_lyrics_prefs", MODE_PRIVATE)
            .unregisterOnSharedPreferenceChangeListener(prefsListener)
        scope.cancel()
        mediaSession.isActive = false
        mediaSession.release()
        super.onDestroy()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_LIST)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        return BrowserRoot(ROOT_ID, extras)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<MutableList<MediaBrowserCompat.MediaItem>>
    ) {
        val state = mediaTracker.state.value
        val items = mutableListOf<MediaBrowserCompat.MediaItem>()

        // Root: two tabs, "Lyrics" then "Sync". (All root items must be browsable,
        // otherwise Android Auto lumps playable items into an extra "More" tab.)
        if (parentId == ROOT_ID) {
            items.add(buildBrowsableItem(LYRICS_ID, "Lyrics", null))
            items.add(buildBrowsableItem(SYNC_MENU_ID, "Sync", null))
            result.sendResult(items)
            return
        }

        if (parentId == SYNC_MENU_ID) {
            buildSyncMenu(state, items)
            result.sendResult(items)
            return
        }

        if (parentId != LYRICS_ID) {
            result.sendResult(items)
            return
        }

        when (state.status) {
            LyricsStatus.NO_MEDIA -> {
                items.add(buildTextItem("no_media", "Play a song to see lyrics"))
            }
            LyricsStatus.LOADING -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("loading", "Loading lyrics…"))
            }
            LyricsStatus.NOT_FOUND -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("not_found", "No lyrics found for this track"))
            }
            LyricsStatus.ERROR -> {
                addTrackHeader(state, items)
                items.add(buildTextItem("error", "Error loading lyrics"))
            }
            LyricsStatus.FOUND -> {
                addTrackHeader(state, items)
                buildWindowedLyrics(state, items)
            }
            LyricsStatus.PLAIN_ONLY -> {
                addTrackHeader(state, items)
                if (state.lines.isEmpty()) {
                    items.add(buildTextItem("empty", "♪"))
                } else {
                    val durationMs = state.track?.durationMs ?: 0
                    val posMs = try {
                        mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
                    } catch (_: Exception) { 0L }

                    val estimatedIdx = if (durationMs > 0) {
                        ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                            .coerceIn(0, state.lines.size - 1)
                    } else { 0 }

                    val winEnd = minOf(state.lines.size, estimatedIdx + PLAIN_WINDOW_SIZE)
                    for (i in estimatedIdx until winEnd) {
                        val text = state.lines[i].text.ifBlank { "♪" }
                        val prefix = if (i == estimatedIdx) "▶  " else "    "
                        val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
                        items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
                    }
                }
            }
        }

        result.sendResult(items)
    }

    private fun buildSyncMenu(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        items.add(
            buildTextItem(
                "sync_offset",
                "This song: ${formatOffset(state.offsetMs)}",
                subtitle = "Car delay (all songs): ${formatOffset(aaOffsetMs)}" +
                    if (CarDelayLearner.isEnabled(this) && CarDelayLearner.lastLearnedShift(this) != 0L) " · learned" else ""
            )
        )

        if (state.lines.isNotEmpty()) {
            val posMs = getAaPositionMs()
            val idx = if (state.status == LyricsStatus.FOUND) {
                findLineIndex(state.lines, posMs).coerceAtLeast(0)
            } else {
                val durationMs = state.track?.durationMs ?: 0
                if (durationMs > 0) {
                    ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                        .coerceIn(0, state.lines.size - 1)
                } else 0
            }

            val curLine = state.lines.getOrNull(idx)
            val curText = if (curLine != null && aaKaraokeEnabled && curLine.words.isNotEmpty()) {
                buildKaraokeText(curLine, idx, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                curLine?.text ?: "♪"
            }
            val curTrans = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("sync_cur", "▶  $curText", pad = true, subtitle = curTrans))
            val nextTrans = state.translatedLines?.getOrNull(idx + 1)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("sync_next", "    ${state.lines.getOrNull(idx + 1)?.text ?: ""}", pad = true, subtitle = nextTrans))
        }

        for ((id, step) in SYNC_STEPS) {
            items.add(buildTextItem(id, step.label, icon = iconBitmap(step.icon)))
        }
        items.add(buildTextItem(SYNC_RESET_ID, "Reset this song", icon = iconBitmap(R.drawable.ic_aa_reset)))
    }

    private val iconCache = mutableMapOf<Int, Bitmap?>()

    /** White vector icon rendered to a small bitmap (Android Auto list rows take bitmaps). */
    private fun iconBitmap(resId: Int): Bitmap? = iconCache.getOrPut(resId) {
        try {
            ContextCompat.getDrawable(this, resId)?.toBitmap(ICON_PX, ICON_PX)
        } catch (_: Exception) {
            null
        }
    }

    private fun formatOffset(ms: Long): String {
        val sign = if (ms > 0) "+" else if (ms < 0) "−" else ""
        return "$sign%.2fs".format(kotlin.math.abs(ms) / 1000.0)
    }

    private fun addTrackHeader(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val track = state.track ?: return

        val descBuilder = MediaDescriptionCompat.Builder()
            .setMediaId("header")
            .setTitle(track.title)
            .setSubtitle(track.artist)

        val headerArt = state.albumArt ?: lastAlbumArt
        headerArt?.let { art ->
            descBuilder.setIconBitmap(art)
        }

        items.add(
            MediaBrowserCompat.MediaItem(
                descBuilder.build(),
                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
            )
        )
    }

    private fun buildWindowedLyrics(
        state: LyricsState,
        items: MutableList<MediaBrowserCompat.MediaItem>
    ) {
        val lines = state.lines
        if (lines.isEmpty()) {
            items.add(buildTextItem("empty", "♪"))
            return
        }

        val posMs = getAaPositionMs()
        val aaCurrentIdx = findLineIndex(lines, posMs).coerceAtLeast(0)

        // Current line at the top, then the lines coming up next
        val windowEnd = minOf(lines.size, aaCurrentIdx + WINDOW_SIZE)

        for (i in aaCurrentIdx until windowEnd) {
            val line = lines[i]
            val isCurrent = i == aaCurrentIdx
            val prefix = if (isCurrent) "▶  " else "    "

            val text = if (isCurrent && aaKaraokeEnabled && line.words.isNotEmpty()) {
                buildKaraokeText(line, i, posMs, BROWSE_KARAOKE_WINDOW_MS)
            } else {
                line.text.ifBlank { "♪" }
            }

            val trans = state.translatedLines?.getOrNull(i)?.takeIf { it.isNotBlank() }
            items.add(buildTextItem("line_$i", "$prefix$text", pad = true, subtitle = trans))
        }
    }

    private fun buildTextItem(
        id: String,
        text: String,
        pad: Boolean = false,
        subtitle: String? = null,
        icon: Bitmap? = null
    ): MediaBrowserCompat.MediaItem {
        val title = if (pad) text.padEnd(PAD_WIDTH) else text
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        if (icon != null) builder.setIconBitmap(icon)
        return MediaBrowserCompat.MediaItem(
            builder.build(),
            MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
        )
    }

    private fun buildBrowsableItem(id: String, title: String, subtitle: String?): MediaBrowserCompat.MediaItem {
        val builder = MediaDescriptionCompat.Builder()
            .setMediaId(id)
            .setTitle(title)
        if (!subtitle.isNullOrBlank()) builder.setSubtitle(subtitle)
        return MediaBrowserCompat.MediaItem(builder.build(), MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    // --- Karaoke helpers ---

    private fun getAaPositionMs(): Long {
        return try {
            mediaTracker.getCurrentPositionMs() + aaOffsetMs
        } catch (_: Exception) {
            0L
        }
    }

    private fun findLineIndex(lines: List<LyricLine>, posMs: Long): Int {
        var idx = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= posMs) idx = i
            else break
        }
        return idx
    }

    private fun buildKaraokeText(line: LyricLine, lineIdx: Int, posMs: Long, windowMs: Long): String {
        val words = line.words
        if (words.isEmpty()) return line.text

        var currentIdx = -1
        for (i in words.indices) {
            if (words[i].timeMs <= posMs) currentIdx = i
            else break
        }
        if (currentIdx < 0) return line.text

        val sameLine = lineIdx == lastKaraokeLineIdx
        if (sameLine && currentIdx <= lastKaraokeWordIdx && lastKaraokeText != null) {
            return lastKaraokeText!!
        }

        var endIdx = currentIdx
        for (i in (currentIdx + 1) until words.size) {
            if (words[i].timeMs <= posMs + windowMs) endIdx = i
            else break
        }

        if (sameLine) {
            endIdx = maxOf(endIdx, lastKaraokeWordIdx)
        }

        lastKaraokeLineIdx = lineIdx
        lastKaraokeWordIdx = currentIdx
        val sb = StringBuilder()
        for (i in words.indices) {
            if (i == currentIdx) sb.append("【")
            sb.append(words[i].text)
            if (i == endIdx) sb.append("】")
            if (i < words.size - 1) sb.append(" ")
        }
        lastKaraokeText = sb.toString()
        return lastKaraokeText!!
    }

    private fun resetKaraokeState() {
        lastKaraokeLineIdx = -1
        lastKaraokeWordIdx = -1
        lastKaraokeText = null
    }

    private fun getSubtitleText(state: LyricsState): String {
        val posMs = getAaPositionMs()

        if (state.status == LyricsStatus.FOUND) {
            val lineIdx = findLineIndex(state.lines, posMs)
            val line = state.lines.getOrNull(lineIdx)
            if (line != null) {
                val original = if (aaKaraokeEnabled && line.words.isNotEmpty()) {
                    buildKaraokeText(line, lineIdx, posMs, SUBTITLE_KARAOKE_WINDOW_MS)
                } else {
                    line.text
                }
                val trans = state.translatedLines?.getOrNull(lineIdx)?.takeIf { it.isNotBlank() }
                return if (trans != null) "$original\n$trans" else original
            }
        }

        if (state.status == LyricsStatus.PLAIN_ONLY && state.lines.isNotEmpty()) {
            val durationMs = state.track?.durationMs ?: 0
            val idx = if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * state.lines.size).toInt()
                    .coerceIn(0, state.lines.size - 1)
            } else { 0 }
            val original = state.lines[idx].text
            val trans = state.translatedLines?.getOrNull(idx)?.takeIf { it.isNotBlank() }
            return if (trans != null) "$original\n$trans" else original
        }

        return when (state.status) {
            LyricsStatus.LOADING -> "Loading lyrics…"
            LyricsStatus.NOT_FOUND -> "No lyrics found"
            LyricsStatus.ERROR -> "Error loading lyrics"
            else -> ""
        }
    }

    // --- MediaSession management ---

    /**
     * Builds the now-playing card metadata.
     *
     * Android Auto decides the font sizes itself: the "display title" is shown big
     * (up to 2 lines) and the "display subtitle" small (1 line). With "Big lyrics"
     * on, the current lyric goes in the big title slot and "Song — Artist" goes in
     * the small subtitle slot. With it off, the original layout is used.
     */
    private fun buildMetadata(state: LyricsState, lyricText: String): MediaMetadataCompat {
        val metaBuilder = MediaMetadataCompat.Builder()

        var trackLine = ""
        state.track?.let { track ->
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.title)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artist)
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, track.album)
            if (track.durationMs > 0) {
                metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, track.durationMs)
            }
            trackLine = if (track.artist.isNotBlank()) {
                "${track.title} — ${track.artist}"
            } else {
                track.title
            }
        }

        if (aaBigLyrics && state.track != null) {
            // Big line: the lyric (or the song name / a note while there is no lyric yet)
            val bigText = lyricText.ifBlank {
                if (state.status == LyricsStatus.FOUND || state.status == LyricsStatus.PLAIN_ONLY) "♪" else trackLine
            }
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, bigText)
            if (bigText != trackLine) {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, trackLine)
            }
        } else {
            if (trackLine.isNotBlank()) {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, trackLine)
            }
            if (lyricText.isNotBlank()) {
                metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, lyricText)
            }
        }

        val art = state.albumArt ?: lastAlbumArt
        if (art != null) {
            metaBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            lastAlbumArt = art
        }

        return metaBuilder.build()
    }

    private fun updateMediaSession(state: LyricsState) {
        val trackChanged = state.track?.title != displayedTrackTitle
        if (trackChanged) {
            mediaSession.isActive = true
            lastSubtitleText = null
            lastAlbumArt = null
            resetKaraokeState()
        }

        val lyricText = getSubtitleText(state)
        lastSubtitleText = lyricText

        mediaSession.setMetadata(buildMetadata(state, lyricText))
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun updateSubtitleKaraoke() {
        val state = mediaTracker.state.value
        if (!state.isPlaying) return
        if (state.status != LyricsStatus.FOUND && state.status != LyricsStatus.PLAIN_ONLY) return

        val lyricText = getSubtitleText(state)
        if (lyricText == lastSubtitleText) return
        lastSubtitleText = lyricText

        mediaSession.setMetadata(buildMetadata(state, lyricText))
        mediaSession.setPlaybackState(buildPlaybackState(state))
    }

    private fun buildPlaybackState(state: LyricsState): PlaybackStateCompat {
        val pbState = if (state.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else if (state.track != null) {
            PlaybackStateCompat.STATE_PAUSED
        } else {
            PlaybackStateCompat.STATE_NONE
        }

        val position = try {
            mediaTracker.getCurrentPositionMs()
        } catch (_: Exception) {
            0L
        }

        return PlaybackStateCompat.Builder()
            .setState(pbState, position, if (state.isPlaying) 1.0f else 0f)
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SEEK_TO
            )
            .build()
    }

    // --- Throttled browse tree updates ---

    private data class WindowInfo(val start: Int, val end: Int, val currentIdx: Int)

    private fun computeWindow(state: LyricsState): WindowInfo {
        val lines = state.lines
        if (lines.isEmpty()) return WindowInfo(-1, -1, -1)

        val isPlain = state.status == LyricsStatus.PLAIN_ONLY
        val currentIdx = if (isPlain) {
            val durationMs = state.track?.durationMs ?: 0
            val posMs = try {
                mediaTracker.getCurrentPositionMs().coerceAtLeast(0)
            } catch (_: Exception) { 0L }
            if (durationMs > 0) {
                ((posMs.toFloat() / durationMs) * lines.size).toInt()
                    .coerceIn(0, lines.size - 1)
            } else 0
        } else {
            val posMs = getAaPositionMs()
            findLineIndex(lines, posMs).coerceAtLeast(0)
        }

        val winSize = if (isPlain) PLAIN_WINDOW_SIZE else WINDOW_SIZE
        val windowEnd = minOf(lines.size, currentIdx + winSize)
        return WindowInfo(currentIdx, windowEnd, currentIdx)
    }

    private fun forceRefresh() {
        displayedWindowStart = -1
        displayedWindowEnd = -1
        displayedCurrentIdx = -1
        lastNotifyTime = 0L
        resetKaraokeState()
        notifyChildrenChanged(LYRICS_ID)
        notifyChildrenChanged(SYNC_MENU_ID)
    }

    private fun throttledNotifyChildren(state: LyricsState) {
        val statusChanged = state.status != displayedStatus
        val trackChanged = state.track?.title != displayedTrackTitle

        if (trackChanged) {
            displayedWindowStart = -1
            displayedWindowEnd = -1
            displayedCurrentIdx = -1
            displayedStatus = state.status
            displayedTrackTitle = state.track?.title
            lastNotifyTime = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            pendingNotify = false
            resetKaraokeState()
            notifyChildrenChanged(LYRICS_ID)
            notifyChildrenChanged(SYNC_MENU_ID)
            return
        }

        val win = computeWindow(state)
        val windowChanged = win.start != displayedWindowStart || win.end != displayedWindowEnd
        val lineChanged = win.currentIdx != displayedCurrentIdx
        val karaokeActive = aaKaraokeEnabled && state.status == LyricsStatus.FOUND
            && state.lines.getOrNull(state.currentIndex)?.words?.isNotEmpty() == true

        if (!windowChanged && !lineChanged && !statusChanged && !karaokeActive) return

        displayedWindowStart = win.start
        displayedWindowEnd = win.end
        displayedCurrentIdx = win.currentIdx
        displayedStatus = state.status

        val now = System.currentTimeMillis()
        val elapsed = now - lastNotifyTime

        if (elapsed >= NOTIFY_THROTTLE_MS) {
            lastNotifyTime = now
            notifyChildrenChanged(LYRICS_ID)
            notifyChildrenChanged(SYNC_MENU_ID)
        } else if (!pendingNotify) {
            pendingNotify = true
            handler.postDelayed({
                pendingNotify = false
                lastNotifyTime = System.currentTimeMillis()
                notifyChildrenChanged(LYRICS_ID)
                notifyChildrenChanged(SYNC_MENU_ID)
            }, NOTIFY_THROTTLE_MS - elapsed)
        }
    }

    // --- Transport controls ---

    private fun getActiveMediaController(): MediaController? {
        // Control the same player the lyrics are following
        mediaTracker.activeController()?.let { return it }
        val sessionManager = getSystemService(MEDIA_SESSION_SERVICE)
            as? android.media.session.MediaSessionManager ?: return null
        return try {
            val component = android.content.ComponentName(
                this, com.autolyrics.media.MediaListenerService::class.java
            )
            sessionManager.getActiveSessions(component)
                .firstOrNull { it.packageName != packageName && it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessionManager.getActiveSessions(component)
                    .firstOrNull { it.packageName != packageName }
        } catch (_: SecurityException) {
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlay() {
            getActiveMediaController()?.transportControls?.play()
        }

        override fun onPause() {
            getActiveMediaController()?.transportControls?.pause()
        }

        override fun onSkipToNext() {
            getActiveMediaController()?.transportControls?.skipToNext()
        }

        override fun onSkipToPrevious() {
            getActiveMediaController()?.transportControls?.skipToPrevious()
        }

        override fun onStop() {
            getActiveMediaController()?.transportControls?.stop()
        }

        override fun onSeekTo(pos: Long) {
            getActiveMediaController()?.transportControls?.seekTo(pos)
        }

        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            if (mediaId == null) return

            // Car sync buttons adjust the current song's timing (remembered per song)
            val step = SYNC_STEPS[mediaId]
            if (step != null || mediaId == SYNC_RESET_ID) {
                if (step != null) mediaTracker.adjustOffset(step.deltaMs)
                else mediaTracker.resetOffset()

                // Learn the car's audio lag from nudges that several songs share
                mediaTracker.currentSongKey()?.let { key ->
                    CarDelayLearner.recordCarNudge(
                        this@LyricsBrowserService, key, mediaTracker.state.value.offsetMs, mediaTracker
                    )
                }
                resetKaraokeState()
                notifyChildrenChanged(LYRICS_ID)
                notifyChildrenChanged(SYNC_MENU_ID)
                return
            }

            if (!mediaId.startsWith("line_")) return
            val index = mediaId.removePrefix("line_").toIntOrNull() ?: return
            val state = mediaTracker.state.value
            if (state.status != LyricsStatus.FOUND) return
            val line = state.lines.getOrNull(index) ?: return
            if (line.timeMs > 0) {
                getActiveMediaController()?.transportControls?.seekTo(line.timeMs)
            }
        }
    }
}
