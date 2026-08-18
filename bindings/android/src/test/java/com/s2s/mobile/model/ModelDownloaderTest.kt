package com.s2s.mobile.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ModelDownloaderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testDefaultRegistrySpecs() {
        val stack = ModelRegistry.DEFAULT_STACK
        assertEquals(4, stack.size)
        assertEquals("VAD", stack[0].category)
        assertEquals("STT", stack[1].category)
        assertEquals("TTS", stack[2].category)
        assertEquals("LLM", stack[3].category)
    }

    @Test
    fun testRegistrySpecsValid() {
        val models = ModelRegistry.ALL_MODELS
        assertTrue(models.isNotEmpty())
        for (spec in models) {
            assertTrue("Model ${spec.id} missing name", spec.name.isNotBlank())
            assertTrue("Model ${spec.id} missing url", spec.url.isNotBlank())
            assertTrue("Model ${spec.id} missing targetPath", spec.targetPath.isNotBlank())
            assertTrue("Model ${spec.id} approxBytes must be positive", spec.approxBytes > 0)
        }
    }

    @Test
    fun testAllRegistrySpecsHaveSha256() {
        // Curated registry entries are all LOCAL and must carry a verified checksum —
        // this is the guarantee issue #21 exists to protect. Dynamically-resolved
        // HUGGING_FACE specs are allowed to lack one (see HuggingFaceDownloaderTest);
        // that relaxation must never leak into the curated registry.
        val models = ModelRegistry.ALL_MODELS
        assertTrue(models.isNotEmpty())
        for (spec in models) {
            assertEquals("Model ${spec.id} should be sourced LOCAL", ModelSource.LOCAL, spec.source)
            assertTrue("Model ${spec.id} missing sha256 checksum", !spec.sha256.isNullOrBlank())
        }
    }

    @Test
    fun testPresentValidationForMissingFile() {
        val dir = tempFolder.newFolder("models")
        val downloader = ModelDownloader(dir)
        val spec = ModelSpec(
            name = "Test Model",
            url = "http://localhost/test.bin",
            targetPath = "test.bin",
            archive = false,
            approxBytes = 1000L,
        )

        assertFalse(downloader.present(spec))
        assertEquals(1, downloader.missing(listOf(spec)).size)
    }

    @Test
    fun testPresentValidationForExistingFile() {
        val dir = tempFolder.newFolder("models")
        val file = File(dir, "test.bin")
        file.writeBytes(ByteArray(1000))

        val downloader = ModelDownloader(dir)
        val spec = ModelSpec(
            name = "Test Model",
            url = "http://localhost/test.bin",
            targetPath = "test.bin",
            archive = false,
            approxBytes = 1000L,
        )

        assertTrue(downloader.present(spec))
        assertTrue(downloader.missing(listOf(spec)).isEmpty())
    }

    @Test
    fun testHuggingFaceSpecAllowsNullSha256() {
        // Dynamic HF specs may lack a checksum (not every file is an LFS object) —
        // present() must not require one; ModelDownloader.downloadSpec falls back to
        // Content-Length verification only for these, never a silent downgrade of a
        // checksum that WAS known (that guarantee is testAllRegistrySpecsHaveSha256).
        val dir = tempFolder.newFolder("models")
        val file = File(dir, "custom.gguf")
        file.writeBytes(ByteArray(1000))

        val spec = HuggingFaceDownloader.createModelSpec(
            id = "custom_llm",
            name = "Custom LLM",
            category = "LLM",
            repo = "someorg/some-repo",
            filename = "custom.gguf",
            approxBytes = 1000L,
        )
        assertEquals(ModelSource.HUGGING_FACE, spec.source)
        assertEquals(null, spec.sha256)

        val downloader = ModelDownloader(dir)
        assertTrue(downloader.present(spec))
    }

    @Test
    fun testDiskUsageAndModelDeletion() = kotlinx.coroutines.runBlocking {
        val dir = tempFolder.newFolder("models")
        val file = File(dir, "test.bin")
        val content = ByteArray(2048)
        file.writeBytes(content)

        val downloader = ModelDownloader(dir)
        val spec = ModelSpec(
            id = "test_spec",
            name = "Test Model",
            url = "http://localhost/test.bin",
            targetPath = "test.bin",
            archive = false,
            approxBytes = 2000L,
        )

        assertEquals(2048L, downloader.diskUsage(spec))
        assertEquals(2048L, downloader.totalDiskUsage())

        val installed = downloader.getInstalledModels(listOf(spec))
        assertEquals(1, installed.size)
        assertTrue(installed[0].isInstalled)
        assertEquals(2048L, installed[0].diskUsageBytes)

        val deleted = downloader.deleteModel(spec)
        assertTrue(deleted)
        assertFalse(file.exists())
        assertEquals(0L, downloader.diskUsage(spec))
        assertEquals(0L, downloader.totalDiskUsage())
    }
}
