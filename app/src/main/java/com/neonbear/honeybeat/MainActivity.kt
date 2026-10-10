package com.neonbear.honeybeat

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent { CubTheme { CubApp() } }
    }
}

/** Playing is not a tab: the mini player opens it as a full-screen sheet. */
enum class Page(val label: String) { Library("Library"), Search("Search"), Options("Options") }

/** Thin wrapper around a MediaController that talks to PlaybackService. */
class Remote(private val ctx: Context) {
    var player by mutableStateOf<Player?>(null)
    var item by mutableStateOf<MediaItem?>(null)
    var playing by mutableStateOf(false)
    private var future: ListenableFuture<MediaController>? = null

    fun connect() {
        val token = SessionToken(ctx, ComponentName(ctx, PlaybackService::class.java))
        val f = MediaController.Builder(ctx, token).buildAsync()
        future = f
        f.addListener({
            try {
                val c = f.get()
                player = c
                item = c.currentMediaItem
                playing = c.isPlaying
                c.addListener(object : Player.Listener {
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { item = mediaItem }
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                })
            } catch (_: Exception) {
            }
        }, ContextCompat.getMainExecutor(ctx))
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        player = null
    }

    fun play(list: List<Song>, index: Int) {
        val p = player ?: return
        if (list.isEmpty()) return
        p.setMediaItems(list.map { it.toItem() }, index, 0L)
        p.prepare()
        p.play()
    }

    fun toggle() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
    }

    fun prev() { player?.seekToPrevious() }
    fun next() { player?.seekToNext() }
}

