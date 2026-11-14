package de.glas.vorlesen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PiperTest {

    @Test
    fun resampleChangesLength() {
        val samples = FloatArray(1000) { it.toFloat() }
        assertEquals(800, PiperNarrator.resample(samples, 1.25f).size)
        assertEquals(1250, PiperNarrator.resample(samples, 0.8f).size)
        val higher = PiperNarrator.resample(samples, 2f)
        assertEquals(500, higher.size)
        assertEquals(10f, higher[5], 0.001f)
    }

    /** Unpacks a real voice package; run with -Dpiper.archive=/path/vits-piper-….tar.bz2 */
    @Test
    fun unpacksVoicePackage() {
        val archive = System.getProperty("piper.archive")?.let { File(it) }
        assumeTrue(archive != null && archive.exists())
        val dir = Files.createTempDirectory("piper").toFile()
        try {
            archive!!.inputStream().use { PiperStore.unpack(it, dir) }
            assertTrue(PiperStore.complete(dir))
            assertTrue(File(dir, "espeak-ng-data/de_dict").exists())
            assertFalse(File(dir, "vits-piper-de_DE-thorsten-medium").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun catalogHasUniqueIds() {
        assertEquals(PiperVoice.ALL.size, PiperVoice.ALL.map { it.id }.toSet().size)
    }
}
