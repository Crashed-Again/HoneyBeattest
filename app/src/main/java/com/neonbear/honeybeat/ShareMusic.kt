package com.neonbear.honeybeat

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import java.io.File

/** "Share to Instagram", like Spotify: a story-sized picture of the song that is playing, sent to Instagram. */
object ShareMusic {
    /**
     * Optional. Instagram's own Add-to-Story screen needs a Facebook App ID (free, from developers.facebook.com).
     * Paste it here to use that screen. Left empty, the picture goes through Instagram's normal share screen
     * (which also offers Story), and if Instagram is not installed the normal Android share sheet opens.
     */
    const val IG_APP_ID = ""
    private const val IG = "com.instagram.android"

    private const val W = 1080
    private const val H = 1920

    private val BEAR = listOf(".XX..XX.", "XXXXXXXX", "XXXXXXXX", "X.XXXX.X", "XXXXXXXX", ".XXXXXX.", "..XXXX..")

    /** Draws the card and saves it as a PNG in the app cache. Call it off the main thread. */
    fun cardUri(ctx: Context, cover: Bitmap?, title: String, artist: String): Uri =
        saveCard(ctx, renderCard(ctx, cover, title, artist))

    /** Writes the finished card to the app cache and returns the share link for it. Call it off the main thread. */
    fun saveCard(ctx: Context, bmp: Bitmap): Uri {
        val dir = File(ctx.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "honeybeat-share.png")
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(ctx, "${ctx.packageName}.share", file)
    }

    /** Draws the story card. The share screen shows this as a preview before anything is sent. Call it off the main thread. */
    fun renderCard(ctx: Context, cover: Bitmap?, title: String, artist: String): Bitmap {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)

        // background: the cover's average colour fading into the app's dark panel
        val tint = if (cover != null) {
            ColorUtils.blendARGB(Bitmap.createScaledBitmap(cover, 1, 1, true).getPixel(0, 0), Color.BLACK, 0.45f)
        } else 0xFF232323.toInt()
        val bg = Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, H.toFloat(), intArrayOf(tint, 0xFF232323.toInt(), Color.BLACK), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), bg)

        // cover, square with rounded corners
        val side = 840f
        val left = (W - side) / 2f
        val top = 380f
        val box = RectF(left, top, left + side, top + side)
        val round = Path().apply { addRoundRect(box, 36f, 36f, Path.Direction.CW) }
        c.save()
        c.clipPath(round)
        if (cover != null) {
            val s = minOf(cover.width, cover.height)
            val src = Rect((cover.width - s) / 2, (cover.height - s) / 2, (cover.width + s) / 2, (cover.height + s) / 2)
            c.drawBitmap(cover, src, box, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            c.drawRect(box, Paint().apply { color = Color.BLACK })
            drawBear(c, left + side / 2f, top + side / 2f, 34f, 0xFF444444.toInt())
        }
        c.restore()

        val bold = ResourcesCompat.getFont(ctx, R.font.outfit_bold) ?: Typeface.DEFAULT_BOLD
        val regular = ResourcesCompat.getFont(ctx, R.font.outfit_regular) ?: Typeface.DEFAULT
        val display = ResourcesCompat.getFont(ctx, R.font.tektur_medium) ?: Typeface.DEFAULT_BOLD

        fun line(text: String, size: Float, face: Typeface, color: Int, y: Float) {
            val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = size; typeface = face; this.color = color }
            val fit = TextUtils.ellipsize(text, p, side, TextUtils.TruncateAt.END).toString()
            c.drawText(fit, left, y, p)
        }
        line(title.ifBlank { "Unknown song" }, 64f, bold, Color.WHITE, top + side + 120f)
        line(artist, 44f, regular, 0xFFBDBDBD.toInt(), top + side + 190f)

        // footer: pixel bear and the app name
        drawBear(c, left + 44f, 1690f, 11f, Color.WHITE)
        val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 42f; typeface = display; color = Color.WHITE }
        c.drawText("HoneyBeat", left + 44f * 2 + 24f, 1704f, name)
        return bmp
    }

    /** Pixel bear centred on (cx, cy). */
    private fun drawBear(c: Canvas, cx: Float, cy: Float, cell: Float, color: Int) {
        val p = Paint().apply { this.color = color }
        val ox = cx - cell * 4f
        val oy = cy - cell * 3.5f
        BEAR.forEachIndexed { r, row ->
            row.forEachIndexed { col, ch ->
                if (ch == 'X') c.drawRect(ox + col * cell + cell * 0.1f, oy + r * cell + cell * 0.1f,
                    ox + (col + 1) * cell - cell * 0.1f, oy + (r + 1) * cell - cell * 0.1f, p)
            }
        }
    }

    /** Opens Instagram with the picture. Falls back to the system share sheet when Instagram is missing. */
    fun toInstagram(ctx: Context, uri: Uri, text: String) {
        if (IG_APP_ID.isNotEmpty()) {
            val story = Intent("com.instagram.share.ADD_TO_STORY").apply {
                putExtra("source_application", IG_APP_ID)
                setDataAndType(uri, "image/png")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                ctx.grantUriPermission(IG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                ctx.startActivity(story)
                return
            } catch (_: ActivityNotFoundException) {
            }
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            ctx.grantUriPermission(IG, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent(send).setPackage(IG).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            ctx.startActivity(Intent.createChooser(send, "Share song").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