@Composable
fun CubApp() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE) }
    val st = remember { Settings(prefs) }

    var page by remember { mutableStateOf(Page.Library) }
    var songs by remember { mutableStateOf<List<Song>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var openKey by remember { mutableStateOf<String?>(null) }
    var showPlayer by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }

    val audioPerm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
    else Manifest.permission.READ_EXTERNAL_STORAGE
    fun granted() = ContextCompat.checkSelfPermission(ctx, audioPerm) == PackageManager.PERMISSION_GRANTED
    var hasAccess by remember { mutableStateOf(granted()) }

    fun rescan() {
        scope.launch {
            if (granted()) songs = withContext(Dispatchers.IO) { loadSongs(ctx) }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasAccess = granted()
        rescan()
    }
    fun askPermissions() {
        val wanted = if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        launcher.launch(wanted)
    }
    LaunchedEffect(Unit) {
        CaveState.recents = CaveStore.load(prefs)
        CaveState.signedIn = YtAuth.signedIn(ctx)
        CaveState.ytName = YtAuth.savedName(ctx)
        if (CaveState.signedIn && CaveState.ytName.isBlank()) YtAuth.refreshName(ctx)
        WatchService.sync(ctx)
        if (hasAccess) rescan() else askPermissions()
    }
    // after a reinstall: playlists come back from the link files kept in Music/HoneyBeat, so they can check for updates again
    LaunchedEffect(hasAccess) {
        if (hasAccess) {
            val r = withContext(Dispatchers.IO) { HoneyFiles.restoreInto(ctx) }
            if (r.added > 0) {
                if (r.auto) st.watch.set(true)
                WatchService.sync(ctx)
            }
        }
    }
    LaunchedEffect(CaveState.finished) { if (CaveState.finished > 0) rescan() }
    LaunchedEffect(st.watch.value) { WatchService.sync(ctx) }

    val remote = remember { Remote(ctx) }
    DisposableEffect(Unit) {
        remote.connect()
        onDispose { remote.release() }
    }

    // accent colour: the app's blue, or the colour of the cover that is playing (can be switched off in Options)
    val playingId = remote.item?.mediaId?.toLongOrNull()
    val accentTarget by produceState(Cub.AccentDefault, playingId, st.coverAccent.value) {
        value = if (st.coverAccent.value && playingId != null) CoverAccent.of(ctx, playingId) ?: Cub.AccentDefault else Cub.AccentDefault
    }
    val accentAnim by animateColorAsState(accentTarget, tween(450), label = "accent")

    val player = remote.player
    LaunchedEffect(player, st.shuffle.value, st.repeat.value) {
        player?.let {
            it.shuffleModeEnabled = st.shuffle.value
            it.repeatMode = if (st.repeat.value) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
        }
    }

    BackHandler(enabled = page == Page.Library && openKey != null) { openKey = null; query = "" }
    BackHandler(enabled = showPlayer) { showPlayer = false }

    val folders = remember(songs) { buildFolders(songs) }

    CompositionLocalProvider(LocalShowArt provides st.art.value, LocalAccent provides accentAnim) {
        Box(Modifier.fillMaxSize().background(Cub.Panel)) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Box(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = page,
                        transitionSpec = {
                            (fadeIn(tween(200)) + slideInVertically(tween(200)) { it / 24 }) togetherWith fadeOut(tween(120))
                        },
                        label = "page",
                    ) { p ->
                        when (p) {
                            Page.Library -> LibraryPage(
                                folders = folders, hasAccess = hasAccess, onAllow = { askPermissions() },
                                openKey = openKey,
                                onOpen = { openKey = it; query = "" },
                                query = query, onQuery = { query = it },
                                currentId = remote.item?.mediaId, playing = remote.playing,
                                onPlay = { list, i -> remote.play(list, i) },
                                st = st, onChanged = { rescan() },
                            )
                            Page.Search -> SearchPage(st = st, onSignIn = { showLogin = true })
                            Page.Options -> OptionsPage(
                                st = st, onRescan = { rescan() },
                                songCount = songs.size, listCount = folders.size - 1,
                                onLogin = { showLogin = true },
                            )
                        }
                    }
                }
                AnimatedVisibility(
                    visible = remote.item != null && !showPlayer,
                    enter = slideInVertically(tween(220)) { it } + fadeIn(tween(220)),
                    exit = slideOutVertically(tween(160)) { it } + fadeOut(tween(160)),
                ) { MiniPlayer(remote) { showPlayer = true } }
                NavBar(page) { page = it }
            }

            AnimatedVisibility(
                visible = showPlayer,
                enter = slideInVertically(tween(320)) { it } + fadeIn(tween(200)),
                exit = slideOutVertically(tween(260)) { it } + fadeOut(tween(200)),
            ) {
                PlayingScreen(
                    remote = remote, shuffle = st.shuffle.value, repeatAll = st.repeat.value,
                    onShuffle = { st.shuffle.set(!st.shuffle.value) },
                    onRepeat = { st.repeat.set(!st.repeat.value) },
                    onClose = { showPlayer = false },
                )
            }
        }
        if (showLogin) LoginPopup { showLogin = false }
    }
}

@Composable
fun NavBar(current: Page, onPick: (Page) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Cub.Black).navigationBarsPadding().padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Page.values().forEach { p ->
            val sel = p == current
            val bg by animateColorAsState(if (sel) Color(0xFF222222) else Color.Transparent, tween(150), label = "navBg")
            val fg by animateColorAsState(if (sel) Cub.Text else Cub.Muted, tween(150), label = "navFg")
            val bar by animateDpAsState(if (sel) 18.dp else 0.dp, tween(180), label = "navBar")
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(4.dp)).background(bg)
                    .clickable { onPick(p) }.padding(vertical = 13.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.align(Alignment.CenterStart).width(3.dp).height(bar).background(Cub.Accent))
                Text(p.label, color = fg, fontSize = 13.sp, fontFamily = Display)
            }
        }
    }
}

