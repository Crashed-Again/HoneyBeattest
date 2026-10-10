package com.neonbear.honeybeat

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** One cloned playlist we remember, so Sync only fetches new songs. [auto] = keep it updated in the background. */
data class Recent(val url: String, val name: String, val keys: List<String>, val auto: Boolean = false)
data class Item(val key: String, val target: String, val label: String)
data class Resolved(val name: String, val items: List<Item>)
data class Hit(val id: String, val title: String, val by: String, val seconds: Int)
data class MyPl(val url: String, val title: String)
data class MyPlaylists(val items: List<MyPl>, val error: String?)

/** Songs picked from the search, waiting for the downloader. */
object CaveQueue {
    private val q = ArrayDeque<Item>()

    @Synchronized fun add(i: Item) { q.addLast(i) }
    @Synchronized fun take(): List<Item> { val l = q.toList(); q.clear(); return l }
    @Synchronized fun clear() { q.clear() }
    @Synchronized fun isEmpty() = q.isEmpty()
}

/** Everything the Search tab shows. Written from the download service, read by Compose. */
object CaveState {
    var running by mutableStateOf(false)
    var status by mutableStateOf("Ready.")
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var current by mutableStateOf("")
    var lastError by mutableStateOf("")
    var finished by mutableIntStateOf(0)
    var updating by mutableStateOf(false)
    var updateMsg by mutableStateOf("")
    var signedIn by mutableStateOf(false)
    var promptShown by mutableStateOf(false)
    var recents by mutableStateOf<List<Recent>>(emptyList())
    val log = mutableStateListOf<String>()

    private val main = Handler(Looper.getMainLooper())
    fun ui(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun addLog(line: String) = ui {
        log.add(line)
        while (log.size > 60) log.removeAt(0)
    }
}

object CaveStore {
    fun load(p: SharedPreferences): List<Recent> = try {
        val arr = JSONArray(p.getString("cave_recents", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val k = o.getJSONArray("keys")
            Recent(o.getString("url"), o.getString("name"), (0 until k.length()).map { k.getString(it) }, o.optBoolean("auto", false))
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(p: SharedPreferences, list: List<Recent>) {
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("url", r.url).put("name", r.name).put("keys", JSONArray(r.keys)).put("auto", r.auto))
        }
        p.edit().putString("cave_recents", arr.toString()).apply()
    }
}

/** YouTube login: the in-app sign-in page leaves cookies behind, we hand them to yt-dlp as a cookies file. */
object YtAuth {
    const val LOGIN_URL = "https://accounts.google.com/ServiceLogin?service=youtube&continue=https%3A%2F%2Fwww.youtube.com%2F"
    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    fun file(ctx: Context) = File(ctx.filesDir, "yt-cookies.txt")
    fun signedIn(ctx: Context) = file(ctx).exists()

    /** Returns true when the WebView holds a logged-in YouTube session and the cookies file was written. */
    fun saveFromWebView(ctx: Context): Boolean {
        val cm = CookieManager.getInstance()
        val yt = cm.getCookie("https://www.youtube.com") ?: return false
        if (!yt.contains("SAPISID") && !yt.contains("__Secure-3PSID")) return false
        val google = cm.getCookie("https://accounts.google.com") ?: ""
        val sb = StringBuilder("# Netscape HTTP Cookie File\n")
        fun add(raw: String, domain: String) {
            raw.split(";").map { it.trim() }.filter { it.contains("=") }.forEach {
                val name = it.substringBefore("=")
                val value = it.substringAfter("=")
                sb.append("$domain\tTRUE\t/\tTRUE\t2147483647\t$name\t$value\n")
            }
        }
        add(yt, ".youtube.com")
        add(google, ".google.com")
        file(ctx).writeText(sb.toString())
        return true
    }

