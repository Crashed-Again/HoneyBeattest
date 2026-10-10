package com.neonbear.honeybeat

import android.content.ClipboardManager
import android.content.Context
import android.net.ConnectivityManager
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun isMetered(ctx: Context): Boolean =
    ctx.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true

@Composable
fun InputBox(value: String, hint: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    Box(modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(Cub.Hover).padding(horizontal = 12.dp, vertical = 13.dp)) {
        if (value.isEmpty()) Text(hint, color = Cub.Muted, fontSize = 14.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = Cub.Text, fontSize = 14.sp, fontFamily = Body),
            cursorBrush = SolidColor(Cub.Accent), modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The YouTube sign-in page. Once the session cookie shows up we save it for yt-dlp and call [onSignedIn]. */
@Composable
fun YouTubeLoginView(modifier: Modifier, onSignedIn: () -> Unit) {
    var fired = false
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = YtAuth.UA
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        val host = try { android.net.Uri.parse(url ?: "").host ?: "" } catch (_: Exception) { "" }
                        val onYt = host == "youtube.com" || host.endsWith(".youtube.com")
                        if (!fired && onYt && YtAuth.saveFromWebView(ctx)) {
                            fired = true
                            onSignedIn()
                        }
                    }
                }
                loadUrl(YtAuth.LOGIN_URL)
            }
        },
        onRelease = { it.destroy() },
    )
}

