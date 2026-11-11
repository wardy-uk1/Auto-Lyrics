package com.autolyrics.lyrics

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Third lyrics source: NetEase Cloud Music (music.163.com).
 * Free, no account needed, large catalogue with line-synced LRC lyrics.
 *
 * Flow: search for the song -> pick the result that really matches
 * title + artist (+ duration) -> fetch its LRC lyrics.
 * If no result matches confidently, returns null rather than risk wrong lyrics.
 */
object NetEaseClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private const val BASE_URL = "https://music.163.com/api"
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36"
    private const val REFERER = "https://music.163.com/"

    data class SearchResponse(
        @SerializedName("code") val code: Int?,
        @SerializedName("result") val result: SearchResult?
    )

    data class SearchResult(
        @SerializedName("songs") val songs: List<Song>?
    )

    data class Song(
        @SerializedName("id") val id: Long?,
        @SerializedName("name") val name: String?,
        @SerializedName("artists") val artists: List<Artist>?,
        @SerializedName("duration") val duration: Long?
    )

    data class Artist(
        @SerializedName("name") val name: String?
    )

    data class LyricResponse(
        @SerializedName("code") val code: Int?,
        @SerializedName("lrc") val lrc: LyricBlock?
    )

    data class LyricBlock(
        @SerializedName("lyric") val lyric: String?
    )

    data class NetEaseResult(
        val lyrics: String,
        val synced: Boolean
    )

    /** Credit lines NetEase puts at the start of many songs ("作词 : ...", "Composer : ..."). */
    private val CREDIT_LINE = Regex(
        """^\s*(作词|作曲|编曲|制作人|制作|监制|混音|母带|和声|吉他|贝斯|鼓|录音|出品|发行|词|曲|""" +
            """lyricist|lyrics|composer|producer|arranger|written by|mixed by|mastered by)\s*[:：]""",
        RegexOption.IGNORE_CASE
    )
    private val TIMESTAMP_PREFIX = Regex("""^(\[\d{2}:\d{2}(?:[.:]\d{1,3})?])+""")
    private val INSTRUMENTAL = Regex("""纯音乐|请欣赏|instrumental""", RegexOption.IGNORE_CASE)

    fun getLyrics(trackName: String, artistName: String, durationMs: Long): NetEaseResult? {
        val songId = findSongId(trackName, artistName, durationMs) ?: return null
        return lyricsForSong(songId)
    }

    /** Lyrics for a specific NetEase song id (credit lines removed). */
    fun lyricsForSong(songId: Long): NetEaseResult? {
        val raw = fetchLyric(songId) ?: return null

        val cleaned = raw.lines()
            .filterNot { line ->
                val text = line.replace(TIMESTAMP_PREFIX, "").trim()
                text.startsWith("{") || CREDIT_LINE.containsMatchIn(text)
            }
            .joinToString("\n")

        val textOnly = cleaned.lines()
            .map { it.replace(TIMESTAMP_PREFIX, "").trim() }
            .filter { it.isNotBlank() }
        if (textOnly.isEmpty()) return null
        if (textOnly.size <= 2 && textOnly.all { INSTRUMENTAL.containsMatchIn(it) }) return null

        val synced = cleaned.lines().any { TIMESTAMP_PREFIX.containsMatchIn(it.trim()) }
        return if (synced) {
            NetEaseResult(normalizeTimestamps(cleaned), synced = true)
        } else {
            NetEaseResult(textOnly.joinToString("\n"), synced = false)
        }
    }

    private fun findSongId(trackName: String, artistName: String, durationMs: Long): Long? {
        val query = if (artistName.isNotBlank()) "$trackName $artistName" else trackName
        val songs = searchSongs(query)
        if (songs.isEmpty()) return null
        return pickBest(songs, trackName, artistName, durationMs)
    }

    /** Raw NetEase search results for a free-text query (used by the picker too). */
    fun searchSongs(query: String): List<Song> {
        val body = FormBody.Builder()
            .add("s", query)
            .add("type", "1")
            .add("limit", "10")
            .add("offset", "0")
            .build()

        val request = Request.Builder()
            .url("$BASE_URL/search/get")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val json = response.body?.string() ?: return emptyList()
                gson.fromJson(json, SearchResponse::class.java)?.result?.songs.orEmpty()
                    .filter { it.id != null && !it.name.isNullOrBlank() }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun pickBest(songs: List<Song>, trackName: String, artistName: String, durationMs: Long): Long? {
        val wantTitle = normalize(trackName)
        val wantArtist = normalize(artistName)

        fun titleMatches(s: Song): Boolean {
            val t = normalize(s.name ?: return false)
            if (t.isEmpty() || wantTitle.isEmpty()) return false
            return t == wantTitle || t.startsWith(wantTitle) || wantTitle.startsWith(t)
        }

        fun artistMatches(s: Song): Boolean {
            if (wantArtist.isEmpty()) return true
            return s.artists.orEmpty().any { a ->
                val n = normalize(a.name ?: "")
                n.isNotEmpty() && (wantArtist.contains(n) || n.contains(wantArtist))
            }
        }

        fun durationMatches(s: Song): Boolean {
            val d = s.duration ?: return true
            if (durationMs <= 0 || d <= 0) return true
            return kotlin.math.abs(d - durationMs) <= 10_000
        }

        val best = songs.firstOrNull { titleMatches(it) && artistMatches(it) && durationMatches(it) }
            ?: songs.firstOrNull { titleMatches(it) && artistMatches(it) }
        return best?.id
    }

    private fun fetchLyric(songId: Long): String? {
        val url = "$BASE_URL/song/lyric".toHttpUrl().newBuilder()
            .addQueryParameter("id", songId.toString())
            .addQueryParameter("lv", "1")
            .addQueryParameter("kv", "1")
            .addQueryParameter("tv", "-1")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", REFERER)
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = response.body?.string() ?: return@use null
                gson.fromJson(json, LyricResponse::class.java)?.lrc?.lyric?.takeIf { it.isNotBlank() }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The app's LRC parser expects [mm:ss.xx] or [mm:ss.xxx]. NetEase sometimes uses
     * [mm:ss], [mm:ss:xx] or [mm:ss.x] — rewrite those into the standard form.
     */
    private val ANY_TIMESTAMP = Regex("""\[(\d{2}):(\d{2})(?:[.:](\d{1,3}))?]""")

    private fun normalizeTimestamps(lrc: String): String {
        return ANY_TIMESTAMP.replace(lrc) { m ->
            val frac = m.groupValues[3]
            val ms = when (frac.length) {
                0 -> "00"
                1 -> frac + "0"
                else -> frac
            }
            "[${m.groupValues[1]}:${m.groupValues[2]}.$ms]"
        }
    }

    /** Lower-case, drop brackets/feat. parts and anything that isn't a letter or digit. */
    private fun normalize(s: String): String {
        return s.lowercase()
            .replace(Regex("""\s*[(\[].*?[)\]]"""), "")
            .replace(Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$"""), "")
            .filter { it.isLetterOrDigit() }
    }
}
