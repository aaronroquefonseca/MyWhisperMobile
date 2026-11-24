package com.mywhisper.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Dictionary WORD bias for the on-device path ([LocalStt.applyWordBoost]).
 *
 * sherpa-onnx 1.13.8 exposes no decoder bias channel for moonshine /
 * whisper (no whisper prompt setter; hotwords require transducer-only
 * modified_beam_search; ruleFsts are ITN rewrites needing precompiled
 * artifacts) — so WORDS ship as explicit post-process near-miss repair,
 * documented in `LocalStt.postProcessLocal`.
 *
 * No sherpa AAR exists off-device, so there is no real decode here:
 * the fixture is a representative raw decode (lowercased, one
 * substitution/insertion off the curated spelling — the standard
 * proper-noun failure mode). Each test compares the biased run against
 * the unbiased control on the SAME fixture. Pure JVM: no Android
 * calls, no native code.
 */
class LocalWordBoostTest {

    @Test
    fun biasedWord_repairsNearMiss_vsUnbiasedControl() {
        // Representative moonshine decode: lowercase + doubled consonant.
        val raw = "deploy to kuberrnetes now"
        val biased = LocalStt.applyWordBoost(raw, listOf("Kubernetes"))
        assertEquals("deploy to Kubernetes now", biased)
        // Unbiased control: same fixture, no words → untouched.
        assertEquals(raw, LocalStt.applyWordBoost(raw, emptyList()))
    }

    @Test
    fun exactMatch_repairsCasing() {
        assertEquals(
            "Kubernetes cluster",
            LocalStt.applyWordBoost("kubernetes cluster", listOf("Kubernetes")),
        )
        // Already canonical: byte-identical.
        assertEquals(
            "Kubernetes cluster",
            LocalStt.applyWordBoost("Kubernetes cluster", listOf("Kubernetes")),
        )
    }

    @Test
    fun exactMatch_beatsFuzzyLongerNeighbor() {
        // Length-desc ordering must not let "Kubernetess" steal the exact
        // "Kubernetes" hit: the curated spelling wins outright.
        assertEquals(
            "Kubernetes",
            LocalStt.applyWordBoost(
                "kubernetes",
                listOf("Kubernetess", "Kubernetes"),
            ),
        )
    }

    @Test
    fun shortTokens_exactOnly() {
        // len < 4: no fuzzy ("cat" stays even next to "car").
        assertEquals("the cat sat", LocalStt.applyWordBoost("the cat sat", listOf("car")))
        // len 4, distance 2 > allowed 1 ("cats" vs "car": sub + delete).
        assertEquals("cats", LocalStt.applyWordBoost("cats", listOf("car")))
    }

    @Test
    fun onsetGate_blocksDistantOnset() {
        // Edit distance 1 but different first letter → no steal.
        assertEquals(
            "xubernetes",
            LocalStt.applyWordBoost("xubernetes", listOf("Kubernetes")),
        )
    }

    @Test
    fun digitTokens_exactOnly() {
        // Exact repair still applies (unambiguous: dictionary spelling wins)…
        assertEquals("k8s cluster", LocalStt.applyWordBoost("K8S cluster", listOf("k8s")))
        // …but digit tokens never fuzzy-match (versions/numbers safe).
        assertEquals("k9s cluster", LocalStt.applyWordBoost("k9s cluster", listOf("k8s")))
        assertEquals("v22 release", LocalStt.applyWordBoost("v22 release", listOf("v2")))
    }

    @Test
    fun phrase_repairsExactCaseInsensitiveOnly() {
        assertEquals(
            "fly to New York tomorrow",
            LocalStt.applyWordBoost("fly to new york tomorrow", listOf("New York")),
        )
        // Near-miss phrases are NOT fuzzy-rewritten (documented limit).
        assertEquals(
            "fly to new yrok tomorrow",
            LocalStt.applyWordBoost("fly to new yrok tomorrow", listOf("New York")),
        )
    }