/** Sign-in popup with the YouTube page inside. */
@Composable
fun LoginPopup(onClose: () -> Unit) {
    AnimatedPopup(onDismiss = onClose) { close ->
        Text("Sign in to YouTube", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text(
            "Used for private playlists, search and playlist links. Yt-dlp can get an account rate-limited, so a spare Google account is safer.",
            color = Cub.Muted, fontSize = 12.sp,
        )
        Box(Modifier.fillMaxWidth().height(430.dp).clip(RoundedCornerShape(6.dp)).background(Color.White)) {
            val ctx = LocalContext.current
            YouTubeLoginView(Modifier.fillMaxWidth().height(430.dp)) {
                CaveState.signedIn = true
                YtAuth.refreshName(ctx)
                close()
            }
        }
        CubButton("Cancel") { close() }
    }
}

/** Shown once per launch on the Search tab while signed out. */
@Composable
fun LoginPrompt(st: Settings, onSignIn: () -> Unit, onClose: () -> Unit) {
    AnimatedPopup(onDismiss = onClose) { close ->
        Text("Sign in to YouTube?", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text(
            "YouTube often refuses downloads from signed-out apps (\"confirm you're not a bot\"). Signing in fixes that, unlocks private playlists and lets you import your music playlists. You can also do it later in Options.",
            color = Cub.Muted, fontSize = 13.sp,
        )
        ToggleCard("Don't show again", "", st.noPrompt.value) { st.noPrompt.set(it) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CubButton("Sign in", primary = true) { close(); onSignIn() }
            CubButton("Not now") { close() }
        }
    }
}

/** Lists the signed-in user's playlists; Import downloads one, saves its cover and keeps it updated. */
@Composable
fun ImportPopup(st: Settings, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var result by remember { mutableStateOf<MyPlaylists?>(null) }
    var started by remember { mutableStateOf<List<String>>(emptyList()) }
    var msg by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { result = withContext(Dispatchers.IO) { CaveEngine.myPlaylists(ctx) } }
    AnimatedPopup(onDismiss = onClose) { close ->
        Text("Import from YouTube Music", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text(
            "Your music playlists. Downloads the playlist with its cover and keeps it updated in the background.",
            color = Cub.Muted, fontSize = 12.sp,
        )
        val r = result
        if (r == null) {
            Text("Loading your playlists...", color = Cub.Muted, fontSize = 13.sp)
        } else {
            if (r.error != null) Text("Couldn't list your playlists: ${r.error}", color = Color(0xFFE5645F), fontSize = 12.sp)
            Column(
                Modifier.fillMaxWidth().heightIn(max = 340.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                r.items.forEach { pl ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Hover).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(pl.title, color = Cub.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        val done = pl.url in started
                        CubButton(if (done) "Started" else "Import", primary = !done) {
                            if (!done) {
                                st.watch.set(true)
                                if (CaveService.start(ctx, pl.url, true)) {
                                    started = started + pl.url
                                    msg = ""
                                    WatchService.sync(ctx)
                                } else msg = "Another download is running. Try again in a moment."
                            }
                        }
                    }
                }
            }
            if (msg.isNotEmpty()) Text(msg, color = Cub.Muted, fontSize = 12.sp)
        }
        CubButton("Close") { close() }
    }
}

@Composable
fun SearchPage(st: Settings, onSignIn: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf("") }
    var showPlaylist by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<(() -> Unit)?>(null) }
    var showPrompt by remember { mutableStateOf(false) }

    var q by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchMsg by remember { mutableStateOf("") }
    val queued = remember { mutableStateListOf<String>() }

    LaunchedEffect(Unit) {
        if (!CaveState.signedIn && !st.noPrompt.value && !CaveState.promptShown) {
            CaveState.promptShown = true
            showPrompt = true
        }
    }

    fun guarded(action: () -> Unit) {
        if (st.ask.value && isMetered(ctx)) confirm = action else action()
    }
    fun clone(url: String) {
        val u = normalizeLink(url)
        if (u.isEmpty()) { searchMsg = "Paste a playlist link first."; return }
        if (!looksLikeLink(u)) { searchMsg = "That doesn't look like a playlist link."; return }
        if (CaveState.running) { searchMsg = "Another download is running. Wait for it or press Stop."; return }
        searchMsg = ""
        guarded {
            if (!CaveService.start(ctx, u, false)) searchMsg = "Another download is running. Try again in a moment."
        }
    }
    fun search() {
        val query = q.trim()
        if (query.isEmpty() || searching) return
        // a pasted playlist link in the search box clones it instead of searching for the link text
        if (looksLikeLink(query)) { clone(query); q = ""; return }
        searching = true
        searchMsg = "Searching..."
        scope.launch {
            try {
                val r = withContext(Dispatchers.IO) { CaveEngine.search(ctx, query) }
                hits = r
                searchMsg = if (r.isEmpty()) "No results." else ""
            } catch (e: Exception) {
                searchMsg = errText(e)
            }
            searching = false
        }
    }

    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        PageTitle("Search", "Find a song and press Get. Files go to Music/HoneyBeat.")

        // search music + small playlist download button
        Row(
            Modifier.padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            InputBox(q, "Search music or paste a playlist link", Modifier.weight(1f)) { q = it }
            IconBtn(IconKind.Search, 46.dp, Cub.Accent) { search() }
            IconBtn(IconKind.Download, 46.dp) { showPlaylist = true }
        }
        if (searchMsg.isNotEmpty()) {
            Text(searchMsg, color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        }

        Column(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth().animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            hits.forEach { h ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Card).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    NetCover("https://i.ytimg.com/vi/${h.id}/mqdefault.jpg", Modifier.size(52.dp))
                    Column(Modifier.weight(1f)) {
                        Text(h.title, color = Cub.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            listOf(h.by, if (h.seconds > 0) fmt(h.seconds * 1000L) else "").filter { it.isNotEmpty() }.joinToString("  -  "),
                            color = Cub.Muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val isQueued = h.id in queued
                    CubButton(if (isQueued) "Queued" else "Get", primary = !isQueued) {
                        if (!isQueued) guarded {
                            queued += h.id
                            CaveService.queueTrack(ctx, h.id, h.title)
                        }
                    }
                }
            }
        }

        // progress
        val frac by animateFloatAsState(
            if (CaveState.total > 0) CaveState.done.toFloat() / CaveState.total else 0f, tween(250), label = "prog"
        )
        Column(
            Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                .background(Cub.Card).padding(14.dp).animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(CaveState.status, color = Cub.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            if (CaveState.current.isNotEmpty()) {
                Text(CaveState.current, color = Cub.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (CaveState.running || CaveState.total > 0) {
                Box(Modifier.fillMaxWidth().height(6.dp).background(Cub.Button)) {
                    Box(Modifier.fillMaxWidth(frac).height(6.dp).background(Cub.Accent))
                }
                if (CaveState.total > 0) {
                    Text("${CaveState.done} / ${CaveState.total}", color = Cub.Muted, fontSize = 12.sp)
                }
            }
            if (CaveState.lastError.isNotEmpty()) {
                Text("Last error: ${CaveState.lastError}", color = Color(0xFFE5645F), fontSize = 12.sp)
            }
            if (CaveState.running) CubButton("Stop") { CaveJob.cancel() }
        }

        if (CaveState.log.isNotEmpty()) {
            Column(
                Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Cub.Card).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text("Log", color = Cub.Muted, fontSize = 12.sp)
                CaveState.log.takeLast(14).forEach {
                    Text(it, color = Cub.Text, fontSize = 11.sp, fontFamily = FontFamily.Monospace,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            "Only download music you have the right to copy.", color = Cub.Muted, fontSize = 11.sp,
            modifier = Modifier.padding(16.dp),
        )
    }

    if (showPlaylist) {
        AnimatedPopup(onDismiss = { showPlaylist = false }) { close ->
            Text("Download a playlist", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
            Text("Paste a YouTube Music, Spotify or Apple Music playlist link.", color = Cub.Muted, fontSize = 13.sp)
            InputBox(link, "Playlist link") { link = it }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CubButton("Download", primary = true) { clone(link); close() }
                CubButton("Paste") {
                    val cm = ctx.getSystemService(ClipboardManager::class.java)
                    val t = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    if (!t.isNullOrBlank()) link = t.trim()
                }
            }
            if (CaveState.signedIn) {
                CubButton("Import my music playlists") { showImport = true; close() }
            }
            CubButton("Cancel") { close() }
        }
    }
    if (showImport) ImportPopup(st) { showImport = false }
    if (showPrompt) LoginPrompt(st, onSignIn = onSignIn) { showPrompt = false }

    confirm?.let { action ->
        AnimatedPopup(onDismiss = { confirm = null }) { close ->
            Text("Use mobile data?", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
            Text("You're on a metered connection. Downloads can use a lot of data.", color = Cub.Muted, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CubButton("Download", primary = true) { close(); action() }
                CubButton("Cancel") { close() }
            }
        }
    }
}
