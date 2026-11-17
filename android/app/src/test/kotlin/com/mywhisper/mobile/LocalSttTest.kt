package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure helpers of [LocalStt] (no native code is loaded here).
 */
class LocalSttTest {
    @Test
    fun dictionaryPrompt_joinsTrimmedWordsAndSkipsBlanks() {
        assertEquals("Kubernetes, MyWhisper, Aarón", LocalStt.dictionaryPrompt(listOf(" Kubernetes", "", "MyWhisper ", "Aarón")))
        assertEquals("", LocalStt.dictionaryPrompt(emptyList()))
    }

    @Test
    fun dictionaryPrompt_staysUnderTheCharCap() {
        val words = List(200) { "word$it" }
        val prompt = LocalStt.dictionaryPrompt(words, maxChars = 50)
        assertTrue(prompt.length <= 50)
        assertTrue(prompt.startsWith("word0, word1"))
    }
}
