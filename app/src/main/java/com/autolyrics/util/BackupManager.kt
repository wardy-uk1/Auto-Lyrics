package com.autolyrics.util

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * Backs up and restores Auto Lyrics' settings to a single JSON file:
 * - all app settings (delays, switches, chosen music apps, fonts...)
 * - per-song timing
 * - lyrics picked by hand with "Wrong lyrics?"
 *
 * Used to carry everything over when reinstalling (e.g. upgrading 1.x -> 2.0).
 */
object BackupManager {

    private const val FORMAT_VERSION = 1
    private val PREF_FILES = listOf("auto_lyrics_prefs", "song_offsets")
    private const val CACHE_DIR = "lyrics_cache"

    private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

    /** Builds the backup file contents. */
    fun createBackup(context: Context, appVersion: String): String {
        val root = JsonObject()
        root.addProperty("app", "Auto Lyrics")
        root.addProperty("format", FORMAT_VERSION)
        root.addProperty("appVersion", appVersion)
        root.addProperty("createdAt", System.currentTimeMillis())

        val prefsJson = JsonObject()
        for (name in PREF_FILES) {
            prefsJson.add(name, prefsToJson(context.getSharedPreferences(name, Context.MODE_PRIVATE)))
        }
        root.add("prefs", prefsJson)

        // Hand-picked lyrics only (the rest can simply be downloaded again)
        val pinned = JsonArray()
        File(context.filesDir, CACHE_DIR).listFiles()?.forEach { f ->
            try {
                val text = f.readText()
                val obj = JsonParser.parseString(text).asJsonObject
                if (obj.get("pinned")?.asBoolean == true) {
                    val entry = JsonObject()
                    entry.addProperty("file", f.name)
                    entry.add("data", obj)
                    pinned.add(entry)
                }
            } catch (_: Exception) { }
        }
        root.add("pickedLyrics", pinned)

        return gson.toJson(root)
    }

    data class RestoreResult(val settings: Int, val songTimings: Int, val pickedLyrics: Int)

    /** Restores a backup. Throws IllegalArgumentException if the file isn't an Auto Lyrics backup. */
    fun restoreBackup(context: Context, json: String): RestoreResult {
        val root = try {
            JsonParser.parseString(json).asJsonObject
        } catch (e: Exception) {
            throw IllegalArgumentException("That file isn't an Auto Lyrics backup.")
        }
        if (root.get("app")?.asString != "Auto Lyrics") {
            throw IllegalArgumentException("That file isn't an Auto Lyrics backup.")
        }

        var settings = 0
        var timings = 0
        val prefsJson = root.getAsJsonObject("prefs") ?: JsonObject()
        for (name in PREF_FILES) {
            val obj = prefsJson.getAsJsonObject(name) ?: continue
            val count = jsonToPrefs(obj, context.getSharedPreferences(name, Context.MODE_PRIVATE))
            if (name == "song_offsets") timings += count else settings += count
        }

        var picked = 0
        val dir = File(context.filesDir, CACHE_DIR).apply { mkdirs() }
        root.getAsJsonArray("pickedLyrics")?.forEach { el ->
            try {
                val entry = el.asJsonObject
                val fileName = entry.get("file").asString
                // Only plain cache file names, never paths
                if (!fileName.matches(Regex("""[0-9a-f]+\.json"""))) return@forEach
                File(dir, fileName).writeText(gson.toJson(entry.getAsJsonObject("data")))
                picked++
            } catch (_: Exception) { }
        }
        return RestoreResult(settings, timings, picked)
    }

    private fun prefsToJson(prefs: SharedPreferences): JsonObject {
        val out = JsonObject()
        for ((key, value) in prefs.all) {
            val item = JsonObject()
            when (value) {
                is Boolean -> { item.addProperty("t", "b"); item.addProperty("v", value) }
                is Int -> { item.addProperty("t", "i"); item.addProperty("v", value) }
                is Long -> { item.addProperty("t", "l"); item.addProperty("v", value) }
                is Float -> { item.addProperty("t", "f"); item.addProperty("v", value) }
                is String -> { item.addProperty("t", "s"); item.addProperty("v", value) }
                is Set<*> -> {
                    item.addProperty("t", "set")
                    val arr = JsonArray()
                    value.filterIsInstance<String>().forEach { arr.add(it) }
                    item.add("v", arr)
                }
                else -> continue
            }
            out.add(key, item)
        }
        return out
    }

    private fun jsonToPrefs(obj: JsonObject, prefs: SharedPreferences): Int {
        val edit = prefs.edit()
        var count = 0
        for ((key, el) in obj.entrySet()) {
            try {
                val item = el.asJsonObject
                val v = item.get("v")
                when (item.get("t").asString) {
                    "b" -> edit.putBoolean(key, v.asBoolean)
                    "i" -> edit.putInt(key, v.asInt)
                    "l" -> edit.putLong(key, v.asLong)
                    "f" -> edit.putFloat(key, v.asFloat)
                    "s" -> edit.putString(key, v.asString)
                    "set" -> edit.putStringSet(key, v.asJsonArray.map { it.asString }.toSet())
                    else -> continue
                }
                count++
            } catch (_: Exception) { }
        }
        edit.apply()
        return count
    }
}
