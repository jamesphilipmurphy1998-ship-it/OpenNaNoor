package com.example.opennanoor.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette

/**
 * Reshapes any app icon into an iOS-style rounded square, with no icon pack
 * involved. Two things make Android icons read as "Android" rather than "iOS":
 * circular masks, and inconsistent glyph sizes against transparent backgrounds.
 * Both are corrected here.
 *
 * Adaptive icons keep their own background layer, which is what they were
 * designed for. Legacy icons - usually a glyph on transparency - are placed on
 * a flat background sampled from the icon itself, so the result still looks
 * deliberate rather than pasted onto white.
 */
object SquircleIcons {

    /**
     * iOS uses a continuous-curvature superellipse rather than a rounded rect.
     * n = 5 is the usual approximation of Apple's shape.
     */
    private const val SUPERELLIPSE_N = 5.0
    private const val PATH_SEGMENTS = 180

    /** How much of the tile the glyph occupies, for legacy icons. */
    private const val GLYPH_SCALE = 0.62f

    fun apply(source: Drawable, sizePx: Int): Drawable {
        val output = createBitmap(sizePx, sizePx)
        val canvas = Canvas(output)
        val bounds = Rect(0, 0, sizePx, sizePx)

        val layer = when {
            source is AdaptiveIconDrawable -> renderAdaptive(source, sizePx)
            else -> renderLegacy(source, sizePx)
        }

        canvas.drawBitmap(layer, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG))

        // Clip everything outside the squircle.
        val clip = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        val maskBitmap = createBitmap(sizePx, sizePx)
        Canvas(maskBitmap).drawPath(
            squirclePath(sizePx.toFloat()),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        )
        canvas.drawBitmap(maskBitmap, 0f, 0f, clip)

        return android.graphics.drawable.BitmapDrawable(null, output)
    }

    /**
     * Adaptive icons already carry a full-bleed background and an inset
     * foreground. Drawing the drawable at full size and letting the squircle do
     * the clipping is exactly what the format expects.
     */
    private fun renderAdaptive(source: AdaptiveIconDrawable, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        // Adaptive icons reserve the outer ~18% for masking, so overdraw
        // slightly to fill the squircle's corners rather than leaving gaps.
        val bleed = (sizePx * 0.08f).toInt()
        source.setBounds(-bleed, -bleed, sizePx + bleed, sizePx + bleed)
        source.draw(canvas)
        return bitmap
    }

    /**
     * Legacy icons are drawn onto a solid tile. The tile colour is sampled from
     * the icon's own dominant colour and lightened, so a set of them still
     * looks like one family.
     */
    private fun renderLegacy(source: Drawable, sizePx: Int): Bitmap {
        val bitmap = createBitmap(sizePx, sizePx)
        val canvas = Canvas(bitmap)
        val glyph = source.toBitmap(sizePx, sizePx)

        canvas.drawColor(backgroundFor(glyph))

        val inset = ((1f - GLYPH_SCALE) * sizePx / 2f).toInt()
        canvas.drawBitmap(
            glyph,
            null,
            Rect(inset, inset, sizePx - inset, sizePx - inset),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        )
        return bitmap
    }

    private fun backgroundFor(glyph: Bitmap): Int {
        val dominant = runCatching {
            Palette.from(glyph).clearFilters().generate().getDominantColor(Color.WHITE)
        }.getOrDefault(Color.WHITE)

        // Push towards white so the glyph stays readable on top of it.
        return blend(dominant, Color.WHITE, 0.72f)
    }

    private fun blend(from: Int, to: Int, ratio: Float): Int {
        val inverse = 1f - ratio
        return Color.rgb(
            (Color.red(from) * inverse + Color.red(to) * ratio).toInt(),
            (Color.green(from) * inverse + Color.green(to) * ratio).toInt(),
            (Color.blue(from) * inverse + Color.blue(to) * ratio).toInt()
        )
    }

    /**
     * |x/a|^n + |y/a|^n = 1, sampled into a closed path.
     */
    private fun squirclePath(size: Float): Path {
        val radius = size / 2f
        val exponent = 2.0 / SUPERELLIPSE_N
        val path = Path()

        for (i in 0..PATH_SEGMENTS) {
            val theta = 2.0 * Math.PI * i / PATH_SEGMENTS
            val cos = Math.cos(theta)
            val sin = Math.sin(theta)
            val x = Math.signum(cos) * Math.pow(Math.abs(cos), exponent) * radius + radius
            val y = Math.signum(sin) * Math.pow(Math.abs(sin), exponent) * radius + radius
            if (i == 0) path.moveTo(x.toFloat(), y.toFloat())
            else path.lineTo(x.toFloat(), y.toFloat())
        }
        path.close()
        return path
    }
}
