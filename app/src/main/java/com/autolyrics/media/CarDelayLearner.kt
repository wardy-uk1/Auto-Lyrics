package com.autolyrics.media

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Learns the car's audio delay from the timing nudges made on the car's Sync tab.
 *
 * If several different songs all needed roughly the same nudge, that shared amount
 * is the car's audio lag rather than a problem with each song's lyrics. The learner
 * moves it into the "Car delay" setting (applies to every song) and takes it off
 * those songs' own timing, so future songs are in time straight away.
 *
 * Only nudges made in the car are used; phone-side nudges usually mean the lyrics
 * themselves are off.
 */
object CarDelayLearner {

    private const val PREFS = "auto_lyrics_prefs"
    private const val KEY_ENABLED = "aa_learn_delay"
    private const val KEY_NUDGES = "aa_learn_nudges"
    private const val KEY_AA_OFFSET = "aa_offset_ms"
    private const val KEY_LAST_LEARNED = "aa_learned_shift_ms"
    private const val SONG_OFFSETS = "song_offsets"

    /** Minimum different songs before learning. */
    const val MIN_SONGS = 3
    /** Ignore small differences (ms). */
    const val MIN_SHIFT_MS = 150L
    /** Share of songs that must agree with the typical nudge. */
    const val AGREEMENT = 0.7
    /** How close (ms) a song's nudge must be to the typical value to "agree". */
    const val TOLERANCE_MS = 400L
    private const val MAX_REMEMBERED = 20

    private val gson = Gson()

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** The most recent amount learned (ms), or 0 if nothing learned yet. */
    fun lastLearnedShift(context: Context): Long =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_LEARNED, 0L)

    /**
     * Pure decision step (unit-tested): given each song's current car nudge (ms),
     * returns how much to move into the car delay, or null if there's no clear pattern.
     */
    fun decideShift(nudges: List<Long>): Long? {
        if (nudges.size < MIN_SONGS) return null
        val sorted = nudges.sorted()
        val median = sorted[sorted.size / 2]
        if (kotlin.math.abs(median) < MIN_SHIFT_MS) return null
        val agreeing = nudges.count { n ->
            n != 0L && (n > 0) == (median > 0) && kotlin.math.abs(n - median) <= TOLERANCE_MS
        }
        if (agreeing < kotlin.math.ceil(nudges.size * AGREEMENT)) return null
        // Round to the nearest 50 ms so the setting stays tidy
        return Math.round(median / 50.0) * 50
    }

    /**
     * Call after a car Sync-tab button changes a song's timing.
     * @param songKey "title|artist" of the song
     * @param songOffsetMs that song's timing after the nudge
     * @return the amount moved into the car delay, or null if nothing was learned
     */
    fun recordCarNudge(
        context: Context,
        songKey: String,
        songOffsetMs: Long,
        tracker: MediaTracker
    ): Long? {
        if (!isEnabled(context)) return null
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val nudges = loadNudges(context)
        nudges.remove(songKey)
        nudges[songKey] = songOffsetMs                      // newest last
        while (nudges.size > MAX_REMEMBERED) nudges.remove(nudges.keys.first())

        val shift = decideShift(nudges.values.toList())
        if (shift == null || shift == 0L) {
            saveNudges(context, nudges)
            return null
        }

        // Move the shared amount into the car delay...
        val carDelay = prefs.getLong(KEY_AA_OFFSET, 0L)
        prefs.edit()
            .putLong(KEY_AA_OFFSET, carDelay + shift)
            .putLong(KEY_LAST_LEARNED, shift)
            .apply()

        // ...and take it off each of those songs' own timing
        val songPrefs = context.getSharedPreferences(SONG_OFFSETS, Context.MODE_PRIVATE)
        val edit = songPrefs.edit()
        for (key in nudges.keys) {
            val newValue = songPrefs.getLong(key, 0L) - shift
            if (newValue == 0L) edit.remove(key) else edit.putLong(key, newValue)
        }
        edit.apply()

        // Keep the song that's playing now consistent
        tracker.setOffsetWithoutSaving(tracker.state.value.offsetMs - shift)

        // Start learning afresh from here
        saveNudges(context, LinkedHashMap())
        return shift
    }

    private fun loadNudges(context: Context): LinkedHashMap<String, Long> {
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_NUDGES, null)
            ?: return LinkedHashMap()
        return try {
            val type = object : TypeToken<LinkedHashMap<String, Long>>() {}.type
            gson.fromJson<LinkedHashMap<String, Long>>(json, type) ?: LinkedHashMap()
        } catch (_: Exception) {
            LinkedHashMap()
        }
    }

    private fun saveNudges(context: Context, nudges: LinkedHashMap<String, Long>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_NUDGES, gson.toJson(nudges))
            .apply()
    }
}
