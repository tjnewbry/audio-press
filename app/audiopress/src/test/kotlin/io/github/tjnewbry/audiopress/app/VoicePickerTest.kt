package io.github.tjnewbry.audiopress.app

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoicePickerTest {
    private val bella = voiceById("af_bella")
    private val santa = voiceById("am_santa")

    @Test
    fun `there are 28 voices in four groups, each with a name and a label`() {
        assertEquals(28, KOKORO_VOICES.size)
        assertEquals(28, KOKORO_VOICES.map { it.id }.distinct().size)
        assertEquals(listOf(11, 9, 4, 4), listOf("af_", "am_", "bf_", "bm_").map { p -> KOKORO_VOICES.count { it.id.startsWith(p) } })
        assertEquals("Bella (American Female)", bella.label)
        assertEquals("Santa (American Male)", santa.label)
        assertEquals("Isabella (British Female)", voiceById("bf_isabella").label)
        assertEquals("Lewis (British Male)", voiceById("bm_lewis").label)
        assertEquals("en-us", santa.languageTag)
        assertEquals("en-gb", voiceById("bm_george").languageTag)
        assertEquals(KOKORO_VOICES.first(), voiceById("af_alloy"))
        assertEquals("am_santa", KOKORO_VOICES.single { it.name == "Santa" }.id)
    }

    @Test
    fun `no voice selected gives a disabled select a voice button`() {
        val action = VoicePicker(VoiceQuality.LITE).action(downloaded = false, downloadMb = 120)

        assertEquals(VoiceAction("Select a voice", enabled = false), action)
        assertNull(VoicePicker(VoiceQuality.LITE).choice)
    }

    @Test
    fun `a voice on a model that is not downloaded offers to download and bake`() {
        val lite = VoicePicker(VoiceQuality.LITE).withVoice(bella).action(downloaded = false, downloadMb = 137)
        val medium = VoicePicker(VoiceQuality.MEDIUM).withVoice(bella).action(downloaded = false, downloadMb = 222)
        val heavy = VoicePicker(VoiceQuality.HEAVY).withVoice(santa).action(downloaded = false, downloadMb = 370)

        assertEquals(VoiceAction("Download lite (137mb) and\nbake audio using Bella (lite)", enabled = true), lite)
        assertEquals("Download medium (222mb) and\nbake audio using Bella (medium)", medium.text)
        assertEquals("Download heavy (370mb) and\nbake audio using Santa (heavy)", heavy.text)
    }

    @Test
    fun `a voice on a downloaded model offers to bake`() {
        val action = VoicePicker(VoiceQuality.HEAVY).withVoice(bella).action(downloaded = true)

        assertEquals(VoiceAction("Bake audio using Bella (heavy)", enabled = true), action)
    }

    @Test
    fun `switching tabs keeps the voice, and the button then names the new quality`() {
        val picked = VoicePicker(VoiceQuality.MEDIUM).withVoice(bella)

        val switched = picked.withQuality(VoiceQuality.HEAVY)
        assertEquals(VoicePicker(VoiceQuality.HEAVY, bella), switched)
        assertEquals("Bake audio using Bella (heavy)", switched.action(downloaded = true).text)
        val lite = picked.withQuality(VoiceQuality.LITE).action(downloaded = false, downloadMb = 137)
        assertEquals("Download lite (137mb) and\nbake audio using Bella (lite)", lite.text)
        assertEquals(VoiceChoice("af_bella", VoiceQuality.HEAVY), switched.choice)
        assertFalse(VoicePicker(VoiceQuality.HEAVY).action(downloaded = true).enabled)
    }

    @Test
    fun `the choice to bake is the voice and the tab`() {
        val choice = VoicePicker(VoiceQuality.MEDIUM).withVoice(santa).choice

        assertEquals(VoiceChoice("am_santa", VoiceQuality.MEDIUM), choice)
        assertEquals(santa, choice?.voice)
    }

    @Test
    fun `each tab has its note, and every note is three lines high or shorter`() {
        val lite = "Smallest download,\nUses the least memory when baking,\nSounds nearly the same as Heavy."
        val heavy = "Largest download,\nUses the most memory when baking,\nHighest fidelity."
        assertEquals(lite, VoicePicker(VoiceQuality.LITE).note)
        assertEquals("\nBalanced option.", VoicePicker(VoiceQuality.MEDIUM).note)
        assertEquals(heavy, VoicePicker(VoiceQuality.HEAVY).note)
        assertTrue(VoiceQuality.entries.all { it.note.lines().size <= 3 })
    }

    @Test
    fun `the samples are found by quality and voice`() {
        assertEquals("voices/lite/af_bella.ogg", bella.sampleAsset(VoiceQuality.LITE))
        assertEquals("voices/heavy/am_santa.ogg", santa.sampleAsset(VoiceQuality.HEAVY))
        assertEquals(listOf("Lite", "Medium", "Heavy"), VoiceQuality.entries.map { it.label })
    }

    @Test
    fun `every voice has a sample at every quality in the app assets`() {
        val assets = File("src/main/assets")
        for (quality in VoiceQuality.entries) {
            for (voice in KOKORO_VOICES) {
                assertTrue(File(assets, voice.sampleAsset(quality)).isFile, "missing ${voice.sampleAsset(quality)}")
            }
        }
    }
}
