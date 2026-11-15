package de.glas.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.LayerDrawable

/**
 * A small, soft picture of the home screen's background, taken when another screen opens.
 * Screens over it (weather, settings) show it at once instead of black while their own
 * background is still loading, and the Liquid Glass settings use it as their backdrop.
 */
object Snapshot {
    @Volatile var bitmap: Bitmap? = null
        private set

    fun take(backdrop: Backdrop) {
        val w = backdrop.width / 8
        val h = backdrop.height / 8
        if (w <= 0 || h <= 0) return
        runCatching {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            c.scale(1f / 8, 1f / 8)
            backdrop.drawBackdrop(c)
            // An empty (still loading) background would only replace a good picture with black
            if (!isBlack(b)) bitmap = b
        }
    }

    private fun isBlack(b: Bitmap): Boolean {
        var x = 0
        while (x < b.width) {
            var y = 0
            while (y < b.height) {
                if (b.getPixel(x, y) and 0xFFFFFF > 0x101010) return false
                y += 4
            }
            x += 4
        }
        return true
    }

    /** The picture filling a view, optionally darkened by [veil]; null if there is none yet. */
    fun drawable(ctx: Context, veil: Int = 0): Drawable? {
        val b = bitmap ?: return null
        val pic = BitmapDrawable(ctx.resources, b).apply { isFilterBitmap = true }
        return if (veil == 0) pic else LayerDrawable(arrayOf(pic, ColorDrawable(veil)))
    }
}
