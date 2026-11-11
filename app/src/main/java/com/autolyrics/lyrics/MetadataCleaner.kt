package com.autolyrics.lyrics

object MetadataCleaner {

    private val QUALITY_PATTERN = Regex(
        """\b(lossless|hi-?res|hires|flac|hi-?fi|hifi|dolby\s*atmos|spatial\s*audio|atmos|mqa)\b""",
        RegexOption.IGNORE_CASE
    )
    private val BITRATE_PATTERN = Regex("""\b\d{2,3}\s*kbps\b""", RegexOption.IGNORE_CASE)
    private val BIT_DEPTH_PATTERN = Regex("""\b\d{2}-?bit\b""", RegexOption.IGNORE_CASE)
    private val SAMPLE_RATE_PATTERN = Regex("""\b\d{2,3}(\.\d)?\s*khz\b""", RegexOption.IGNORE_CASE)
    private val MULTI_SPACE = Regex("""\s{2,}""")

    fun cleanArtist(raw: String): String {
        val primary = raw.split(Regex("""\s*[•·|]\s*""")).first().trim()
        val noDash = primary.split(Regex("""\s+[-–—]\s+""")).first().trim()
        val cleaned = removeQualityTags(noDash)
        return cleaned.ifBlank { raw.split(Regex("""\s*[•·|]\s*""")).first().trim() }
    }

    fun cleanTitle(raw: String): String {
        var s = raw.trim()
        s = s.replace(
            Regex("""\s*[\(\[].*?\b(official|video|lyric|audio|visualizer|live|acoustic)\b.*?[\)\]]""", RegexOption.IGNORE_CASE),
            ""
        )
        s = removeQualityTags(s)
        return s.trim().ifBlank { raw.trim() }
    }

    fun cleanAlbum(raw: String): String {
        var s = raw.trim()
        s = removeQualityTags(s)
        s = s.replace(
            Regex("""\s*[\(\[].*?\b(deluxe|remaster|expanded|bonus|anniversary|edition|explicit)\b.*?[\)\]]""", RegexOption.IGNORE_CASE),
            ""
        )
        return s.trim().ifBlank { raw.trim() }
    }

    // ---- Extra search variants (for apps like Metrolist / YouTube Music) ----

    private val ANY_BRACKETS = Regex("""\s*[(\[{][^)\]}]*[)\]}]""")
    private val FEAT_TAIL = Regex("""\s+(feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val DASH_TAG_TAIL = Regex(
        """\s+[-–—]\s+[^-–—]*\b(remaster(ed)?|live|version|edit|mix|remix|mono|stereo|acoustic|demo|single|radio|bonus|explicit)\b[^-–—]*$""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_SPLIT = Regex(
        """\s*(,|&|;|/|\s+x\s+|\s+feat\.?\s+|\s+ft\.?\s+|\s+featuring\s+|\s+with\s+)\s*""",
        RegexOption.IGNORE_CASE
    )
    private val ARTIST_TITLE = Regex("""^(.+?)\s+[-–—]\s+(.+)$""")
    private val CHANNEL_SUFFIX = Regex("""\s*(-\s*topic|vevo|official)\s*$""", RegexOption.IGNORE_CASE)

    /** Title with everything in brackets, "feat. ..." and " - Remastered 2011"-style tails removed. */
    fun stripTitleExtras(title: String): String {
        val s = title
            .replace(ANY_BRACKETS, "")
            .replace(FEAT_TAIL, "")
            .replace(DASH_TAG_TAIL, "")
            .replace(MULTI_SPACE, " ")
            .trim()
        return s.ifBlank { title.trim() }
    }

    /** First artist only: "Calvin Harris, Rihanna" -> "Calvin Harris". */
    fun primaryArtist(artist: String): String {
        val first = artist.split(ARTIST_SPLIT).firstOrNull { it.isNotBlank() }?.trim() ?: artist
        return first.replace(CHANNEL_SUFFIX, "").trim().ifBlank { artist.trim() }
    }

    /**
     * Title/artist pairs to try, best first. Music apps that use YouTube
     * (Metrolist, YouTube Music, NewPipe...) often send several artists joined
     * with commas, extra text in brackets, or a video title like "Artist - Song".
     */
    fun searchVariants(title: String, artist: String): List<Pair<String, String>> {
        val variants = mutableListOf<Pair<String, String>>()
        val primary = primaryArtist(artist)
        val stripped = stripTitleExtras(title)

        variants.add(title to artist)
        variants.add(title to primary)
        variants.add(stripped to primary)

        // "Artist - Song (Official Video)" style titles
        ARTIST_TITLE.find(stripped)?.let { m ->
            val a = m.groupValues[1].trim()
            val t = stripTitleExtras(m.groupValues[2])
            if (a.isNotBlank() && t.isNotBlank()) variants.add(t to primaryArtist(a))
        }

        return variants
            .map { (t, a) -> t.trim() to a.trim() }
            .filter { it.first.isNotBlank() }
            .distinctBy { (t, a) -> t.lowercase() + "|" + a.lowercase() }
    }

    private fun removeQualityTags(s: String): String {
        return s
            .replace(QUALITY_PATTERN, "")
            .replace(BITRATE_PATTERN, "")
            .replace(BIT_DEPTH_PATTERN, "")
            .replace(SAMPLE_RATE_PATTERN, "")
            .replace(MULTI_SPACE, " ")
            .trim()
            .trimEnd('-', '–', '—', ',', ';', ':', '•', '·', '(', '[')
            .trimStart('-', '–', '—', ',', ';', ':', '•', '·', ')', ']')
            .trim()
    }
}
