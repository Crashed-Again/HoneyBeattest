package com.neonbear.honeybeat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Size as GSize

object Cub {
    val Black = Color(0xFF000000)
    val Panel = Color(0xFF232323)
    val Card = Color(0xFF1B1B1B)
    val Hover = Color(0xFF2A2A2A)
    /** The app's blue. [Accent] is this, or the playing song's cover colour when that option is on. */
    val AccentDefault = Color(0xFF3B82F6)
    /** Read through a CompositionLocal, so every button and highlight redraws the moment the colour changes. */
    val Accent: Color @Composable get() = LocalAccent.current
    val Text = Color(0xFFF2F2F2)
    val Muted = Color(0xFF8C8C8C)
    val Button = Color(0xFF3A3A3A)
    val Dot = Color(0xFF5A5A5A)
}

val LocalAccent = compositionLocalOf { Color(0xFF3B82F6) }

@Composable
fun CubTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Cub.AccentDefault,
            background = Cub.Panel,
            surface = Cub.Card,
            onSurface = Cub.Text,
            onBackground = Cub.Text,
        ),
    ) {
        ProvideTextStyle(TextStyle(fontFamily = Body)) { content() }
    }
}

/** Tektur for titles, Outfit for everything else (both SIL Open Font License, see /licenses). */
val Display = FontFamily(
    Font(R.font.tektur_regular, FontWeight.Normal),
    Font(R.font.tektur_medium, FontWeight.Medium),
    Font(R.font.tektur_medium, FontWeight.SemiBold),
    Font(R.font.tektur_medium, FontWeight.Bold),
)
val Body = FontFamily(
    Font(R.font.outfit_regular, FontWeight.Normal),
    Font(R.font.outfit_regular, FontWeight.Medium),
    Font(R.font.outfit_bold, FontWeight.SemiBold),
    Font(R.font.outfit_bold, FontWeight.Bold),
)

/** The pixel bear used for the logo (same grid as the launcher icon). */
private val BEAR = listOf(".XX..XX.", "XXXXXXXX", "XXXXXXXX", "X.XXXX.X", "XXXXXXXX", ".XXXXXX.", "..XXXX..")

@Composable
fun BearMark(modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier) {
        val cell = minOf(size.width, size.height) / 8f
        val ox = (size.width - cell * 8f) / 2f
        val oy = (size.height - cell * 7f) / 2f
        BEAR.forEachIndexed { r, row ->
            row.forEachIndexed { c, ch ->
                if (ch == 'X') drawRect(
                    color,
                    Offset(ox + c * cell + cell * 0.1f, oy + r * cell + cell * 0.1f),
                    GSize(cell * 0.8f, cell * 0.8f),
                )
            }
        }
    }
}

@Composable
fun BearLogo(box: Dp = 44.dp) {
    Box(
        Modifier.size(box).clip(RoundedCornerShape(10.dp)).background(Cub.Black)
            .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) { BearMark(Modifier.size(box * 0.62f)) }
}

private val LETTERS = mapOf(
    'N' to listOf("10001", "11001", "10101", "10101", "10011", "10001", "10001"),
    'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
    'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
    'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
    'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
    'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
)

/** Dot-matrix text, like the NEONBEAR mark at the bottom of Cub. */
@Composable
fun DotText(text: String, step: Dp = 3.5.dp, color: Color = Cub.Dot) {
    val cols = text.length * 6 - 1
    Canvas(Modifier.size(step * cols, step * 7)) {
        val s = step.toPx()
        text.forEachIndexed { i, ch ->
            val g = LETTERS[ch] ?: return@forEachIndexed
            g.forEachIndexed { r, row ->
                row.forEachIndexed { c, bit ->
                    if (bit == '1') drawCircle(color, s * 0.33f, Offset((i * 6 + c + 0.5f) * s, (r + 0.5f) * s))
                }
            }
        }
    }
}

enum class G { Play, Pause, Next, Prev }

@Composable
fun Glyph(g: G, color: Color, box: Dp = 24.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        fun tri(a: Float, b: Float, c: Float) = Path().apply {
            moveTo(w * a, h * 0.2f); lineTo(w * b, h * 0.5f); lineTo(w * a, h * 0.8f); close()
        }
        when (g) {
            G.Play -> drawPath(tri(0.3f, 0.82f, 0f), color)
            G.Pause -> {
                drawRect(color, Offset(w * 0.27f, h * 0.2f), GSize(w * 0.16f, h * 0.6f))
                drawRect(color, Offset(w * 0.57f, h * 0.2f), GSize(w * 0.16f, h * 0.6f))
            }
            G.Next -> {
                drawPath(tri(0.16f, 0.68f, 0f), color)
                drawRect(color, Offset(w * 0.72f, h * 0.2f), GSize(w * 0.12f, h * 0.6f))
            }
            G.Prev -> {
                drawRect(color, Offset(w * 0.16f, h * 0.2f), GSize(w * 0.12f, h * 0.6f))
                drawPath(Path().apply {
                    moveTo(w * 0.84f, h * 0.2f); lineTo(w * 0.32f, h * 0.5f); lineTo(w * 0.84f, h * 0.8f); close()
                }, color)
            }
        }
    }
}

