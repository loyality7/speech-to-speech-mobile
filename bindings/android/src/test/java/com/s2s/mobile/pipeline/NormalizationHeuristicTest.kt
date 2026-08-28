package com.s2s.mobile.pipeline

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AUTO-policy fast path: which transcripts are worth paying a
 * normalizer's latency for.
 *
 * The bias is deliberate and asymmetric. Skipping a transcript that would
 * have benefited costs a slightly rough sentence the downstream model
 * usually copes with. Normalizing one that did not need it costs hundreds
 * of milliseconds on a voice turn, every time. So these tests assert
 * "skips clean speech" at least as hard as they assert "catches messy
 * speech".
 */
class NormalizationHeuristicTest {

    // ── Should normalize ────────────────────────────────────────────────

    @Test
    fun `filler words are worth normalizing`() {
        assertTrue(
            NormalizationHeuristic.benefitsFromNormalization(
                "so um i need to send the report by friday",
            ),
        )
    }

    @Test
    fun `repeated word from ASR is worth normalizing`() {
        assertTrue(NormalizationHeuristic.benefitsFromNormalization("please send the the report today"))
    }

    @Test
    fun `self-correction is worth normalizing`() {
        assertTrue(
            NormalizationHeuristic.benefitsFromNormalization("remind me on friday no wait make that thursday"),
        )
    }

    @Test
    fun `spelled out numbers are worth normalizing`() {
        assertTrue(
            NormalizationHeuristic.benefitsFromNormalization(
                "the invoice came to twenty three thousand four hundred and fifty dollars",
            ),
        )
    }

    @Test
    fun `dictated punctuation is worth normalizing`() {
        assertTrue(NormalizationHeuristic.benefitsFromNormalization("send it to bob at example dot com"))
    }

    @Test
    fun `long unpunctuated utterance is worth normalizing`() {
        assertTrue(
            NormalizationHeuristic.benefitsFromNormalization(
                "i was thinking that we could move the deployment window to sometime next week " +
                    "because the team is still finishing the migration work",
            ),
        )
    }

    // ── Should NOT normalize ────────────────────────────────────────────

    @Test
    fun `short clean command is skipped`() {
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("call John Smith"))
    }

    @Test
    fun `simple question is skipped`() {
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("what time is it"))
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("what is the weather today"))
    }

    @Test
    fun `very short input is skipped`() {
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("yes"))
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("stop"))
        assertFalse(NormalizationHeuristic.benefitsFromNormalization(""))
    }

    @Test
    fun `already punctuated sentence is skipped`() {
        assertFalse(
            NormalizationHeuristic.benefitsFromNormalization("Please send the report to the team by Thursday."),
        )
    }

    @Test
    fun `legitimate repetition is not treated as an ASR artefact`() {
        // "very very" is real English; "the the" is not.
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("that was very very good work"))
    }

    @Test
    fun `a single spelled number is not enough on its own`() {
        // "set a timer for ten minutes" is perfectly usable as-is; requiring
        // two number words avoids normalizing every simple timer command.
        assertFalse(NormalizationHeuristic.benefitsFromNormalization("set a timer for ten minutes"))
    }
}