@Composable
fun SearchBox(query: String, onQuery: (String) -> Unit) {
    Box(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(Cub.Card).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (query.isEmpty()) Text("Search songs, artists, albums", color = Cub.Muted, fontSize = 14.sp)
        BasicTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = TextStyle(color = Cub.Text, fontSize = 14.sp, fontFamily = Body),
            cursorBrush = SolidColor(Cub.Accent),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun matches(s: Song, q: String) =
    q.isBlank() || s.title.contains(q, true) || s.artist.contains(q, true) || s.album.contains(q, true)

/** "Sort: Album" button. Opens the sort popup. The choice is saved in Settings. */
@Composable
fun SortButton(st: Settings) {
    var open by remember { mutableStateOf(false) }
    CubButton("Sort: ${sortModeOf(st.sort.value).label}") { open = true }
    if (open) SortPopup(st) { open = false }
}

@Composable
private fun SortPopup(st: Settings, onClose: () -> Unit) {
    AnimatedPopup(onDismiss = onClose) { close ->
        val mode = sortModeOf(st.sort.value)
        val desc = st.sortDesc.value
        val (up, down) = sortDirectionLabels(mode)
        Text("Sort songs", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text("Sort by", color = Cub.Muted, fontSize = 12.sp)
        SortMode.values().toList().chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { m -> Chip(m.label, mode == m) { st.sort.set(m.name) } }
            }
        }
        Text("Direction", color = Cub.Muted, fontSize = 12.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip("Up: $up", !desc) { st.sortDesc.set(false) }
            Chip("Down: $down", desc) { st.sortDesc.set(true) }
        }
        if (mode == SortMode.Album || mode == SortMode.Artist) {
            Text("Songs stay in track order inside each ${mode.label.lowercase()}.", color = Cub.Muted, fontSize = 12.sp)
        }
        CubButton("Done", primary = true) { close() }
    }
}

/** One row of a sorted list: a group header (album / artist) or a song. [index] is the song's place in the played list. */
private class Entry(val key: Any, val header: String?, val song: Song?, val index: Int)

private fun entriesFor(list: List<Song>, mode: SortMode): List<Entry> {
    val out = ArrayList<Entry>(list.size + 8)
    var last: String? = null
    list.forEachIndexed { i, s ->
        val g = groupOf(s, mode)
        if (g != null && g != last) {
            out += Entry("h:$i:$g", g, null, -1)
            last = g
        }
        out += Entry(s.id, null, s, i)
    }
    return out
}

@Composable
private fun GroupHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text, color = Cub.Accent, fontSize = 13.sp, fontFamily = Display, fontWeight = FontWeight.Medium,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().padding(start = 4.dp, top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SortedSongList(
    list: List<Song>, mode: SortMode, currentId: String?, playing: Boolean, onPlay: (List<Song>, Int) -> Unit,
) {
    val entries = remember(list, mode) { entriesFor(list, mode) }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(entries, key = { it.key }) { e ->
            if (e.header != null) GroupHeader(e.header, Modifier.animateItem())
            else {
                val s = e.song!!
                SongRow(s, s.id.toString() == currentId, playing, Modifier.animateItem()) { onPlay(list, e.index) }
            }
        }
    }
}

@Composable
fun LibraryPage(
    folders: List<Folder>, hasAccess: Boolean, onAllow: () -> Unit,
    openKey: String?, onOpen: (String?) -> Unit,
    query: String, onQuery: (String) -> Unit,
    currentId: String?, playing: Boolean, onPlay: (List<Song>, Int) -> Unit,
    st: Settings, onChanged: () -> Unit,
) {
    if (!hasAccess) {
        Column(Modifier.fillMaxSize()) {
            PageTitle("Library", "Your HoneyBeat music.")
            Column(
                Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(RoundedCornerShape(6.dp))
                    .background(Cub.Card).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Allow access to your music", color = Cub.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Text("HoneyBeat needs permission to read audio files so it can list and play them.", color = Cub.Muted, fontSize = 12.sp)
                CubButton("Allow access", primary = true, onClick = onAllow)
            }
        }
        return
    }
    AnimatedContent(
        targetState = openKey,
        transitionSpec = {
            if (targetState != null) {
                (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { -it / 5 } + fadeOut(tween(150)))
            } else {
                (slideInHorizontally(tween(220)) { -it / 5 } + fadeIn(tween(220))) togetherWith
                    (slideOutHorizontally(tween(220)) { it / 5 } + fadeOut(tween(150)))
            }
        },
        label = "lib",
    ) { key ->
        if (key == null) {
            val all = folders.first().songs
            val mode = sortModeOf(st.sort.value)
            val desc = st.sortDesc.value
            val found = remember(all, query, mode, desc) { sortSongs(all.filter { matches(it, query) }, mode, desc) }
            Column(Modifier.fillMaxSize()) {
                PageTitle("Library", "Every folder in Music/HoneyBeat is a playlist.")
                SearchBox(query, onQuery)
                if (all.isEmpty()) {
                    Text(
                        "No songs in Music/HoneyBeat yet. Search or download a playlist in the Search tab.",
                        color = Cub.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
                    )
                }
                if (query.isNotBlank()) {
                    if (found.isEmpty()) {
                        Text("No songs match that search.", color = Cub.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                    }
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { SortButton(st) }
                    SortedSongList(found, mode, currentId, playing, onPlay)
                } else {
                    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(folders, key = { it.key }) { f ->
                            FolderCard(f, Modifier.animateItem()) { onOpen(f.key) }
                        }
                    }
                }
            }
        } else {
            val folder = folders.find { it.key == key } ?: folders.first()
            val mode = sortModeOf(st.sort.value)
            val desc = st.sortDesc.value
            val shown = remember(folder, query, mode, desc) { sortSongs(folder.songs.filter { matches(it, query) }, mode, desc) }
            LibraryDetail(folder, shown, query, onQuery, currentId, playing, st, onBack = { onOpen(null) }, onPlay = onPlay, onChanged = onChanged)
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String, danger: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.bounceClick(0.98f, onClick).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Hover)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Text(title, color = if (danger) Color(0xFFE5645F) else Cub.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(sub, color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

@Composable
private fun PlaylistSettingsPopup(
    folder: Folder, recent: Recent?, hasCustom: Boolean, st: Settings,
    onPickCover: () -> Unit, onDelete: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    AnimatedPopup(onDismiss = onClose) { close ->
        Text(folder.name, color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
        SettingRow("Change cover", "Pick a picture from your phone.") { onPickCover(); close() }
        if (hasCustom) SettingRow("Reset cover", "Go back to the first song's cover.") { PlaylistCovers.remove(ctx, folder.key); close() }
        if (recent != null) {
            SettingRow("Sync now", "Download new songs from the original playlist.") {
                CaveService.start(ctx, recent.url, false)
                close()
            }
            ToggleCard(
                "Auto-update",
                "Check for new songs and download them in the background, even when the app is closed.",
                recent.auto,
            ) { on ->
                val list = CaveState.recents.map { if (it.url == recent.url) it.copy(auto = on) else it }
                CaveState.recents = list
                CaveStore.save(ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE), list)
                if (on) st.watch.set(true)
                WatchService.sync(ctx)
                Thread { HoneyFiles.save(ctx, recent.copy(auto = on)) }.start() // keep the link file in step
            }
        } else if (folder.key.isNotEmpty()) {
            Text("Sync and auto-update are for playlists downloaded from a link.", color = Cub.Muted, fontSize = 12.sp)
        }
        if (folder.key.isNotEmpty()) {
            SettingRow("Delete playlist", "Removes its songs from your phone.", danger = true) { close(); onDelete() }
        }
        CubButton("Close") { close() }
    }
}

@Composable
fun LibraryDetail(
    folder: Folder, shown: List<Song>, query: String, onQuery: (String) -> Unit,
    currentId: String?, playing: Boolean, st: Settings,
    onBack: () -> Unit, onPlay: (List<Song>, Int) -> Unit, onChanged: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var showSettings by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) { PlaylistCovers.save(ctx, folder.key, uri) }
    }
    val custom = remember(PlaylistCovers.version) { PlaylistCovers.has(ctx, folder.key) }
    val recent = CaveState.recents.firstOrNull { safeName(it.name) == folder.key }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "< Library", color = Cub.Accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(onClick = onBack).padding(start = 12.dp, top = 12.dp, end = 16.dp, bottom = 8.dp),
            )
            Spacer(Modifier.weight(1f))
            IconBtn(IconKind.Gear, 40.dp) { showSettings = true }
        }
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            FolderCover(folder.key, folder.songs.firstOrNull()?.id ?: -1L, Modifier.size(84.dp), 400)
            Column(Modifier.weight(1f)) {
                Text(folder.name, color = Cub.Text, fontSize = 24.sp, fontFamily = Display, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    if (folder.songs.size == 1) "1 song" else "${folder.songs.size} songs",
                    color = Cub.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CubButton("Play", primary = true) { onPlay(shown, 0) }
            SortButton(st)
        }
        Spacer(Modifier.height(8.dp))
        SearchBox(query, onQuery)
        if (shown.isEmpty()) {
            Text(
                if (query.isNotEmpty()) "No songs match that search." else "This playlist is empty.",
                color = Cub.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp),
            )
        }
        SortedSongList(shown, sortModeOf(st.sort.value), currentId, playing, onPlay)
    }

    if (showSettings) {
        PlaylistSettingsPopup(
            folder, recent, custom, st,
            onPickCover = { pick.launch("image/*") },
            onDelete = { confirmDelete = true },
            onClose = { showSettings = false },
        )
    }
    if (confirmDelete) {
        AnimatedPopup(onDismiss = { confirmDelete = false }) { close ->
            Text("Delete \"${folder.name}\"?", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
            Text(
                "This deletes ${folder.songs.size} songs from your phone. Files that another app created may be kept.",
                color = Cub.Muted, fontSize = 13.sp,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CubButton("Delete", primary = true) {
                    close()
                    scope.launch(Dispatchers.IO) {
                        folder.songs.forEach {
                            try { ctx.contentResolver.delete(songUri(it.id), null, null) } catch (_: Exception) {}
                        }
                        PlaylistCovers.remove(ctx, folder.key)
                        HoneyFiles.remove(ctx, folder.key) // so the playlist does not come back after a reinstall
                        val list = CaveState.recents.filter { safeName(it.name) != folder.key }
                        CaveStore.save(ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE), list)
                        CaveState.ui { CaveState.recents = list; onChanged(); onBack() }
                        WatchService.sync(ctx)
                    }
                }
                CubButton("Cancel") { close() }
            }
        }
    }
}