@Composable
fun CubSwitch(on: Boolean) {
    val x by animateDpAsState(if (on) 24.dp else 0.dp, tween(140), label = "knob")
    val c by animateColorAsState(if (on) Cub.Accent else Cub.Muted, tween(140), label = "knobColor")
    Box(
        Modifier.size(46.dp, 24.dp).border(1.dp, c).padding(3.dp),
    ) {
        Box(Modifier.offset(x = x).size(16.dp).background(c))
    }
}

@Composable
fun ToggleCard(title: String, sub: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Card)
            .clickable { onChange(!on) }.padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cub.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        CubSwitch(on)
    }
}

@Composable
fun CubButton(text: String, primary: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.bounceClick(onClick = onClick).clip(RoundedCornerShape(4.dp))
            .background(if (primary) Cub.Accent else Cub.Button)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
fun PageTitle(title: String, sub: String) {
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        Text(title, color = Cub.Text, fontSize = 30.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text(sub, color = Cub.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

val LocalShowArt = compositionLocalOf { true }

/** Click with a small press-in animation. Put it first in the modifier chain so the background scales too. */
fun Modifier.bounceClick(scale: Float = 0.94f, onClick: () -> Unit): Modifier = composed {
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) scale else 1f, tween(90), label = "press")
    Modifier
        .graphicsLayer { scaleX = s; scaleY = s }
        .clickable(interactionSource = src, indication = null, onClick = onClick)
}

/** Album cover (embedded picture) with a bear placeholder. Size comes from the modifier. */
@Composable
fun Cover(id: Long, px: Int, modifier: Modifier = Modifier, corner: Dp = 4.dp) {
    val ctx = LocalContext.current
    val show = LocalShowArt.current
    val bmp by produceState<Bitmap?>(if (show) Covers.peek(id, px) else null, id, px, show) {
        value = if (show) Covers.load(ctx, id, px) else null
    }
    Box(modifier.clip(RoundedCornerShape(corner)).background(Cub.Black), contentAlignment = Alignment.Center) {
        Crossfade(bmp, animationSpec = tween(180), label = "cover") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BearMark(Modifier.fillMaxSize(0.38f), Color(0xFF444444))
            }
        }
    }
}

/** Three little equaliser bars, shown next to the song that is playing. */
@Composable
fun EqBars(active: Boolean, color: Color = Cub.Accent) {
    val t = rememberInfiniteTransition(label = "eq")
    val a by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse), label = "b")
    val c by t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(340, easing = LinearEasing), RepeatMode.Reverse), label = "c")
    Canvas(Modifier.size(16.dp, 14.dp)) {
        val bw = size.width / 5f
        listOf(a, b, c).forEachIndexed { i, v ->
            val h = size.height * (if (active) v else 0.3f)
            drawRect(color, Offset(i * 2 * bw, size.height - h), GSize(bw, h))
        }
    }
}

@Composable
fun TBtn(g: G, box: Dp, glyph: Dp, bg: Color, tint: Color, onClick: () -> Unit) {
    Box(
        Modifier.bounceClick(onClick = onClick).size(box).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Glyph(g, tint, glyph) }
}