    fun clear(ctx: Context) {
        file(ctx).delete()
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

private fun YoutubeDLRequest.auth(ctx: Context): YoutubeDLRequest {
    if (YtAuth.signedIn(ctx)) addOption("--cookies", YtAuth.file(ctx).absolutePath)
    return this
}

fun errText(e: Exception): String {
    val t = e.message ?: e.toString()
    val line = t.lines().lastOrNull { it.contains("ERROR", true) } ?: t.lines().lastOrNull { it.isNotBlank() } ?: "failed"
    return line.replace(Regex("^.*?ERROR:\\s*"), "").trim().ifEmpty { "failed" }
}

object CaveEngine {
    private var ready = false

    /** yt-dlp is updated by itself every 12 hours (can be switched off in Options). */
    fun maybeUpdate(ctx: Context, prefs: SharedPreferences) {
        if (!prefs.getBoolean("cave_autoupdate", true)) return
        val last = prefs.getLong("cave_update_at", 0L)
        if (System.currentTimeMillis() - last < 12 * 3600 * 1000L) return
        CaveState.ui { CaveState.status = "Updating yt-dlp..." }
        val msg = update(ctx)
        CaveState.addLog(msg)
        if (!msg.startsWith("Update failed")) prefs.edit().putLong("cave_update_at", System.currentTimeMillis()).apply()
    }

    /** Search YouTube for songs (no download). */
    fun search(ctx: Context, q: String): List<Hit> {
        init(ctx)
        val req = YoutubeDLRequest("ytsearch15:$q").auth(ctx)
        req.addOption("--flat-playlist")
        req.addOption("--print", "%(id)s|||%(title)s|||%(uploader,channel|)s|||%(duration|0)s")
        val out = YoutubeDL.getInstance().execute(req, "cave-search").out
        return out.lines().map { it.split("|||") }
            .filter { it.size >= 4 && it[0].length in 8..15 }
            .map { Hit(it[0], it[1], it[2], it[3].toDoubleOrNull()?.toInt() ?: 0) }
    }

    /** The signed-in user's playlists (plus Liked videos and Watch later). */
    fun myPlaylists(ctx: Context): MyPlaylists {
        val base = listOf(
            MyPl("https://www.youtube.com/playlist?list=LL", "Liked videos"),
            MyPl("https://www.youtube.com/playlist?list=WL", "Watch later"),
        )
        return try {
            init(ctx)
            val req = YoutubeDLRequest("https://www.youtube.com/feed/playlists").auth(ctx)
            req.addOption("--flat-playlist")
            req.addOption("--print", "%(url)s|||%(title)s")
            val out = YoutubeDL.getInstance().execute(req, "cave-mine").out
            val found = out.lines().map { it.split("|||") }
                .filter { it.size >= 2 && it[0].startsWith("http") && it[0].contains("list=") }
                .map { MyPl(it[0].trim(), it[1].trim()) }
                .filter { f -> base.none { it.url == f.url } }
            MyPlaylists(base + found, null)
        } catch (e: Exception) {
            MyPlaylists(base, errText(e))
        }
    }

    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx)
        FFmpeg.getInstance().init(ctx)
        ready = true
    }

