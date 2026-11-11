package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcParserTest {

    @Test
    fun parsesTwoAndThreeDigitFractions() {
        val lines = LrcParser.parse("[00:12.34]Hello\n[01:02.500]World")
        assertEquals(2, lines.size)
        assertEquals(12_340L, lines[0].timeMs)
        assertEquals("Hello", lines[0].text)
        assertEquals(62_500L, lines[1].timeMs)
    }

    @Test
    fun repeatedTimestampsAndSorting() {
        val lines = LrcParser.parse("[00:30.00][00:10.00]Chorus\n[00:20.00]Verse")
        assertEquals(listOf(10_000L, 20_000L, 30_000L), lines.map { it.timeMs })
        assertEquals("Chorus", lines[0].text)
    }

    @Test
    fun blankLineBecomesNote() {
        val lines = LrcParser.parse("[00:05.00]")
        assertEquals("♪", lines.single().text)
    }

    @Test
    fun ignoresMetadataTags() {
        val lines = LrcParser.parse("[ar:Queen]\n[ti:Song]\n[00:01.00]Text")
        assertEquals(1, lines.size)
    }

    @Test
    fun parsesKaraokeWords() {
        val lines = LrcParser.parseKaraoke("[00:10.00]<00:10.00>I <00:10.50>am <00:11.00>titanium")
        val line = lines.single()
        assertEquals("I am titanium", line.text)
        assertEquals(3, line.words.size)
        assertEquals(10_500L, line.words[1].timeMs)
        assertTrue(line.words.all { it.text.isNotBlank() })
    }
}
