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

    /** True while the home screen is still visible behind another window (e.g. the control centre). */
    @Volatile var homeBehind = false

    /** Set just before the control centre opens over the home screen: its video keeps running. */
    @Volatile var keepPlaying = false

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

/**
 * Liquid Glass for windows (menus, control centre) on TVs that don't blur behind windows:
 * the soft picture of the home screen's background, cut to where the window lies, with a light
 * veil, a sheen at the top and a bright rim. Without a picture it falls back to dark glass.
 */
class GlassDrawable(private val ctx: Context, private val radius: Float, private val usePicture: Boolean) : android.graphics.drawable.Drawable() {
    /** Where the window lies on the screen, kept up to date by [attach]. */
    private val screen = android.graphics.Rect()
    private val path = android.graphics.Path()
    private val rect = android.graphics.RectF()
    private val src = android.graphics.Rect()
    private val pic = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val veil = android.graphics.Paint()
    private val sheen = android.graphics.Paint()
    private val rim = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE }
    private val density = ctx.resources.displayMetrics.density
    private val frost = Prefs(ctx).windowFrost.coerceIn(0, 3)
    /** The picture made softer still for milkier glass. */
    private var soft: Bitmap? = null
    private var softOf: Bitmap? = null

    private fun picture(shot: Bitmap): Bitmap {
        if (frost <= 1) return shot
        if (softOf !== shot) {
            val f = if (frost == 2) 2 else 4
            val small = Bitmap.createScaledBitmap(shot, (shot.width / f).coerceAtLeast(1), (shot.height / f).coerceAtLeast(1), true)
            soft = Bitmap.createScaledBitmap(small, shot.width, shot.height, true)
            softOf = shot
        }
        return soft ?: shot
    }

    override fun draw(c: Canvas) {
        val b = bounds
        rect.set(b)
        path.reset()
        path.addRoundRect(rect, radius, radius, android.graphics.Path.Direction.CW)
        c.save()
        c.clipPath(path)
        val shot = if (usePicture) Snapshot.bitmap else null
        if (shot != null && !screen.isEmpty) {
            val dm = ctx.resources.displayMetrics
            val sx = shot.width / dm.widthPixels.toFloat()
            val sy = shot.height / dm.heightPixels.toFloat()
            src.set((screen.left * sx).toInt(), (screen.top * sy).toInt(), (screen.right * sx).toInt(), (screen.bottom * sy).toInt())
            c.drawBitmap(picture(shot), src, rect, pic)
            // A grey veil keeps white writing readable on any picture; a milky white layer, as strong
            // as set, frosts it; the sheen makes it glassy
            veil.color = 0x40181C24
            c.drawRect(rect, veil)
            veil.color = (intArrayOf(0x00, 0x1A, 0x33, 0x4D)[frost] shl 24) or 0xD8DEE8
            c.drawRect(rect, veil)
            sheen.shader = android.graphics.LinearGradient(0f, rect.top, 0f, rect.bottom,
                intArrayOf(0x40FFFFFF, 0x14FFFFFF, 0x08FFFFFF), floatArrayOf(0f, .45f, 1f), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(rect, sheen)
        } else {
            veil.color = 0xE6141820.toInt()
            c.drawRect(rect, veil)
        }
        c.restore()
        rim.strokeWidth = density * 1.5f
        rim.shader = android.graphics.LinearGradient(rect.left, rect.top, rect.right, rect.bottom,
            intArrayOf(0xCCFFFFFF.toInt(), 0x33FFFFFF, 0x80FFFFFF.toInt()), floatArrayOf(0f, .55f, 1f), android.graphics.Shader.TileMode.CLAMP)
        val inset = rim.strokeWidth / 2
        rect.inset(inset, inset)
        c.drawRoundRect(rect, radius - inset, radius - inset, rim)
    }

    /** Follows the window's position, so the right part of the picture shows behind it. */
    fun attach(window: android.view.Window) {
        val decor = window.decorView
        val loc = IntArray(2)
        decor.viewTreeObserver.addOnPreDrawListener {
            decor.getLocationOnScreen(loc)
            val r = android.graphics.Rect(loc[0], loc[1], loc[0] + decor.width, loc[1] + decor.height)
            if (r != screen) { screen.set(r); invalidateSelf() }
            true
        }
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
    @Deprecated("Deprecated in Android") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
}