    fun update(ctx: Context): String = try {
        init(ctx)
        val st = YoutubeDL.getInstance().updateYoutubeDL(ctx)
        "yt-dlp update: ${st ?: "already up to date"}"
    } catch (e: Exception) {
        "Update failed: ${e.message}"
    }
}

private fun httpGet(url: String): String {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 20000
    c.setRequestProperty("User-Agent", YtAuth.UA)
    c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
    return c.inputStream.bufferedReader().use { it.readText() }
}

private fun httpBytes(url: String): ByteArray {
    val c = URL(url).openConnection() as HttpURLConnection
    c.connectTimeout = 15000
    c.readTimeout = 20000
    return c.inputStream.use { it.readBytes() }
}

private fun findKey(n: Any?, key: String): Any? {
    when (n) {
        is JSONObject -> {
            if (n.has(key)) return n.get(key)
            for (k in n.keys()) findKey(n.get(k), key)?.let { return it }
        }
        is JSONArray -> for (i in 0 until n.length()) findKey(n.get(i), key)?.let { return it }
    }
    return null
}

private fun searchItem(prefix: String, artist: String, title: String): Item {
    val label = if (artist.isBlank()) title else "$artist - $title"
    return Item("$prefix:${label.lowercase()}", "ytsearch1:$label audio", label)
}

/** True when [text] looks like a web link rather than something to search for. */
fun looksLikeLink(text: String): Boolean =
    Regex("^(https?://|www\\.|music\\.youtube\\.com|youtube\\.com|youtu\\.be|open\\.spotify\\.com|music\\.apple\\.com)\\S*$", RegexOption.IGNORE_CASE)
        .matches(text.trim())

/**
 * Cleans up a pasted link. Any YouTube / YouTube Music link that carries a playlist id (share links, watch?v=..&list=..,
 * music.youtube.com/browse/VL..) becomes a plain youtube.com/playlist link, which yt-dlp reads reliably.
 */
fun normalizeLink(raw: String): String {
    var u = raw.trim().trim('"', '\'', '<', '>', ' ')
    if (u.isEmpty()) return u
    if (!u.contains("://") && u.contains('.') && !u.contains(' ')) u = "https://$u"
    val host = Regex("^https?://([^/?#]+)", RegexOption.IGNORE_CASE).find(u)?.groupValues?.get(1)?.lowercase() ?: return u
    if (!(host.endsWith("youtube.com") || host == "youtu.be")) return u
    val list = Regex("[?&]list=([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
        ?: Regex("/browse/VL([A-Za-z0-9_-]+)").find(u)?.groupValues?.get(1)
        ?: return u
    // auto-generated mixes (RD...) only open together with the video they start from
    if (list.startsWith("RD")) return u
    return "https://www.youtube.com/playlist?list=$list"
}

private fun youtube(ctx: Context, url: String): Resolved {
    fun list(withAuth: Boolean): List<List<String>> {
        val req = YoutubeDLRequest(url)
        if (withAuth) req.auth(ctx)
        req.addOption("--flat-playlist")
        req.addOption("--yes-playlist")
        req.addOption("--ignore-errors")
        req.addOption("--print", "%(playlist_title|)s|||%(id)s|||%(title)s")
        val out = YoutubeDL.getInstance().execute(req, "cave-list").out
        return out.lines().filter { it.contains("|||") }.map { it.split("|||") }
            .filter { it.size >= 3 && it[1].isNotBlank() && it[1] != "NA" }
            .filter { it[2].trim() !in setOf("[Private video]", "[Deleted video]") }
    }

    // Signed in: try with the account first (private lists), then without it, because a stale login can break a public list.
    val tries = if (YtAuth.signedIn(ctx)) listOf(true, false) else listOf(false)
    var rows: List<List<String>> = emptyList()
    var failure: Exception? = null
    for (withAuth in tries) {
        if (CaveJob.cancelled) break
        try {
            rows = list(withAuth)
            if (rows.isNotEmpty()) break
        } catch (e: Exception) {
            failure = e
        }
    }
    if (rows.isEmpty()) {
        if (failure != null && !CaveJob.cancelled) throw failure
        error("No songs found. Is the playlist public (or are you signed in)?")
    }
    val name = rows.map { it[0].trim() }.firstOrNull { it.isNotEmpty() && it != "NA" } ?: "Singles"
    return Resolved(name, rows.map { Item("yt:${it[1]}", "https://www.youtube.com/watch?v=${it[1]}", it[2]) })
}

private fun spotify(url: String): Resolved {
    val m = Regex("open\\.spotify\\.com/(?:intl-[a-zA-Z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)").find(url)
        ?: error("That doesn't look like a Spotify playlist link.")
    val html = httpGet("https://open.spotify.com/embed/${m.groupValues[1]}/${m.groupValues[2]}")
    val data = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        .find(html)?.groupValues?.get(1) ?: error("Couldn't read the Spotify page.")
    val root = JSONObject(data)
    val entity = findKey(root, "entity") as? JSONObject
    val name = entity?.optString("name")?.ifEmpty { entity.optString("title") }?.ifEmpty { "Spotify" } ?: "Spotify"
    val list = findKey(root, "trackList") as? JSONArray
    val items = ArrayList<Item>()
    if (list != null) {
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val title = o.optString("title")
            if (title.isNotBlank()) items += searchItem("sp", o.optString("subtitle").replace('\u00a0', ' '), title)
        }
    } else if (entity != null) {
        val artist = entity.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
        items += searchItem("sp", artist, name)
    }
    if (items.isEmpty()) error("No songs found on that Spotify page. Is it public?")
    return Resolved(name, items)
}

private fun artistOf(o: JSONObject): String = when (val b = o.opt("byArtist") ?: o.opt("artist")) {
    is JSONObject -> b.optString("name")
    is JSONArray -> (0 until b.length()).joinToString(", ") { b.optJSONObject(it)?.optString("name") ?: "" }
    is String -> b
    else -> ""
}

private fun apple(url: String): Resolved {
    val html = httpGet(url)
    val blocks = Regex("<script[^>]*type=\"application/ld\\+json\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        .findAll(html).map { it.groupValues[1] }
    for (b in blocks) {
        val o = try { JSONObject(b) } catch (_: Exception) { continue }
        val tracks = o.optJSONArray("track") ?: o.optJSONArray("tracks") ?: continue
        val albumArtist = artistOf(o)
        val items = ArrayList<Item>()
        for (i in 0 until tracks.length()) {
            val t = tracks.optJSONObject(i) ?: continue
            val title = t.optString("name")
            if (title.isBlank()) continue
            items += searchItem("am", artistOf(t).ifBlank { albumArtist }, title)
        }
        if (items.isNotEmpty()) return Resolved(o.optString("name").ifBlank { "Apple Music" }, items)
    }
    error("Couldn't read that Apple Music page. Is the playlist public?")
}

fun resolve(ctx: Context, rawUrl: String): Resolved {
    val url = normalizeLink(rawUrl)
    return when {
        "spotify.com" in url -> spotify(url)
        "music.apple.com" in url || "itunes.apple.com" in url -> apple(url)
        else -> youtube(ctx, url)
    }
}

fun safeName(n: String): String =
    n.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().trimEnd('.').take(80).ifEmpty { "Playlist" }

/** Audio containers yt-dlp can leave behind. Anything else in the folder (thumbnails, .part files) is ignored. */
private val AUDIO_EXT = setOf("mp3", "m4a", "aac", "opus", "ogg", "flac", "wav", "webm", "mp4")

/** MediaStore wants a MIME type that matches the file extension, otherwise it renames the file. */
private fun mimeFor(ext: String): String = when (ext.lowercase()) {
    "mp3" -> "audio/mpeg"
    "m4a", "mp4" -> "audio/mp4"
    "aac" -> "audio/aac"
    "opus", "ogg" -> "audio/ogg"
    "flac" -> "audio/flac"
    "wav" -> "audio/x-wav"
    "webm" -> "audio/webm"
    else -> "audio/mpeg"
}

/** One way of asking yt-dlp for a song. YouTube changes what it serves often, so we try several until one works. */
private data class Plan(val art: Boolean, val format: String?, val clients: String?)

private fun downloadTrack(ctx: Context, target: String, art: Boolean, m4a: Boolean, proc: String): File {
    val dir = File(ctx.cacheDir, "cave-dl/${System.nanoTime()}").apply { mkdirs() }
    val preferred = if (m4a) "bestaudio[ext=m4a]/bestaudio/best" else "bestaudio/best"

    fun attempt(p: Plan) {
        val r = YoutubeDLRequest(target).auth(ctx)
        r.addOption("--no-playlist")
        r.addOption("-x")
        // null = let yt-dlp pick, which avoids "Requested format is not available" when our selector matches nothing
        if (p.format != null) r.addOption("-f", p.format)
        if (m4a) {
            r.addOption("--audio-format", "m4a")
        } else {
            r.addOption("--audio-format", "mp3")
            r.addOption("--audio-quality", "320K")
        }
        if (p.clients != null) r.addOption("--extractor-args", "youtube:player_client=${p.clients}")
        r.addOption("--embed-metadata")
        r.addOption("--no-mtime")
        if (p.art) {
            r.addOption("--embed-thumbnail")
            r.addOption("--convert-thumbnails", "jpg")
            r.addOption("--postprocessor-args", "ThumbnailsConvertor+ffmpeg_o:-q:v 1") // best jpg quality
        }
        r.addOption("-o", dir.absolutePath + "/%(title).120B.%(ext)s")
        YoutubeDL.getInstance().execute(r, proc)
    }

    val plans = buildList {
        if (art) add(Plan(true, preferred, null))
        add(Plan(false, preferred, null))                          // cover art is the usual thing that breaks
        add(Plan(false, "ba/b", "default,android_vr"))             // other YouTube clients serve other formats
        add(Plan(false, null, null))                               // last resort: yt-dlp's own choice
    }

    var last: Exception? = null
    for (p in plans) {
        if (CaveJob.cancelled) break
        dir.listFiles()?.forEach { it.delete() }
        try {
            attempt(p)
            last = null
            break
        } catch (e: Exception) {
            last = e
        }
    }
    // some yt-dlp errors still leave a finished file behind, so look before giving up
    val out = dir.listFiles()
        ?.filter { it.isFile && it.extension.lowercase() in AUDIO_EXT && it.length() > 0 }
        ?.maxByOrNull { it.length() }
    if (out != null) return out
    throw last ?: IllegalStateException("yt-dlp produced no audio file")
}

private fun saveToMusic(ctx: Context, f: File, folder: String) {
    val values = ContentValues().apply {
        put(MediaStore.Audio.Media.DISPLAY_NAME, f.name)
        put(MediaStore.Audio.Media.MIME_TYPE, mimeFor(f.extension))
        put(MediaStore.Audio.Media.RELATIVE_PATH, if (folder.isEmpty()) "Music/Cave" else "Music/Cave/$folder")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: error("Couldn't create the file in Music/Cave")
    resolver.openOutputStream(uri)!!.use { out -> f.inputStream().use { it.copyTo(out) } }
    values.clear()
    values.put(MediaStore.Audio.Media.IS_PENDING, 0)
    resolver.update(uri, values, null, null)
}

/** "All" means as many as is safe: each download is a separate Python + ffmpeg process, and Android kills the app if there are too many. */
private const val MAX_ALL = 20

object CaveJob {
    @Volatile
    var cancelled = false
    private val procs = ConcurrentHashMap.newKeySet<String>()

    fun cancel() {
        cancelled = true
        CaveQueue.clear()
        (procs + "cave-list").forEach {
            try { YoutubeDL.getInstance().destroyProcessById(it) } catch (_: Exception) {}
        }
    }

    private fun reset() {
        cancelled = false
        CaveState.ui {
            CaveState.lastError = ""; CaveState.done = 0; CaveState.total = 0
            CaveState.current = ""; CaveState.status = "Starting yt-dlp..."
        }
    }

    private fun fail(e: Exception) {
        val msg = errText(e)
        CaveState.addLog("error: $msg")
        CaveState.ui { CaveState.lastError = msg; CaveState.status = "Stopped." }
    }

    private fun finish(ctx: Context) {
        File(ctx.cacheDir, "cave-dl").deleteRecursively()
        CaveState.ui { CaveState.finished += 1 }
    }

    /** Playlist covers are the first video's thumbnail, like on YouTube. Only for YouTube playlists without a cover yet. */
    private fun fetchCover(ctx: Context, res: Resolved, folder: String) {
        val first = res.items.firstOrNull()?.key ?: return
        if (!first.startsWith("yt:") || PlaylistCovers.has(ctx, folder)) return
        try {
            val bytes = httpBytes("https://i.ytimg.com/vi/${first.removePrefix("yt:")}/hqdefault.jpg")
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { PlaylistCovers.saveBitmap(ctx, folder, it) }
        } catch (_: Exception) {
        }
    }

    /** Blocking. Clones a playlist (or syncs a known one) into Music/Cave/<playlist name>. */
    fun run(ctx: Context, url: String, auto: Boolean, onProgress: (String) -> Unit) {
        reset()
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        try {
            CaveEngine.init(ctx)
            CaveEngine.maybeUpdate(ctx, prefs)
            CaveState.ui { CaveState.status = "Reading playlist..." }
            onProgress("Reading playlist...")
            val res = resolve(ctx, url)
            val folder = safeName(res.name)
            fetchCover(ctx, res, folder)
            download(ctx, res, url, folder, auto, onProgress)
        } catch (e: Exception) {
            if (!cancelled) fail(e)
        } finally {
            finish(ctx)
        }
    }

    /** Blocking. Downloads songs picked from the search into Music/Cave itself. */
    fun runItems(ctx: Context, items: List<Item>, onProgress: (String) -> Unit) {
        reset()
        try {
            CaveEngine.init(ctx)
            download(ctx, Resolved("Songs", items), null, "", false, onProgress)
        } catch (e: Exception) {
            if (!cancelled) fail(e)
        } finally {
            finish(ctx)
        }
    }

    private fun download(ctx: Context, res: Resolved, recentUrl: String?, folder: String, auto: Boolean, onProgress: (String) -> Unit) {
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val art = prefs.getBoolean("cave_art", true)
        val par = prefs.getInt("cave_par", 4) // 0 = all
        val m4a = prefs.getString("cave_quality", "mp3") == "m4a"

        val prev = recentUrl?.let { u -> CaveStore.load(prefs).firstOrNull { it.url == u } }
        val keys = (prev?.keys ?: emptyList()).toMutableList()
        val autoFlag = prev?.auto ?: auto
        val known = keys.toSet()
        val todo = res.items.filter { it.key !in known }
        CaveState.addLog("${res.name}: ${res.items.size} songs, ${todo.size} new")

        fun saveRecent() {
            if (recentUrl == null) return
            val list = CaveStore.load(prefs).filter { it.url != recentUrl }
            val updated = listOf(Recent(recentUrl, res.name, keys.toList(), autoFlag)) + list
            CaveStore.save(prefs, updated)
            CaveState.ui { CaveState.recents = updated }
        }
        saveRecent() // remembers the playlist (and its auto-update flag) even if nothing is new

        if (todo.isEmpty()) {
            CaveState.ui { CaveState.status = "Already up to date." }
            return
        }
        CaveState.ui { CaveState.total = todo.size; CaveState.done = 0 }

        val workers = (if (par <= 0) minOf(todo.size, MAX_ALL) else minOf(par, todo.size)).coerceAtLeast(1)
        val pool = Executors.newFixedThreadPool(workers)
        val doneC = AtomicInteger()
        val okC = AtomicInteger()
        val failC = AtomicInteger()
        val active = AtomicInteger()
        val lock = Any()

        fun refresh(label: String?) {
            val d = doneC.get()
            val a = active.get()
            val text = "Downloading $d of ${todo.size} ($a running)"
            CaveState.ui {
                CaveState.done = d
                CaveState.status = text
                if (label != null) CaveState.current = label
            }
            onProgress("$d/${todo.size}  ${label ?: ""}")
        }

        val futures = todo.mapIndexed { i, item ->
            pool.submit(Runnable {
                if (cancelled) return@Runnable
                // stagger the first wave so the phone isn't hit by every Python start-up at once
                if (i < workers) try { Thread.sleep(i * 250L) } catch (_: InterruptedException) {}
                if (cancelled) return@Runnable
                val proc = "cave-$i"
                procs.add(proc)
                active.incrementAndGet()
                refresh(item.label)
                try {
                    val file = downloadTrack(ctx, item.target, art, m4a, proc)
                    saveToMusic(ctx, file, folder)
                    file.parentFile?.deleteRecursively()
                    synchronized(lock) {
                        keys += item.key
                        saveRecent()
                    }
                    okC.incrementAndGet()
                    CaveState.addLog("ok: ${item.label}")
                } catch (e: Exception) {
                    if (!cancelled) {
                        failC.incrementAndGet()
                        val msg = errText(e)
                        CaveState.addLog("failed: ${item.label} - $msg")
                        CaveState.ui { CaveState.lastError = "${item.label}: $msg" }
                    }
                } finally {
                    procs.remove(proc)
                    active.decrementAndGet()
                    doneC.incrementAndGet()
                    refresh(null)
                }
            })
        }
        futures.forEach { try { it.get() } catch (_: Exception) {} }
        pool.shutdown()

        val ok = okC.get()
        val failed = failC.get()
        val end = if (cancelled) "Stopped. $ok new." else "Done. $ok new" + if (failed > 0) ", $failed failed." else "."
        CaveState.ui { CaveState.done = todo.size; CaveState.current = ""; CaveState.status = end }
        if (ok > 0 && !cancelled) {
            Notifier.post(ctx, (recentUrl ?: "songs").hashCode(), res.name.ifEmpty { "Songs" }, "Downloaded $ok new song" + if (ok == 1) "" else "s")
        }
    }
}
