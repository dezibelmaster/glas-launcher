package de.glas.vorlesen

/**
 * Turns the text blocks found on a camera picture into readable text:
 * puts them in reading order (also for a double page), joins broken lines and hyphenated words
 * and drops stray marks such as page numbers.
 */
object PageText {

    /** One block of text as found on the picture, with its box in the upright picture. */
    class Block(val left: Int, val top: Int, val right: Int, val bottom: Int, val lines: List<String>) {
        val width get() = right - left
    }

    /** A sentence and where it sits in the full text, for highlighting while it is read. */
    data class Sentence(val start: Int, val end: Int, val text: String)

    fun build(blocks: List<Block>, imageWidth: Int, imageHeight: Int): String {
        val useful = blocks.filter { b -> b.lines.joinToString("").count { it.isLetter() } >= 2 }
        val sb = StringBuilder()
        var lastLength = 0
        for (block in readingOrder(useful, imageWidth, imageHeight)) {
            val text = joinLines(block.lines).trim()
            if (text.isEmpty()) continue
            if (sb.isNotEmpty()) {
                // A sentence running on into the next block stays in the same paragraph
                // (a short block without a full stop is more likely a heading)
                val runsOn = !endsSentence(sb) && (text.first().isLowerCase() || lastLength > 30)
                sb.append(if (runsOn) " " else "\n\n")
            }
            sb.append(text)
            lastLength = text.length
        }
        return sb.toString()
    }

    private fun readingOrder(blocks: List<Block>, w: Int, h: Int): List<Block> {
        val mid = w / 2
        val margin = w * 0.06
        val narrow = blocks.filter { it.width < w * 0.55 }
        val left = narrow.filter { it.right <= mid + margin }
        val right = narrow.filter { it.left >= mid - margin && it !in left }
        val wide = blocks.filter { it !in left && it !in right }
        val byRows = compareBy<Block>({ it.top / maxOf(1, h / 40) }, { it.left })
        // A double page: text on both halves side by side, so read the left page first, then the right one
        val twoPages = left.isNotEmpty() && right.isNotEmpty() &&
            left.size + right.size >= blocks.size * 0.75 &&
            left.minOf { it.top } < right.maxOf { it.bottom } && right.minOf { it.top } < left.maxOf { it.bottom }
        return if (twoPages) {
            wide.sortedWith(byRows) + left.sortedWith(byRows) + right.sortedWith(byRows)
        } else {
            blocks.sortedWith(byRows)
        }
    }

    private fun joinLines(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) {
                sb.append(line)
                continue
            }
            val last = sb.last()
            if ((last == '-' || last == '¬') && sb.length >= 2 && sb[sb.length - 2].isLetter() && line.first().isLowerCase()) {
                // "Geschich-" + "te" -> "Geschichte"
                sb.setLength(sb.length - 1)
                sb.append(line)
            } else {
                sb.append(' ').append(line)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ")
    }

    private fun endsSentence(sb: CharSequence): Boolean {
        var i = sb.length - 1
        while (i >= 0 && (sb[i] in CLOSING || sb[i].isWhitespace())) i--
        return i >= 0 && sb[i] in ENDINGS
    }

    /** Splits the text into sentences, so the voice can be paused, resumed and followed on screen. */
    fun sentences(text: String): List<Sentence> {
        val result = mutableListOf<Sentence>()
        var start = 0
        fun add(end: Int) {
            var s = start
            var e = end
            while (s < e && text[s].isWhitespace()) s++
            while (e > s && text[e - 1].isWhitespace()) e--
            if (e > s && text.substring(s, e).any { it.isLetterOrDigit() }) result += Sentence(s, e, text.substring(s, e))
            start = end
        }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\n' && i + 1 < text.length && text[i + 1] == '\n') {
                add(i)
            } else if (c in ENDINGS && !(c == '.' && isAbbreviation(text, i))) {
                var j = i + 1
                while (j < text.length && (text[j] in ENDINGS || text[j] in CLOSING)) j++
                // Cut only before a new sentence: "z. B. etwas" stays together
                var k = j
                while (k < text.length && text[k] == ' ') k++
                if (j == text.length || (k > j && k < text.length && !text[k].isLowerCase())) {
                    add(j)
                    i = j
                    continue
                }
            }
            i++
        }
        add(text.length)
        return result
    }

    /** "z. B.", "Dr.", "usw.": a full stop that does not end the sentence. */
    private fun isAbbreviation(text: String, dot: Int): Boolean {
        var s = dot
        while (s > 0 && text[s - 1].isLetter()) s--
        val word = text.substring(s, dot)
        return word.length == 1 || word.lowercase() in ABBREVIATIONS
    }

    /** A piece of a sentence and whether it is quoted speech („…“), so a character can sound different. */
    data class Part(val text: String, val quoted: Boolean)

    fun speechParts(sentence: String): List<Part> {
        val parts = mutableListOf<Part>()
        val sb = StringBuilder()
        var quoted = false
        fun flush() {
            var t = sb.toString().trim()
            sb.setLength(0)
            // Punctuation at the start (", fragte sie") belongs to the piece before
            if (parts.isNotEmpty() && !quoted) {
                val lead = t.takeWhile { !it.isLetterOrDigit() && it !in "„»‹“\"" }
                if (lead.isNotBlank() && lead.length < t.length) {
                    parts[parts.size - 1] = parts.last().copy(text = parts.last().text + lead.trim())
                    t = t.substring(lead.length).trim()
                }
            }
            if (t.isEmpty()) return
            // Bits without words (", ") go with the piece before them
            if (t.none { it.isLetterOrDigit() } && parts.isNotEmpty()) {
                parts[parts.size - 1] = parts.last().copy(text = parts.last().text + t)
            } else {
                parts += Part(t, quoted)
            }
        }
        for (c in sentence) {
            val opens = !quoted && (c in "„»‹“" || c == '"')
            val closes = quoted && (c in "“”«›" || c == '"')
            when {
                opens -> { flush(); quoted = true; sb.append(c) }
                closes -> { sb.append(c); flush(); quoted = false }
                else -> sb.append(c)
            }
        }
        flush()
        // Merge neighbours of the same kind (after leftover punctuation was attached)
        return parts.fold(mutableListOf()) { acc, p ->
            if (acc.isNotEmpty() && acc.last().quoted == p.quoted) acc[acc.size - 1] = Part(acc.last().text + " " + p.text, p.quoted)
            else acc += p
            acc
        }
    }

    /** How much two texts share, from 0 (nothing) to 1 (the same words). */
    fun similarity(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return (wa intersect wb).size.toDouble() / (wa union wb).size
    }

    private fun words(s: String) =
        s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 }.toSet()

    private val ABBREVIATIONS = setOf("dr", "fr", "hr", "nr", "st", "ca", "bzw", "usw", "etc", "vgl", "prof", "str")
    private const val ENDINGS = ".!?…"
    private const val CLOSING = "\"'“”„»«›‹)"
}