@Composable
fun PlayPauseBtn(playing: Boolean, box: Dp, glyph: Dp, onClick: () -> Unit) {
    Box(
        Modifier.bounceClick(onClick = onClick).size(box).clip(RoundedCornerShape(4.dp)).background(Cub.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(playing, animationSpec = tween(120), label = "pp") { p ->
            Glyph(if (p) G.Pause else G.Play, Color.White, glyph)
        }
    }
}

/** Playlist cover: the one the user picked, or the first song's cover. */
@Composable
fun FolderCover(key: String, fallbackSongId: Long, modifier: Modifier = Modifier, px: Int = 160) {
    val ctx = LocalContext.current
    val ver = PlaylistCovers.version
    val custom by produceState<Bitmap?>(null, key, ver) {
        value = withContext(Dispatchers.IO) { PlaylistCovers.load(ctx, key) }
    }
    val c = custom
    if (c != null) {
        Box(modifier.clip(RoundedCornerShape(4.dp)).background(Cub.Black)) {
            Image(c.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
    } else {
        Cover(fallbackSongId, px, modifier)
    }
}

/** Thumbnail from the web (used for search results). */
@Composable
fun NetCover(url: String, modifier: Modifier = Modifier) {
    val bmp by produceState<Bitmap?>(NetImages.peek(url), url) { value = NetImages.load(url) }
    Box(modifier.clip(RoundedCornerShape(4.dp)).background(Cub.Black), contentAlignment = Alignment.Center) {
        Crossfade(bmp, animationSpec = tween(180), label = "net") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                BearMark(Modifier.fillMaxSize(0.38f), Color(0xFF444444))
            }
        }
    }
}

enum class IconKind { Gear, Download, Down, Search, Share }

/** Little line icons drawn by hand so the app needs no icon library. */
@Composable
fun Ico(kind: IconKind, color: Color, box: Dp = 22.dp) {
    Canvas(Modifier.size(box)) {
        val w = size.width
        val h = size.height
        val sw = w * 0.09f
        when (kind) {
            IconKind.Gear -> {
                drawCircle(color, radius = w * 0.3f, style = Stroke(sw))
                drawCircle(color, radius = w * 0.11f, style = Stroke(sw))
                repeat(8) { k ->
                    rotate(k * 45f) {
                        drawRect(color, Offset(w * 0.5f - sw, h * 0.5f - w * 0.45f), GSize(sw * 2f, w * 0.15f))
                    }
                }
            }
            IconKind.Download -> {
                drawLine(color, Offset(w * 0.5f, h * 0.14f), Offset(w * 0.5f, h * 0.62f), sw * 1.2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.3f, h * 0.44f), Offset(w * 0.5f, h * 0.64f), sw * 1.2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.7f, h * 0.44f), Offset(w * 0.5f, h * 0.64f), sw * 1.2f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.2f, h * 0.84f), Offset(w * 0.8f, h * 0.84f), sw * 1.2f, StrokeCap.Round)
            }
            IconKind.Down -> {
                drawLine(color, Offset(w * 0.2f, h * 0.36f), Offset(w * 0.5f, h * 0.66f), sw * 1.3f, StrokeCap.Round)
                drawLine(color, Offset(w * 0.8f, h * 0.36f), Offset(w * 0.5f, h * 0.66f), sw * 1.3f, StrokeCap.Round)
            }
            IconKind.Share -> {
                val a = Offset(w * 0.76f, h * 0.2f)
                val b = Offset(w * 0.24f, h * 0.5f)
                val d = Offset(w * 0.76f, h * 0.8f)
                drawLine(color, b, a, sw, StrokeCap.Round)
                drawLine(color, b, d, sw, StrokeCap.Round)
                drawCircle(color, w * 0.12f, a)
                drawCircle(color, w * 0.12f, b)
                drawCircle(color, w * 0.12f, d)
            }
            IconKind.Search -> {
                drawCircle(color, radius = w * 0.26f, center = Offset(w * 0.42f, h * 0.42f), style = Stroke(sw * 1.2f))
                drawLine(color, Offset(w * 0.62f, h * 0.62f), Offset(w * 0.86f, h * 0.86f), sw * 1.3f, StrokeCap.Round)
            }
        }
    }
}

@Composable
fun IconBtn(kind: IconKind, box: Dp = 44.dp, bg: Color = Cub.Button, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.bounceClick(onClick = onClick).size(box).clip(RoundedCornerShape(4.dp)).background(bg),
        contentAlignment = Alignment.Center,
    ) { Ico(kind, tint, box * 0.5f) }
}

/** A popup that grows in and shrinks out. [content] gets a close() that plays the exit animation. */
@Composable
fun AnimatedPopup(onDismiss: () -> Unit, content: @Composable (close: () -> Unit) -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { shown = true }
    val close: () -> Unit = {
        scope.launch {
            shown = false
            delay(170)
            onDismiss()
        }
    }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        AnimatedVisibility(
            visible = shown,
            enter = fadeIn(tween(160)) + scaleIn(tween(220), initialScale = 0.85f),
            exit = fadeOut(tween(140)) + scaleOut(tween(160), targetScale = 0.9f),
        ) {
            Column(
                Modifier.padding(24.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Cub.Card).padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { content(close) }
        }
    }
}

/** Fake level meter: pure animation, it does not read the audio. */
@Composable
fun Visualizer(active: Boolean, color: Color, bars: Int = 28) {
    val t = rememberInfiniteTransition(label = "viz")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "phase")
    val level by animateFloatAsState(if (active) 1f else 0.06f, tween(450), label = "level")
    Canvas(Modifier.fillMaxWidth().height(30.dp)) {
        val gap = 4.dp.toPx()
        val bw = (size.width - gap * (bars - 1)) / bars
        for (i in 0 until bars) {
            val a = (sin(phase * 2 * PI + i * 0.55) + 0.5 * sin(phase * 4 * PI + i * 1.3)) / 1.5
            val v = ((0.25f + 0.75f * (a.toFloat() * 0.5f + 0.5f)) * level).coerceIn(0f, 1f)
            val bh = (size.height * v).coerceAtLeast(3.dp.toPx())
            drawRect(color.copy(alpha = 0.35f + 0.65f * v), Offset(i * (bw + gap), size.height - bh), GSize(bw, bh))
        }
    }
}
