package de.glas.vorlesen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PageTextTest {

    private fun block(l: Int, t: Int, r: Int, b: Int, vararg lines: String) = PageText.Block(l, t, r, b, lines.toList())

    @Test
    fun joinsHyphenatedWordsAndLines() {
        val text = PageText.build(
            listOf(block(100, 100, 900, 300, "Es war einmal ein klei-", "ner Bär, der im Wald", "wohnte.")),
            1000, 1400
        )
        assertEquals("Es war einmal ein kleiner Bär, der im Wald wohnte.", text)
    }

    @Test
    fun keepsRealDashes() {
        val text = PageText.build(listOf(block(0, 0, 900, 100, "Mutter-", "Kind-Gruppe")), 1000, 1400)
        assertEquals("Mutter- Kind-Gruppe", text)
    }

    @Test
    fun readsDoublePageLeftThenRight() {
        val blocks = listOf(
            block(560, 100, 950, 300, "Rechts oben."),
            block(50, 700, 450, 900, "Links unten."),
            block(560, 700, 950, 900, "Rechts unten."),
            block(50, 100, 450, 300, "Links oben."),
            block(250, 1300, 270, 1330, "12"),
        )
        val text = PageText.build(blocks, 1000, 1400)
        assertEquals("Links oben.\n\nLinks unten.\n\nRechts oben.\n\nRechts unten.", text)
    }

    @Test
    fun singlePageTopToBottom() {
        val blocks = listOf(
            block(100, 600, 900, 800, "Zweiter Absatz."),
            block(100, 100, 900, 300, "Erster Absatz."),
        )
        assertEquals("Erster Absatz.\n\nZweiter Absatz.", PageText.build(blocks, 1000, 1400))
    }

    @Test
    fun sentenceRunningIntoNextBlockStaysTogether() {
        val blocks = listOf(
            block(100, 100, 900, 300, "Es wurde schon dunkel im Wald, da kam der"),
            block(100, 320, 900, 500, "Fuchs um die Ecke."),
        )
        assertEquals("Es wurde schon dunkel im Wald, da kam der Fuchs um die Ecke.", PageText.build(blocks, 1000, 1400))
    }

    @Test
    fun headingStaysOwnParagraph() {
        val blocks = listOf(
            block(300, 100, 700, 200, "Der kleine Bär"),
            block(100, 300, 900, 500, "Es war einmal ein Bär."),
        )
        assertEquals("Der kleine Bär\n\nEs war einmal ein Bär.", PageText.build(blocks, 1000, 1400))
    }

    @Test
    fun splitsSentences() {
        val text = "„Komm her!“, rief Mama. Der Bär lief z. B. schnell. Was nun?\n\nEnde"
        val s = PageText.sentences(text).map { it.text }
        assertEquals(listOf("„Komm her!“, rief Mama.", "Der Bär lief z. B. schnell.", "Was nun?", "Ende"), s)
        PageText.sentences(text).forEach { assertEquals(it.text, text.substring(it.start, it.end)) }
    }

    @Test
    fun similarity() {
        assertTrue(PageText.similarity("Der kleine Bär schläft", "Der kleine Bär schläft.") > 0.9)
        assertTrue(PageText.similarity("Der kleine Bär schläft", "Ganz andere Wörter hier") < 0.1)
    }
}
