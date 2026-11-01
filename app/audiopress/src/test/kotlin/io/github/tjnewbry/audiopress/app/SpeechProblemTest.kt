package io.github.tjnewbry.audiopress.app

import android.speech.tts.TextToSpeech
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechProblemTest {
    @Test
    fun `speech that works has no problem, even when only English is available`() {
        assertNull(speechProblem(TextToSpeech.SUCCESS, TextToSpeech.LANG_AVAILABLE))
        assertNull(speechProblem(TextToSpeech.SUCCESS, TextToSpeech.LANG_COUNTRY_AVAILABLE, TextToSpeech.LANG_NOT_SUPPORTED))
    }

    @Test
    fun `a missing engine says how to get one`() {
        val message = speechProblem(TextToSpeech.ERROR, TextToSpeech.ERROR)

        assertEquals(NO_ENGINE_MESSAGE, message)
        assertTrue("no working text-to-speech engine" in message.orEmpty())
        assertTrue("Settings" in message.orEmpty())
    }

    @Test
    fun `missing voice data and an unsupported language get different messages`() {
        val missing = speechProblem(TextToSpeech.SUCCESS, TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_MISSING_DATA)
        val unsupported = speechProblem(TextToSpeech.SUCCESS, TextToSpeech.LANG_NOT_SUPPORTED, TextToSpeech.LANG_NOT_SUPPORTED)

        assertTrue("not installed" in missing.orEmpty())
        assertTrue("does not support your language" in unsupported.orEmpty())
        assertNotEquals(missing, unsupported)
    }
}
