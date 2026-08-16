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