    @Test
    fun phrase_withDottedCapitalI_neverThrows() {
        // 'İ'.lowercase() expands to 2 chars ("i\u0307"): the old
        // lowercase-then-index phrase pass desynced offsets and threw
        // StringIndexOutOfBoundsException (this fn never throws), killing
        // the local hit. regionMatches scans original offsets: no desync.
        assertEquals(
            "İ New York",
            LocalStt.applyWordBoost("İ new york", listOf("New York")),
        )
        // Already canonical + no phrase present: passthrough, no throw.
        assertEquals(
            "İ New York",
            LocalStt.applyWordBoost("İ New York", listOf("New York")),
        )
        assertEquals(
            "İSTANBUL morgen",
            LocalStt.applyWordBoost("İSTANBUL morgen", listOf("New York")),
        )
    }

    @Test
    fun punctuationBoundaries_preserved() {
        assertEquals(
            "hi Kubernetes, welcome!",
            LocalStt.applyWordBoost("hi kuberrnetes, welcome!", listOf("Kubernetes")),
        )
    }

    @Test
    fun emptyInputs_passthrough() {
        assertEquals("", LocalStt.applyWordBoost("", listOf("Kubernetes")))
        assertEquals("hello world", LocalStt.applyWordBoost("hello world", emptyList()))
        assertEquals(
            "hello world",
            LocalStt.applyWordBoost("hello world", listOf("  ", "")),
        )
    }

    @Test
    fun wordCap_dropsTail() {
        val filler = List(128) { "zzword$it" }
        // 129th entry is past MAX_WORD_BOOST_WORDS → ignored.
        assertEquals(
            "kuberrnetes",
            LocalStt.applyWordBoost("kuberrnetes", filler + "Kubernetes"),
        )
        // Same entry inside the cap repairs.
        assertEquals(
            "Kubernetes",
            LocalStt.applyWordBoost("kuberrnetes", listOf("Kubernetes") + filler),
        )
    }

    @Test
    fun levenshteinCapped_vectors() {
        assertEquals(0, LocalStt.levenshteinCapped("abc", "abc", 2))
        assertEquals(1, LocalStt.levenshteinCapped("kuberrnetes", "kubernetes", 2))
        // Early exit past the cap (exact value past cap is unspecified,
        // only the > cap guarantee matters).
        assertTrue(LocalStt.levenshteinCapped("abc", "xyz", 1) > 1)
        assertTrue(LocalStt.levenshteinCapped("cats", "car", 1) > 1)
    }

    @Test
    fun postProcessLocal_boostsWordsAndLeavesSnippetsToCore() {
        // Snippet triggers ("omw") stay for the core's protected expansion.
        val out = LocalStt.postProcessLocal("omw to kuberrnetes", listOf("Kubernetes"))
        assertEquals("omw to Kubernetes", out)
    }

    @Test
    fun postProcessLocal_noNativeLib_isPassthrough() {
        // JVM unit-test classpath has no mywhisper_core: both loaders
        // must degrade to empty (Throwable-caught) instead of throwing.
        assertEquals("kuberrnetes now", LocalStt.postProcessLocal("kuberrnetes now"))
    }

    @Test
    fun localHit_appliesWordsExactlyOnce_serverUntouched() {
        val words = listOf("Kubernetes")
        val results: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val serverHit = java.util.concurrent.atomic.AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val dictation = Dictation(
                mode = { "Push" },
                startCapture = { _, _, _ ->
                    object : Dictation.Capture {
                        override fun stop() = Unit
                    }
                },
                finishTranscription = {
                    serverHit.set(true)
                    "server text" to "test-engine"
                },
                cancelTranscription = {},
                finishLocal = { _, text, engine, _, _ -> text to engine },
                localTranscribe = { "deploy to kuberrnetes" to "local moonshine-v2-tiny.en" },
                localPostProcess = { LocalStt.postProcessLocal(it, words) },
                pushPcm = { _, _ -> },
                log = {},
                workers = executor,
            ).also {
                it.listener = object : Dictation.Listener {
                    override fun onState(s: Dictation.State, toggled: Boolean) = Unit
                    override fun onResult(text: String) {
                        results.add(text)
                    }
                    override fun onError(msg: String) = Unit
                }
            }
            dictation.pressDown()
            dictation.pressUp()
            executor.submit {}.get(10, TimeUnit.SECONDS)
            assertEquals(listOf("deploy to Kubernetes"), results)
            assertTrue("server must not run on local hit", !serverHit.get())
        } finally {
            executor.shutdownNow()
        }
    }
}
