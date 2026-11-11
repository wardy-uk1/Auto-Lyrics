package com.autolyrics.media

import android.content.Context

/**
 * Which music apps Auto Lyrics follows. Every app that shows up with a media
 * session is remembered ("seen"); the user can untick apps on the phone so
 * they are ignored (e.g. WhatsApp voice notes, podcast apps).
 */
object AppFilter {
    private const val PREFS = "auto_lyrics_prefs"
    private const val KEY_IGNORED = "ignored_packages"
    private const val KEY_SEEN = "seen_packages"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ignored(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_IGNORED, emptySet())?.toSet() ?: emptySet()

    fun setIgnored(context: Context, packages: Set<String>) {
        prefs(context).edit().putStringSet(KEY_IGNORED, packages.toSet()).apply()
    }

    fun seen(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_SEEN, emptySet())?.toSet() ?: emptySet()

    fun recordSeen(context: Context, packages: Collection<String>) {
        val current = seen(context)
        if (current.containsAll(packages)) return
        prefs(context).edit().putStringSet(KEY_SEEN, current + packages).apply()
    }
}
