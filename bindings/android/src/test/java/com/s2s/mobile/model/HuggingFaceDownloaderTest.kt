package com.s2s.mobile.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class HuggingFaceDownloaderTest {

    @Test
    fun testResolveShorthandUrl() {
        val shorthand = "hf://Qwen/Qwen2.5-0.5B-Instruct-GGUF@main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        val resolved = HuggingFaceDownloader.resolveUrl(shorthand)
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            resolved
        )
    }

    @Test
    fun testResolveStandardUrlUnchanged() {
        val standard = "https://github.com/snakers4/silero-vad/raw/main/files/silero_vad.onnx"
        val resolved = HuggingFaceDownloader.resolveUrl(standard)
        assertEquals(standard, resolved)
    }

    @Test
    fun testBuildUrl() {
        val url = HuggingFaceDownloader.buildUrl("soniqo/Silero-VAD-v5-ONNX", "silero-vad.onnx", "v1.0")
        assertEquals("https://huggingface.co/soniqo/Silero-VAD-v5-ONNX/resolve/v1.0/silero-vad.onnx", url)
    }

    @Test
    fun testCreateModelSpec() {
        val spec = HuggingFaceDownloader.createModelSpec(
            id = "hf_qwen",
            name = "Qwen 0.5B GGUF",
            category = "LLM",
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxBytes = 491400032L
        )

        assertEquals("hf_qwen", spec.id)
        assertEquals("LLM", spec.category)
        assertEquals(ModelSource.HUGGING_FACE, spec.source)
        assertEquals(
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            spec.url
        )
        assertEquals("qwen2.5-0.5b-instruct-q4_k_m.gguf", spec.targetPath)
        assertEquals(491400032L, spec.approxBytes)
        assertEquals(null, spec.sha256)
    }

    @Test
    fun testCreateModelSpecWithSha256FromLfsOid() {
        // Same integrity guarantee as a curated registry entry when HF exposes an
        // LFS checksum — ModelDownloader hard-fails on mismatch for this the same
        // way it does for a LOCAL spec.
        val sha = "a".repeat(64)
        val spec = HuggingFaceDownloader.createModelSpec(
            id = "hf_qwen",
            name = "Qwen 0.5B GGUF",
            category = "LLM",
            repo = "Qwen/Qwen2.5-0.5B-Instruct-GGUF",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            approxBytes = 491400032L,
            sha256 = sha,
        )
        assertEquals(sha, spec.sha256)
    }
}
