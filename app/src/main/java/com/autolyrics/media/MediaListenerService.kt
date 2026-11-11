package com.autolyrics.media

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class MediaListenerService : NotificationListenerService() {

    private lateinit var sessionManager: MediaSessionManager
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Watchdog: re-check the active players every few seconds. Media session
     * change events can be missed or arrive mid track-change, which used to leave
     * the app following a paused/stale player so the lyrics froze.
     */
    private val watchdog = object : Runnable {
        override fun run() {
            updateSessions()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            pickBestSession(controllers)
        }

    override fun onCreate() {
        super.onCreate()
        sessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsListener,
                ComponentName(this, MediaListenerService::class.java)
            )
            updateSessions()
        } catch (_: SecurityException) { }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_MS)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        handler.removeCallbacks(watchdog)
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        } catch (_: Exception) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        updateSessions()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    private fun updateSessions() {
        try {
            val controllers = sessionManager.getActiveSessions(
                ComponentName(this, MediaListenerService::class.java)
            )
            pickBestSession(controllers)
        } catch (_: SecurityException) { }
    }

    private fun pickBestSession(controllers: List<MediaController>?) {
        val tracker = MediaTracker.getInstance(this)
        val others = controllers?.filter { it.packageName != packageName }.orEmpty()
        AppFilter.recordSeen(this, others.map { it.packageName })
        val ignored = AppFilter.ignored(this)
        val filtered = others.filter { it.packageName !in ignored }
        if (filtered.isEmpty()) {
            tracker.onMediaSessionChanged(null)
            return
        }

        val currentToken = tracker.activeSessionToken()
        val current = filtered.firstOrNull { it.sessionToken == currentToken }

        val best =
            // 1. Something is actually playing
            filtered.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            // 2. Our current player is busy changing track / buffering: stay with it
                ?: current?.takeIf { isBusy(it) }
            // 3. Any other player that is busy changing track / buffering
                ?: filtered.firstOrNull { isBusy(it) }
            // 4. Nothing playing: keep the current player, else the first one
                ?: current
                ?: filtered.first()

        tracker.onMediaSessionChanged(best)
    }

    private fun isBusy(controller: MediaController): Boolean {
        return when (controller.playbackState?.state) {
            PlaybackState.STATE_BUFFERING,
            PlaybackState.STATE_CONNECTING,
            PlaybackState.STATE_SKIPPING_TO_NEXT,
            PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
            PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING -> true
            else -> false
        }
    }

    companion object {
        private const val WATCHDOG_MS = 3000L
    }
}
