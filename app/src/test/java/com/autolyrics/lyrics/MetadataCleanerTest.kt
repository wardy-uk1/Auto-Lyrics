package com.autolyrics.lyrics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataCleanerTest {

    @Test
    fun stripsQualityTags() {
        assertEquals("Titanium", MetadataCleaner.cleanTitle("Titanium - Lossless"))
        assertEquals("Queen", MetadataCleaner.cleanArtist("Queen • Hi-Res"))
    }

    @Test
    fun stripsOfficialVideoFromTitle() {
        assertEquals("Lose Yourself", MetadataCleaner.cleanTitle("Lose Yourself (Official Video)"))
    }

    @Test
    fun youtubeTopicChannelArtist() {
        assertEquals("Eminem", MetadataCleaner.cleanArtist("Eminem - Topic"))
    }

    @Test
    fun primaryArtistTakesFirstOfSeveral() {
        assertEquals("Rihanna", MetadataCleaner.primaryArtist("Rihanna, Calvin Harris"))
        assertEquals("Artist A", MetadataCleaner.primaryArtist("Artist A x Artist B"))
        assertEquals("Eminem", MetadataCleaner.primaryArtist("EminemVEVO"))
    }

    @Test
    fun stripTitleExtrasRemovesFeatAndRemaster() {
        assertEquals("We Found Love", MetadataCleaner.stripTitleExtras("We Found Love (feat. Calvin Harris)"))
        assertEquals("Bohemian Rhapsody", MetadataCleaner.stripTitleExtras("Bohemian Rhapsody - Remastered 2011"))
        assertEquals("Song", MetadataCleaner.stripTitleExtras("Song feat. Somebody"))
    }

    @Test
    fun searchVariantsTriesFullArtistFirst() {
        val v = MetadataCleaner.searchVariants("September", "Earth, Wind & Fire")
        assertEquals("September" to "Earth, Wind & Fire", v.first())
        assertTrue(v.contains("September" to "Earth"))
    }

    @Test
    fun searchVariantsSplitsArtistDashSong() {
        val v = MetadataCleaner.searchVariants("Eminem - Lose Yourself", "EminemVEVO")
        assertTrue(v.contains("Lose Yourself" to "Eminem"))
    }

    @Test
    fun searchVariantsHasNoDuplicates() {
        val v = MetadataCleaner.searchVariants("Titanium", "Madilyn Bailey")
        assertEquals(listOf("Titanium" to "Madilyn Bailey"), v)
    }
}
