package com.neonbear.honeybeat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AColor
import androidx.compose.ui.graphics.Color
import androidx.core.graphics.ColorUtils

/** Picks an accent colour from a song's cover: the most colourful part of the picture, tuned to stay readable on the dark UI. */
object CoverAccent {
    suspend fun of(ctx: Context, songId: Long): Color? {
        val big = Covers.load(ctx, songId, 64) ?: return null
        return from(big)
    }

    fun from(src: Bitmap): Color? {
        val b = Bitmap.createScaledBitmap(src, 24, 24, true)
        val hsv = FloatArray(3)
        var sumW = 0.0
        var sumX = 0.0   // hue is an angle, so average it as a vector
        var sumY = 0.0
        var sumS = 0.0
        var sumV = 0.0
        for (y in 0 until b.height) for (x in 0 until b.width) {
            AColor.colorToHSV(b.getPixel(x, y), hsv)
            val w = (hsv[1] * hsv[1] * hsv[2]).toDouble() // colourful and bright pixels count most
            if (w <= 0.0) continue
            val rad = Math.toRadians(hsv[0].toDouble())
            sumX += Math.cos(rad) * w
            sumY += Math.sin(rad) * w
            sumS += hsv[1] * w
            sumV += hsv[2] * w
            sumW += w
        }
        if (sumW < 1.0) return null // black and white cover: keep the normal blue
        val hue = ((Math.toDegrees(Math.atan2(sumY, sumX)) + 360.0) % 360.0).toFloat()
        val sat = (sumS / sumW).toFloat().coerceIn(0.5f, 0.9f)
        var v = (sumV / sumW).toFloat().coerceIn(0.62f, 0.9f)
        var c = AColor.HSVToColor(floatArrayOf(hue, sat, v))
        // white text sits on top of the accent in buttons, so very light colours (yellow) get darker
        while (ColorUtils.calculateLuminance(c) > 0.42 && v > 0.45f) {
            v -= 0.04f
            c = AColor.HSVToColor(floatArrayOf(hue, sat, v))
        }
        return Color(c)
    }
}
