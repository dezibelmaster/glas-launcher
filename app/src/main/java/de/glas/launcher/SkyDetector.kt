package de.glas.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Finds where the sky ends in a photo without a prepared mask (e.g. from Reddit), so the large
 * clock can disappear behind the landscape there too. It looks for the line with the strongest
 * colour change from left to right, held together so it can't jump around, and accepts it only
 * when the part above is calm like a sky and the part below has more structure. In doubt it
 * gives up and the clock simply stands free.
 */
object SkyDetector {
    private const val W = 320
    private const val H = 180

    /** A mask like the prepared ones (white = landscape), at the photo's size; null when unsure. Blocking. */
    fun mask(photo: Bitmap): Bitmap? = runCatching { detect(photo) }.getOrNull()

    private fun detect(photo: Bitmap): Bitmap? {
        val small = Bitmap.createScaledBitmap(photo, W, H, true)
        val px = IntArray(W * H)
        small.getPixels(px, 0, W, 0, 0, W, H)
        val r = FloatArray(W * H) { Color.red(px[it]) / 255f }
        val g = FloatArray(W * H) { Color.green(px[it]) / 255f }
        val b = FloatArray(W * H) { Color.blue(px[it]) / 255f }

        // Colour change between the 6 rows above and below each point
        val k = 6
        val e = Array(H) { FloatArray(W) }
        for (x in 0 until W) {
            for (y in (H * .18f).toInt() until (H * .88f).toInt()) {
                var dr = 0f; var dg = 0f; var db = 0f
                for (i in 1..k) {
                    val a = (y - i) * W + x
                    val c = (y + i - 1) * W + x
                    dr += r[a] - r[c]; dg += g[a] - g[c]; db += b[a] - b[c]
                }
                e[y][x] = sqrt(dr * dr + dg * dg + db * db) / k
            }
        }
        // Best connected line through all columns (dynamic programming), at most 3 rows of change per column
        val score = Array(H) { FloatArray(W) }
        val from = Array(H) { IntArray(W) }
        for (y in 0 until H) score[y][0] = e[y][0]
        for (x in 1 until W) for (y in 0 until H) {
            var best = Float.NEGATIVE_INFINITY
            var arg = y
            for (dy in -3..3) {
                val p = y + dy
                if (p !in 0 until H) continue
                val s = score[p][x - 1] - .02f * abs(dy)
                if (s > best) { best = s; arg = p }
            }
            score[y][x] = best + e[y][x]
            from[y][x] = arg
        }
        var y = (0 until H).maxBy { score[it][W - 1] }
        val line = IntArray(W)
        for (x in W - 1 downTo 0) { line[x] = y; y = from[y][x] }

        // Is it really a skyline? Strong enough, calm above, more structure below, in the middle band
        var strength = 0f; var skyTex = 0f; var landTex = 0f
        for (x in 0 until W) {
            strength += e[line[x]][x]
            skyTex += texture(r, g, b, x, 0, (line[x] - 2).coerceAtLeast(1))
            landTex += texture(r, g, b, x, (line[x] + 2).coerceAtMost(H - 1), H)
        }
        strength /= W; skyTex /= W; landTex /= W
        val middle = line.copyOfRange(W / 4, W * 3 / 4).sorted()
        val horizon = middle[middle.size / 4] / H.toFloat()
        if (strength < .35f || skyTex > .03f || landTex < skyTex * 1.4f || horizon !in .32f..0.70f) return null

        // Sharpen the line on the full photo: the strongest change within a few rows, every other column
        val fw = photo.width
        val fh = photo.height
        val step = 2
        val ys = FloatArray(fw / step + 1)
        val col = IntArray(fh)
        for (i in ys.indices) {
            val x = (i * step).coerceAtMost(fw - 1)
            val sx = (x * (W - 1) / (fw - 1).coerceAtLeast(1))
            val guess = (line[sx] + .5f) * fh / H
            val lo = (guess - fh / H * 1.5f).toInt().coerceIn(3, fh - 4)
            val hi = (guess + fh / H * 1.5f).toInt().coerceIn(3, fh - 4)
            photo.getPixels(col, 0, 1, x, 0, 1, fh)
            var bestY = guess
            var bestD = -1f
            for (yy in lo..hi) {
                val d = diff(col[yy - 3], col[yy + 2]) + diff(col[yy - 2], col[yy + 1])
                if (d > bestD) { bestD = d; bestY = yy.toFloat() }
            }
            ys[i] = bestY
        }
        // A light median keeps single stray columns out
        val smooth = FloatArray(ys.size) { i -> floatArrayOf(ys[(i - 1).coerceAtLeast(0)], ys[i], ys[(i + 1).coerceAtMost(ys.size - 1)]).sorted()[1] }

        val mask = Bitmap.createBitmap(fw, fh, Bitmap.Config.ARGB_8888)
        val c = Canvas(mask)
        c.drawColor(Color.BLACK)
        val path = Path().apply {
            moveTo(0f, fh.toFloat())
            for (i in smooth.indices) lineTo((i * step).toFloat().coerceAtMost(fw.toFloat()), smooth[i])
            lineTo(fw.toFloat(), smooth.last())
            lineTo(fw.toFloat(), fh.toFloat())
            close()
        }
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        return mask
    }

    /** Mean colour change from row to row in one column, between [y0] and [y1]. */
    private fun texture(r: FloatArray, g: FloatArray, b: FloatArray, x: Int, y0: Int, y1: Int): Float {
        if (y1 - y0 < 2) return 0f
        var t = 0f
        for (y in y0 until y1 - 1) {
            val a = y * W + x
            val c = a + W
            t += abs(r[a] - r[c]) + abs(g[a] - g[c]) + abs(b[a] - b[c])
        }
        return t / (y1 - y0 - 1)
    }

    private fun diff(p: Int, q: Int) =
        (abs(Color.red(p) - Color.red(q)) + abs(Color.green(p) - Color.green(q)) + abs(Color.blue(p) - Color.blue(q))) / 765f
}