@Composable
fun FolderCard(f: Folder, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier.bounceClick(0.98f, onClick).fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Card).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        FolderCover(f.key, f.songs.firstOrNull()?.id ?: -1L, Modifier.size(56.dp), 320)
        Column(Modifier.weight(1f)) {
            Text(f.name, color = Cub.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (f.songs.size == 1) "1 song" else "${f.songs.size} songs", color = Cub.Muted, fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp))
        }
        Text(">", color = Cub.Muted, fontSize = 16.sp, modifier = Modifier.padding(end = 6.dp))
    }
}

@Composable
fun SongRow(s: Song, current: Boolean, playing: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val titleColor by animateColorAsState(if (current) Cub.Accent else Cub.Text, tween(150), label = "title")
    val bar by animateColorAsState(if (current) Cub.Accent else Color.Transparent, tween(150), label = "bar")
    Row(
        modifier.bounceClick(0.98f, onClick).fillMaxWidth().height(IntrinsicSize.Min)
            .clip(RoundedCornerShape(6.dp)).background(Cub.Card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Cover(s.id, 256, Modifier.padding(start = 10.dp).size(46.dp))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                s.title, color = titleColor, fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (s.album.isNotEmpty()) "${s.artist} - ${s.album}" else s.artist,
                color = Cub.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (current) {
            EqBars(playing)
            Spacer(Modifier.width(10.dp))
        }
        Text(fmt(s.durationMs), color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(end = 12.dp))
    }
}

@Composable
fun MiniPlayer(remote: Remote, onOpen: () -> Unit) {
    val md = remote.item?.mediaMetadata
    val id = remote.item?.mediaId?.toLongOrNull() ?: -1L
    Column(Modifier.fillMaxWidth().background(Cub.Black)) {
        Box(Modifier.fillMaxWidth().height(2.dp).background(Cub.Accent))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(id, 256, Modifier.size(42.dp))
            Column(Modifier.weight(1f)) {
                Text(md?.title?.toString() ?: "", color = Cub.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(md?.artist?.toString() ?: "", color = Cub.Muted, fontSize = 12.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            TBtn(G.Prev, 38.dp, 20.dp, Cub.Card, Cub.Text) { remote.prev() }
            PlayPauseBtn(remote.playing, 42.dp, 22.dp) { remote.toggle() }
            TBtn(G.Next, 38.dp, 20.dp, Cub.Card, Cub.Text) { remote.next() }
        }
    }
}

@Composable
fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    val c by animateColorAsState(if (on) Cub.Accent else Cub.Muted, tween(150), label = "chip")
    Box(
        Modifier.bounceClick(onClick = onClick)
            .border(1.dp, if (on) Cub.Accent else Cub.Button, RoundedCornerShape(4.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) { Text(text, color = c, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
}

/** Full-screen player: tinted from the cover, with a level animation and what plays next. */
@Composable
fun PlayingScreen(
    remote: Remote, shuffle: Boolean, repeatAll: Boolean,
    onShuffle: () -> Unit, onRepeat: () -> Unit, onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val item = remote.item
    val player = remote.player
    val id = item?.mediaId?.toLongOrNull() ?: -1L
    val shareScope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<SharePreview?>(null) }

    val tint by produceState(Cub.Panel, id) {
        val b = Covers.load(ctx, id, 64)
        value = if (b != null) {
            val px = Bitmap.createScaledBitmap(b, 1, 1, true).getPixel(0, 0)
            lerp(Color(px), Color.Black, 0.45f)
        } else Cub.Panel
    }
    val tintAnim by animateColorAsState(tint, tween(600), label = "tint")

    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(tintAnim, Cub.Panel, Cub.Black)))
            .pointerInput(Unit) {},
    ) {
        // Anchored layout: top bar, cover fills the middle, everything else is pinned at the bottom.
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp).padding(top = 8.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(IconKind.Down, 40.dp, Color.Black.copy(alpha = 0.35f)) { onClose() }
                Spacer(Modifier.weight(1f))
                Text("NOW PLAYING", color = Cub.Muted, fontSize = 12.sp, fontFamily = Display)
                Spacer(Modifier.weight(1f))
                if (item != null) {
                    // like Spotify's share: build the picture first and show it, then send it to Instagram on request
                    IconBtn(IconKind.Share, 40.dp, Color.Black.copy(alpha = 0.35f)) {
                        val md0 = item.mediaMetadata
                        val title = md0.title?.toString() ?: ""
                        val artist = md0.artist?.toString() ?: ""
                        shareScope.launch {
                            try {
                                val cover = Covers.load(ctx, id, 1024)
                                val card = withContext(Dispatchers.IO) { ShareMusic.renderCard(ctx, cover, title, artist) }
                                preview = SharePreview(card, title, artist)
                            } catch (_: Exception) {
                                android.widget.Toast.makeText(ctx, "Couldn't share this song.", android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                } else Spacer(Modifier.size(40.dp))
            }

            if (item == null) {
                Spacer(Modifier.weight(1f))
                Text("Nothing playing", color = Cub.Text, fontSize = 22.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
                Spacer(Modifier.weight(1f))
                return@Column
            }

            val scale by animateFloatAsState(if (remote.playing) 1f else 0.9f, tween(300), label = "coverScale")
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val side = minOf(maxWidth, maxHeight, 340.dp).coerceAtLeast(120.dp)
                Cover(
                    id, 1024,
                    Modifier.size(side).graphicsLayer { scaleX = scale; scaleY = scale },
                    corner = 12.dp,
                )
            }

            val md = item.mediaMetadata
            Crossfade(
                targetState = (md.title?.toString() ?: "") to (md.artist?.toString() ?: ""),
                animationSpec = tween(220), label = "titles",
                modifier = Modifier.fillMaxWidth().height(84.dp),
            ) { (title, artist) ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(title, color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = Cub.Muted, fontSize = 14.sp, textAlign = TextAlign.Center,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
                }
            }

            Visualizer(remote.playing, Cub.Accent)
            Spacer(Modifier.height(10.dp))

            var pos by remember { mutableLongStateOf(0L) }
            var drag by remember { mutableStateOf<Float?>(null) }
            LaunchedEffect(player) {
                while (true) {
                    player?.let { pos = it.currentPosition }
                    delay(400)
                }
            }
            val dur = (player?.duration ?: 0L).coerceAtLeast(0L)
            val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
            Column(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Slider(
                    value = frac,
                    onValueChange = { drag = it },
                    onValueChangeFinished = {
                        drag?.let { player?.seekTo((it * dur).toLong()) }
                        drag = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Cub.Accent, activeTrackColor = Cub.Accent, inactiveTrackColor = Cub.Button,
                    ),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(fmt((frac * dur).toLong()), color = Cub.Muted, fontSize = 12.sp)
                    Text(fmt(dur), color = Cub.Muted, fontSize = 12.sp)
                }
            }

            Row(
                Modifier.widthIn(max = 420.dp).fillMaxWidth().padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Chip("Shuffle", shuffle, onShuffle)
                TBtn(G.Prev, 48.dp, 26.dp, Cub.Card, Cub.Text) { remote.prev() }
                PlayPauseBtn(remote.playing, 64.dp, 32.dp) { remote.toggle() }
                TBtn(G.Next, 48.dp, 26.dp, Cub.Card, Cub.Text) { remote.next() }
                Chip("Repeat", repeatAll, onRepeat)
            }

            val upNext = remember(item, player) {
                player?.let { p ->
                    if (p.hasNextMediaItem()) p.getMediaItemAt(p.nextMediaItemIndex).mediaMetadata.title?.toString() else null
                }
            }
            Crossfade(upNext, animationSpec = tween(200), label = "next", modifier = Modifier.fillMaxWidth().height(34.dp)) { t ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    if (t != null) {
                        Text("Up next  -  $t", color = Cub.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    preview?.let { p ->
        SharePreviewPopup(
            p,
            onShare = {
                // runs in the player's scope, which outlives the popup closing
                shareScope.launch {
                    try {
                        val uri = withContext(Dispatchers.IO) { ShareMusic.saveCard(ctx, p.card) }
                        ShareMusic.toInstagram(ctx, uri, listOf(p.title, p.artist).filter { it.isNotBlank() }.joinToString(" - "))
                    } catch (_: Exception) {
                        android.widget.Toast.makeText(ctx, "Couldn't share this song.", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
            },
            onClose = { preview = null },
        )
    }
}

/** The finished story card, kept until the person confirms or cancels. */
class SharePreview(val card: Bitmap, val title: String, val artist: String)

/** Shows exactly what will be posted, so the person can check it before Instagram opens. */
@Composable
fun SharePreviewPopup(p: SharePreview, onShare: () -> Unit, onClose: () -> Unit) {
    AnimatedPopup(onDismiss = onClose) { close ->
        Text("Share preview", color = Cub.Text, fontSize = 20.sp, fontFamily = Display, fontWeight = FontWeight.Medium)
        Text("This is the picture that will be sent to Instagram.", color = Cub.Muted, fontSize = 12.sp)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(
                p.card.asImageBitmap(), null,
                Modifier.height(420.dp).aspectRatio(9f / 16f).clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Fit,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CubButton("Share to Instagram", primary = true) { onShare(); close() }
            CubButton("Cancel") { close() }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, color = Cub.Muted, fontSize = 12.sp, fontFamily = Display,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp),
    )
}

@Composable
private fun ChoiceCard(title: String, sub: String, content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Card).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(title, color = Cub.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun ActionCard(title: String, sub: String, button: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(Cub.Card)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Cub.Text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(sub, color = Cub.Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
        }
        CubButton(button, onClick = onClick)
    }
}

@Composable
fun OptionsPage(st: Settings, onRescan: () -> Unit, songCount: Int, listCount: Int, onLogin: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        PageTitle("Options", "How HoneyBeat behaves. Changes apply right away.")

        SectionLabel("PLAYBACK")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleCard("Shuffle", "Play the queue in random order.", st.shuffle.value) { st.shuffle.set(it) }
            ToggleCard("Repeat all", "Start over when the last song ends.", st.repeat.value) { st.repeat.set(it) }
            ToggleCard("Show cover art", "Covers in the lists and on the player.", st.art.value) { st.art.set(it) }
            ToggleCard(
                "Cover accent color",
                "While a song is playing, buttons and highlights use a color from its cover instead of blue.",
                st.coverAccent.value,
            ) { st.coverAccent.set(it) }
            ActionCard("Library", "$songCount songs in $listCount playlists.", "Rescan", onRescan)
        }

        SectionLabel("YOUTUBE ACCOUNT")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionCard(
                if (CaveState.signedIn) "Logged in to YouTube" else "YouTube",
                if (CaveState.signedIn) {
                    (if (CaveState.ytName.isNotBlank()) "Signed in as ${CaveState.ytName}. " else "") +
                        "Downloads, private playlists and importing your music playlists are on."
                } else "Not signed in. YouTube often blocks downloads without a sign-in, so sign in here.",
                if (CaveState.signedIn) "Sign out" else "Sign in",
            ) {
                if (CaveState.signedIn) {
                    YtAuth.clear(ctx)
                    CaveState.signedIn = false
                    CaveState.ytName = ""
                } else onLogin()
            }
        }

        SectionLabel("PLAYLIST UPDATES")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ToggleCard(
                "Auto-update playlists",
                "Keeps checking the playlists that have auto-update on and downloads new songs, even when the app is closed. Shows a small notification while on.",
                st.watch.value,
            ) { st.watch.set(it) }
            ChoiceCard("Check every", "How often to look for new songs.") {
                listOf(5 to "5 min", 15 to "15 min", 30 to "30 min", 60 to "1 hour").forEach { (n, label) ->
                    Chip(label, st.every.value == n) { st.every.set(n); WatchService.sync(ctx) }
                }
            }
            ToggleCard("Notify about new songs", "A notification when new songs are found or downloaded.", st.notify.value) { st.notify.set(it) }
        }

        SectionLabel("SEARCH AND DOWNLOADS")
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard(
                "Parallel downloads",
                "How many songs download at the same time. All runs up to 20 at once, which is fastest but heavy on the phone and battery.",
            ) {
                listOf(1 to "1", 2 to "2", 4 to "4", 8 to "8", 0 to "All").forEach { (n, label) ->
                    Chip(label, st.par.value == n) { st.par.set(n) }
                }
            }
            ChoiceCard(
                "Audio quality",
                "MP3 320 kbps is the highest MP3 setting. Best original keeps the source audio as M4A with no extra lossy conversion.",
            ) {
                Chip("MP3 320 kbps", st.quality.value == "mp3") { st.quality.set("mp3") }
                Chip("Best original (M4A)", st.quality.value == "m4a") { st.quality.set("m4a") }
            }
            ToggleCard("Cover art in downloads", "Embeds the cover in each file. If it fails for a song, it retries without it.", st.caveArt.value) { st.caveArt.set(it) }
            ToggleCard("Ask on mobile data", "Ask before downloading on a metered connection.", st.ask.value) { st.ask.set(it) }
            ToggleCard("Auto-update yt-dlp", "Updates the download engine by itself every 12 hours.", st.autoYt.value) { st.autoYt.set(it) }
            ActionCard(
                "Update yt-dlp",
                CaveState.updateMsg.ifEmpty { "Press Update if downloads start failing." },
                if (CaveState.updating) "Updating..." else "Update",
            ) {
                if (!CaveState.updating && !CaveState.running) {
                    CaveState.updating = true
                    CaveState.updateMsg = "Updating..."
                    scope.launch(Dispatchers.IO) {
                        val msg = CaveEngine.update(ctx)
                        CaveState.addLog(msg)
                        CaveState.ui { CaveState.updateMsg = msg; CaveState.updating = false }
                    }
                }
            }
        }

        Column(Modifier.padding(start = 20.dp, top = 40.dp, bottom = 24.dp)) {
            DotText("NEONBEAR")
            Text("Copyright (c) 2026 - NeonBear", color = Cub.Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    }
}
