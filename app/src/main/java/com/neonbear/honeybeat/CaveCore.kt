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
data class Resolved(val name: String, val items: List<Item>, val cover: String? = null)
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
    var ytName by mutableStateOf("")
    var promptShown by mutableStateOf(false)
    var recents by mutableStateOf<List<Recent>>(emptyList())
    var streams by mutableStateOf<List<StreamList>>(emptyList())
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

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)

    fun savedName(ctx: Context): String = prefs(ctx).getString("yt_name", "") ?: ""

    /**
     * Looks up the account name in the background (best effort, YouTube can refuse it) and shows it in Options.
     * Asks YouTube's own account menu, signing the request with the SAPISID cookie the way the website does.
     */
    fun refreshName(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            val name = fetchName(app)
            if (!name.isNullOrBlank()) {
                prefs(app).edit().putString("yt_name", name).apply()
                CaveState.ui { CaveState.ytName = name }
            }
        }.start()
    }

    private fun fetchName(ctx: Context): String? {
        try {
            val rows = file(ctx).takeIf { it.exists() }?.readLines().orEmpty()
                .filter { !it.startsWith("#") }.map { it.split("\t") }.filter { it.size >= 7 && it[0] == ".youtube.com" }
            val sapisid = (rows.firstOrNull { it[5] == "SAPISID" } ?: rows.firstOrNull { it[5] == "__Secure-3PAPISID" })?.get(6)
                ?: return null
            val cookie = rows.joinToString("; ") { "${it[5]}=${it[6]}" }
            val origin = "https://www.youtube.com"
            val ts = System.currentTimeMillis() / 1000
            val hash = java.security.MessageDigest.getInstance("SHA-1")
                .digest("$ts $sapisid $origin".toByteArray()).joinToString("") { "%02x".format(it) }
            val c = URL("$origin/youtubei/v1/account/account_menu?prettyPrint=false").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.doOutput = true
            c.setRequestProperty("User-Agent", UA)
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Cookie", cookie)
            c.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_$hash")
            c.setRequestProperty("Origin", origin)
            c.setRequestProperty("X-Origin", origin)
            c.setRequestProperty("X-Goog-AuthUser", "0")
            c.outputStream.use {
                it.write("""{"context":{"client":{"clientName":"WEB","clientVersion":"2.20260101.00.00","hl":"en"}}}""".toByteArray())
            }
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            fun text(key: String): String? {
                val o = findKey(root, key) as? JSONObject ?: return null
                return o.optString("simpleText").ifBlank { o.optJSONArray("runs")?.optJSONObject(0)?.optString("text") ?: "" }.ifBlank { null }
            }
            return text("accountName") ?: text("channelHandle")
        } catch (_: Exception) {
            return null
        }
    }

    private fun cookieRows(ctx: Context): List<List<String>> =
        file(ctx).takeIf { it.exists() }?.readLines().orEmpty()
            .filter { !it.startsWith("#") }.map { it.split("\t") }.filter { it.size >= 7 && it[0] == ".youtube.com" }

    /** One signed call to YouTube Music's own library API (the same one its website uses). */
    private fun musicBrowse(rows: List<List<String>>, query: String, body: String): JSONObject {
        val sapisid = (rows.firstOrNull { it[5] == "SAPISID" } ?: rows.firstOrNull { it[5] == "__Secure-3PAPISID" })?.get(6)
            ?: error("The YouTube login is missing. Sign out and sign in again.")
        val cookie = rows.joinToString("; ") { "${it[5]}=${it[6]}" }
        val origin = "https://music.youtube.com"
        val ts = System.currentTimeMillis() / 1000
        val hash = java.security.MessageDigest.getInstance("SHA-1")
            .digest("$ts $sapisid $origin".toByteArray()).joinToString("") { "%02x".format(it) }
        val c = URL("$origin/youtubei/v1/browse?prettyPrint=false$query").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 15000
        c.readTimeout = 25000
        c.doOutput = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Cookie", cookie)
        c.setRequestProperty("Authorization", "SAPISIDHASH ${ts}_$hash")
        c.setRequestProperty("Origin", origin)
        c.setRequestProperty("X-Origin", origin)
        c.setRequestProperty("Referer", "$origin/")
        c.setRequestProperty("X-Goog-AuthUser", "0")
        c.setRequestProperty("X-YouTube-Client-Name", "67")
        c.setRequestProperty("X-YouTube-Client-Version", MUSIC_VERSION)
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        if (code !in 200..299) error("YouTube Music answered $code. Try signing in again.")
        return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
    }

    private const val MUSIC_VERSION = "1.20260101.01.00"
    private const val MUSIC_CONTEXT = "\"context\":{\"client\":{\"clientName\":\"WEB_REMIX\",\"clientVersion\":\"$MUSIC_VERSION\",\"hl\":\"en\"}}"

    private fun runText(o: JSONObject?): String =
        o?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")?.trim().orEmpty()

    private fun collectPlaylists(n: Any?, out: MutableList<MyPl>, seen: MutableSet<String>) {
        when (n) {
            is JSONObject -> {
                val two = n.optJSONObject("musicTwoRowItemRenderer")
                val row = n.optJSONObject("musicResponsiveListItemRenderer")
                val item = two ?: row
                if (item != null) {
                    val browse = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    val title = if (two != null) runText(two.optJSONObject("title"))
                    else runText(row?.optJSONArray("flexColumns")?.optJSONObject(0)
                        ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text"))
                    val id = browse.removePrefix("VL")
                    // "SE" = Episodes for later, "WL" = Watch later: not music, so never listed
                    if (browse.startsWith("VL") && id.isNotEmpty() && id != "SE" && id != "WL" && title.isNotEmpty() && seen.add(id)) {
                        out += MyPl("https://www.youtube.com/playlist?list=$id", title)
                    }
                }
                for (k in n.keys()) collectPlaylists(n.get(k), out, seen)
            }
            is JSONArray -> for (i in 0 until n.length()) collectPlaylists(n.get(i), out, seen)
        }
    }

    /** The playlists in the signed-in person's YouTube Music library (made or saved by them). Music only, no Watch later. */
    fun musicLibrary(ctx: Context): List<MyPl> {
        val rows = cookieRows(ctx)
        if (rows.isEmpty()) error("Not signed in.")
        val out = ArrayList<MyPl>()
        val seen = HashSet<String>()
        var root = musicBrowse(rows, "", "{$MUSIC_CONTEXT,\"browseId\":\"FEmusic_liked_playlists\"}")
        for (page in 0 until 8) {
            collectPlaylists(root, out, seen)
            val next = (findKey(root, "nextContinuationData") as? JSONObject)?.optString("continuation").orEmpty()
            if (next.isEmpty()) break
            root = musicBrowse(rows, "&ctoken=$next&continuation=$next&type=next", "{$MUSIC_CONTEXT}")
        }
        return out
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().remove("yt_name").apply()
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
    val msg = line.replace(Regex("^.*?ERROR:\\s*"), "").trim().ifEmpty { "failed" }
    return if (msg.contains("not a bot", true) || msg.contains("sign in", true)) "$msg (sign in to YouTube in Options)" else msg
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

    private val searchCache = object : LinkedHashMap<String, List<Hit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Hit>>?) = size > 30
    }

    /**
     * Search YouTube for songs (no download). Asks YouTube's search address directly, which answers in a fraction of a
     * second; starting yt-dlp (Python) for it takes several seconds, so that is only the fallback.
     */
    fun search(ctx: Context, q: String): List<Hit> {
        val key = q.trim().lowercase()
        synchronized(searchCache) { searchCache[key]?.let { return it } }
        val fast = try { webSearch(q) } catch (_: Exception) { emptyList() }
        val hits = fast.ifEmpty { slowSearch(ctx, q) }
        if (hits.isNotEmpty()) synchronized(searchCache) { searchCache[key] = hits }
        return hits
    }

    private fun slowSearch(ctx: Context, q: String): List<Hit> {
        init(ctx)
        val req = YoutubeDLRequest("ytsearch15:$q")
        req.addOption("--flat-playlist")
        req.addOption("--print", "%(id)s|||%(title)s|||%(uploader,channel|)s|||%(duration|0)s")
        val out = YoutubeDL.getInstance().execute(req, "cave-search").out
        return out.lines().map { it.split("|||") }
            .filter { it.size >= 4 && it[0].length in 8..15 }
            .map { Hit(it[0], it[1], it[2], it[3].toDoubleOrNull()?.toInt() ?: 0) }
    }

    private fun webSearch(q: String): List<Hit> {
        val c = URL("https://www.youtube.com/youtubei/v1/search?prettyPrint=false").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.connectTimeout = 6000
        c.readTimeout = 8000
        c.doOutput = true
        c.setRequestProperty("User-Agent", YtAuth.UA)
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9")
        c.setRequestProperty("X-YouTube-Client-Name", "1")
        c.setRequestProperty("X-YouTube-Client-Version", "2.20260101.00.00")
        val body = JSONObject()
            .put("context", JSONObject().put("client", JSONObject()
                .put("clientName", "WEB").put("clientVersion", "2.20260101.00.00").put("hl", "en").put("gl", "US")))
            .put("query", q)
            .put("params", "EgIQAQ==") // videos only
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode !in 200..299) error("search answered ${c.responseCode}")
        val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val out = ArrayList<Hit>()
        fun walk(n: Any?) {
            if (out.size >= 15) return
            when (n) {
                is JSONObject -> {
                    val v = n.optJSONObject("videoRenderer")
                    if (v != null) {
                        val id = v.optString("videoId")
                        val title = v.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                        val by = v.optJSONObject("ownerText")?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
                        val len = v.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                        // no length = a live stream: not a song
                        if (id.length == 11 && title.isNotEmpty() && len.isNotEmpty()) out += Hit(id, title, by, parseClock(len))
                    }
                    for (k in n.keys()) walk(n.get(k))
                }
                is JSONArray -> for (i in 0 until n.length()) walk(n.get(i))
            }
        }
        walk(root)
        return out
    }

    private fun parseClock(t: String): Int =
        t.split(":").mapNotNull { it.trim().toIntOrNull() }.fold(0) { acc, x -> acc * 60 + x }

    /** The signed-in user's music playlists: Liked music first, then every playlist in their YouTube Music library. */
    fun myPlaylists(ctx: Context): MyPlaylists {
        val base = listOf(MyPl("https://www.youtube.com/playlist?list=LM", "Liked music"))
        return try {
            val found = YtAuth.musicLibrary(ctx).filter { f -> base.none { it.url == f.url } }
            MyPlaylists(base + found, if (found.isEmpty()) "No playlists found in your YouTube Music library." else null)
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

/** True when [text] is, or contains, a web link rather than something to search for. */
fun looksLikeLink(text: String): Boolean =
    Regex("(https?://|www\\.|music\\.youtube\\.com/|youtube\\.com/|youtu\\.be/|open\\.spotify\\.com/|music\\.apple\\.com/)\\S+", RegexOption.IGNORE_CASE)
        .containsMatchIn(text.trim())

/**
 * Cleans up a pasted link. Any YouTube / YouTube Music link that carries a playlist id (share links, watch?v=..&list=..,
 * music.youtube.com/browse/VL..) becomes a plain youtube.com/playlist link, which yt-dlp reads reliably.
 */
fun normalizeLink(raw: String): String {
    // a share message can be "Check this out https://..." so take the link out of the text
    var u = (Regex("https?://\\S+").find(raw)?.value ?: raw).trim().trim('"', '\'', '<', '>', ' ')
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

private val HIDDEN = setOf("[Private video]", "[Deleted video]")

private fun listJson(ctx: Context, url: String, withAuth: Boolean): Resolved? {
    val req = YoutubeDLRequest(url)
    if (withAuth) req.auth(ctx)
    req.addOption("--flat-playlist")
    req.addOption("--yes-playlist")
    req.addOption("--ignore-errors")
    req.addOption("--dump-single-json")
    val out = YoutubeDL.getInstance().execute(req, "cave-list").out
    val at = out.indexOf('{')
    if (at < 0) return null
    val root = JSONObject(out.substring(at))
    val entries = root.optJSONArray("entries")
    val items = ArrayList<Item>()
    if (entries != null) {
        for (i in 0 until entries.length()) {
            val o = entries.optJSONObject(i) ?: continue
            val id = o.optString("id")
            val title = o.optString("title").ifBlank { id }
            if (id.length != 11 || title in HIDDEN) continue
            items += Item("yt:$id", "https://www.youtube.com/watch?v=$id", title)
        }
    } else if (root.optString("id").length == 11) { // a single video link
        val id = root.optString("id")
        items += Item("yt:$id", "https://www.youtube.com/watch?v=$id", root.optString("title").ifBlank { id })
    }
    if (items.isEmpty()) return null
    val name = root.optString("title").trim().takeIf { it.isNotEmpty() && it != "NA" } ?: "Singles"
    // the playlist's own picture: the biggest of the thumbnails YouTube lists for it (album art for album playlists)
    val thumbs = root.optJSONArray("thumbnails")
    var cover: String? = null
    var best = -1L
    if (thumbs != null) {
        for (i in 0 until thumbs.length()) {
            val t = thumbs.optJSONObject(i) ?: continue
            val u = t.optString("url")
            if (!u.startsWith("http")) continue
            val area = t.optLong("width", 0L) * t.optLong("height", 0L)
            if (area >= best) { best = area; cover = u }
        }
    }
    return Resolved(name, items, cover)
}

private fun listText(ctx: Context, url: String, withAuth: Boolean): Resolved? {
    val req = YoutubeDLRequest(url)
    if (withAuth) req.auth(ctx)
    req.addOption("--flat-playlist")
    req.addOption("--yes-playlist")
    req.addOption("--ignore-errors")
    req.addOption("--print", "%(playlist_title|)s|||%(id)s|||%(title)s")
    val out = YoutubeDL.getInstance().execute(req, "cave-list").out
    val rows = out.lines().filter { it.contains("|||") }.map { it.split("|||") }
        .filter { it.size >= 3 && it[1].length == 11 && it[2].trim() !in HIDDEN }
    if (rows.isEmpty()) return null
    val name = rows.map { it[0].trim() }.firstOrNull { it.isNotEmpty() && it != "NA" } ?: "Singles"
    return Resolved(name, rows.map { Item("yt:${it[1]}", "https://www.youtube.com/watch?v=${it[1]}", it[2]) })
}

/**
 * Reads a YouTube / YouTube Music playlist. A YouTube Music link can fail on one address and work on the other, and a
 * saved login helps private lists but can break public ones, so every combination is tried until one gives songs.
 */
private fun youtube(ctx: Context, input: String): Resolved {
    val id = Regex("[?&]list=([A-Za-z0-9_-]+)").find(input)?.groupValues?.get(1)
        ?: Regex("/browse/VL([A-Za-z0-9_-]+)").find(input)?.groupValues?.get(1)
    val urls = ArrayList<String>()
    if (id != null && !id.startsWith("RD")) {
        urls += "https://www.youtube.com/playlist?list=$id"
        urls += "https://music.youtube.com/playlist?list=$id"
    }
    if (input !in urls) urls += input
    val auths = if (YtAuth.signedIn(ctx)) listOf(true, false) else listOf(false)

    var failure: String? = null
    fun attempt(url: String, withAuth: Boolean, text: Boolean): Resolved? {
        if (CaveJob.cancelled) return null
        return try {
            if (text) listText(ctx, url, withAuth) else listJson(ctx, url, withAuth)
        } catch (e: Exception) {
            if (failure == null) failure = errText(e)
            val short = url.substringAfter("://").take(48)
            val who = if (withAuth) "(logged in) " else ""
            CaveState.addLog("playlist: $short $who- ${errText(e)}")
            null
        }
    }
    for (u in urls) for (a in auths) attempt(u, a, false)?.let { return it }
    // last try with the plain-text listing, in case the JSON one is what breaks
    for (a in auths) attempt(urls.first(), a, true)?.let { return it }

    if (CaveJob.cancelled) error("Stopped.")
    error(failure ?: "No songs found. Is the playlist public (or are you signed in)?")
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

/**
 * YouTube now makes yt-dlp solve a small JavaScript puzzle before it hands out audio, and answers "Sign in to confirm you're
 * not a bot" when it can't. Android has no JavaScript runtime, so the build adds QuickJS as libqjs.so (see the GitHub
 * workflow) and yt-dlp is pointed at it.
 */
object JsRuntime {
    private var checked = false
    private var found: String? = null

    @Synchronized
    fun path(ctx: Context): String? {
        if (checked) return found
        checked = true
        val f = File(ctx.applicationInfo.nativeLibraryDir, "libqjs.so")
        found = if (f.exists() && starts(f)) f.absolutePath else null
        CaveState.addLog(if (found != null) "JavaScript runtime: QuickJS ready" else "JavaScript runtime: not available on this phone")
        return found
    }

    /** True when the file can be started at all (a program built for the wrong system fails right here). */
    private fun starts(f: File): Boolean = try {
        val p = ProcessBuilder(f.absolutePath, "--help").redirectErrorStream(true).start()
        if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) p.destroy()
        true
    } catch (_: Exception) {
        false
    }
}

/**
 * One way of asking yt-dlp for a song. The best chance is the signed-in session together with the JavaScript runtime.
 * Without the runtime, a login cookie makes YouTube return no formats at all, so the older plans (no login, the
 * android_vr client) stay as fallbacks.
 */
private data class Plan(val auth: Boolean, val js: Boolean, val art: Boolean, val format: String?, val clients: String?)

private const val CLIENTS = "default,tv,web_safari,android_vr"

private fun plansFor(hasJs: Boolean, signed: Boolean, art: Boolean, preferred: String): List<Plan> = listOf(
    when {
        hasJs && signed -> Plan(true, true, art, preferred, null)
        hasJs -> Plan(false, true, art, preferred, CLIENTS)
        else -> Plan(false, false, art, preferred, "android_vr")
    },
    if (hasJs && signed) Plan(false, true, false, preferred, CLIENTS) else Plan(false, false, false, preferred, "android_vr"),
)

/** Finds the direct audio address of a video, for streaming. Blocking; uses the same two routes as downloading. */
fun resolveStream(ctx: Context, id: String): StreamUrl {
    CaveEngine.init(ctx)
    val js = JsRuntime.path(ctx)
    var last: Exception? = null
    for (p in plansFor(js != null, YtAuth.signedIn(ctx), false, "bestaudio[ext=m4a]/bestaudio/best")) {
        try {
            val r = YoutubeDLRequest("https://www.youtube.com/watch?v=$id")
            if (p.auth) r.auth(ctx)
            if (p.js && js != null) {
                r.addOption("--js-runtimes", "quickjs:$js")
                r.addOption("--remote-components", "ejs:github")
            }
            r.addOption("--no-playlist")
            r.addOption("-f", p.format ?: "bestaudio/best")
            if (p.clients != null) r.addOption("--extractor-args", "youtube:player_client=${p.clients}")
            r.addOption("--dump-json")
            val out = YoutubeDL.getInstance().execute(r, "stream-$id-${System.nanoTime()}").out
            val at = out.indexOf('{')
            if (at < 0) error("no answer")
            val o = JSONObject(out.substring(at))
            val url = o.optString("url")
            if (!url.startsWith("http")) error("no stream address")
            val h = o.optJSONObject("http_headers")
            val headers = HashMap<String, String>()
            if (h != null) for (k in h.keys()) headers[k] = h.optString(k)
            return StreamUrl(url, headers)
        } catch (e: Exception) {
            last = e
        }
    }
    throw last ?: IllegalStateException("no stream address")
}

private fun downloadTrack(ctx: Context, target: String, art: Boolean, m4a: Boolean, proc: String): File {
    val dir = File(ctx.cacheDir, "cave-dl/${System.nanoTime()}").apply { mkdirs() }
    val preferred = if (m4a) "bestaudio[ext=m4a]/bestaudio/best" else "bestaudio/best"
    val js = JsRuntime.path(ctx)
    val signed = YtAuth.signedIn(ctx)

    fun attempt(p: Plan) {
        val r = YoutubeDLRequest(target)
        if (p.auth) r.auth(ctx)
        if (p.js && js != null) {
            r.addOption("--js-runtimes", "quickjs:$js")
            r.addOption("--remote-components", "ejs:github")
        }
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

    // two tries only: the best route we have, then one different route without cover art (the usual thing that breaks)
    val plans = plansFor(js != null, signed, art, preferred)

    var last: Exception? = null
    for ((i, p) in plans.withIndex()) {
        if (CaveJob.cancelled) break
        dir.listFiles()?.forEach { it.delete() }
        try {
            attempt(p)
            last = null
            break
        } catch (e: Exception) {
            last = e
            if (i < plans.size - 1) CaveState.addLog("retry ${i + 2}/${plans.size}: ${errText(e)}")
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
        put(MediaStore.Audio.Media.RELATIVE_PATH, if (folder.isEmpty()) "Music/HoneyBeat" else "Music/HoneyBeat/$folder")
        put(MediaStore.Audio.Media.IS_PENDING, 1)
    }
    val resolver = ctx.contentResolver
    val uri = resolver.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values)
        ?: error("Couldn't create the file in Music/HoneyBeat")
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

    /**
     * Playlist cover from YouTube: the playlist's own picture when YouTube gives one, otherwise the first video's
     * thumbnail (which is what YouTube shows as the cover too). Only when the playlist has no cover yet.
     */
    fun fetchCover(ctx: Context, res: Resolved, key: String) {
        if (PlaylistCovers.has(ctx, key)) return
        val first = res.items.firstOrNull()?.key?.takeIf { it.startsWith("yt:") }?.removePrefix("yt:")
        val urls = listOfNotNull(
            res.cover,
            first?.let { "https://i.ytimg.com/vi/$it/maxresdefault.jpg" },
            first?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" },
        )
        for (u in urls) {
            try {
                val bytes = httpBytes(u)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                if (bmp.width < 120) continue
                PlaylistCovers.saveBitmap(ctx, key, bmp)
                return
            } catch (_: Exception) {
                // next candidate
            }
        }
    }

    /** Blocking. Clones a playlist (or syncs a known one) into Music/HoneyBeat/<playlist name>. */
    fun run(ctx: Context, rawUrl: String, auto: Boolean, refetch: Boolean, onProgress: (String) -> Unit) {
        val url = normalizeLink(rawUrl)
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
            download(ctx, res, url, folder, auto, refetch, onProgress)
        } catch (e: Exception) {
            if (!cancelled) fail(e)
        } finally {
            finish(ctx)
        }
    }

    /** Blocking. Downloads songs picked from the search into Music/HoneyBeat itself. */
    fun runItems(ctx: Context, items: List<Item>, onProgress: (String) -> Unit) {
        reset()
        try {
            CaveEngine.init(ctx)
            download(ctx, Resolved("Songs", items), null, "", false, false, onProgress)
        } catch (e: Exception) {
            if (!cancelled) fail(e)
        } finally {
            finish(ctx)
        }
    }

    private fun download(ctx: Context, res: Resolved, recentUrl: String?, folder: String, auto: Boolean, refetch: Boolean, onProgress: (String) -> Unit) {
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val art = prefs.getBoolean("cave_art", true)
        val par = prefs.getInt("cave_par", 4) // 0 = all
        val m4a = prefs.getString("cave_quality", "mp3") == "m4a"

        val prev = recentUrl?.let { u -> CaveStore.load(prefs).firstOrNull { it.url == u } }
        val keys = (prev?.keys ?: emptyList()).toMutableList()
        val autoFlag = prev?.auto ?: auto
        // Refetch: ignore what the app remembers and compare with the songs that are really in the folder. Songs that are
        // missing are downloaded again, and so are songs that are damaged ("Unknown artist", no cover): the new file
        // replaces the old one. Songs that are fine are left alone, so nothing is downloaded twice.
        fun norm(t: String) = t.lowercase().filter { it.isLetterOrDigit() }
        val repairs = HashMap<String, Long>() // item key -> id of the damaged song it replaces
        if (refetch) {
            keys.clear()
            CaveState.ui { CaveState.status = "Checking the songs in this playlist..." }
            val local = if (folder.isNotEmpty()) loadSongs(ctx).filter { it.folder == folder } else emptyList()
            val byName = HashMap<String, Song>()
            local.forEach { song ->
                byName[norm(song.title)] = song
                byName[norm(File(song.path).nameWithoutExtension)] = song
            }
            for (item in res.items) {
                val song = byName[norm(item.label)] ?: continue
                val damaged = song.artist == "Unknown artist" || (art && !Covers.hasEmbedded(ctx, song.id))
                if (damaged) repairs[item.key] = song.id else keys += item.key
            }
            if (repairs.isNotEmpty()) CaveState.addLog("${repairs.size} damaged song(s) to fix (no artist or no cover)")
        }
        val known = keys.toSet()
        val todo = res.items.filter { it.key !in known }
        CaveState.addLog("${res.name}: ${res.items.size} songs, ${todo.size} " + (if (refetch) "to download or fix" else "new"))

        fun saveRecent() {
            if (recentUrl == null) return
            val list = CaveStore.load(prefs).filter { it.url != recentUrl }
            val updated = listOf(Recent(recentUrl, res.name, keys.toList(), autoFlag)) + list
            CaveStore.save(prefs, updated)
            CaveState.ui { CaveState.recents = updated }
        }
        saveRecent() // remembers the playlist (and its auto-update flag) even if nothing is new

        // the link file in Music/HoneyBeat/<playlist>: lets a reinstalled app pick this playlist up again
        fun writeLink() {
            if (recentUrl != null && folder.isNotEmpty()) HoneyFiles.save(ctx, Recent(recentUrl, res.name, keys.toList(), autoFlag))
        }
        writeLink()

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
                    val old = repairs[item.key]
                    if (old != null) {
                        // the new file is ready; now the damaged one goes (if Android lets us)
                        val gone = try { ctx.contentResolver.delete(songUri(old), null, null); true } catch (_: Exception) { false }
                        if (!gone) {
                            file.parentFile?.deleteRecursively()
                            error("Android won't let HoneyBeat replace this file. Delete the song and refetch.")
                        }
                    }
                    saveToMusic(ctx, file, folder)
                    file.parentFile?.deleteRecursively()
                    synchronized(lock) {
                        keys += item.key
                        saveRecent()
                    }
                    okC.incrementAndGet()
                    CaveState.addLog((if (repairs.containsKey(item.key)) "fixed: " else "ok: ") + item.label)
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
        writeLink() // final list of downloaded songs

        val ok = okC.get()
        val failed = failC.get()
        val end = if (cancelled) "Stopped. $ok new." else "Done. $ok new" + if (failed > 0) ", $failed failed." else "."
        CaveState.ui { CaveState.done = todo.size; CaveState.current = ""; CaveState.status = end }
        if (ok > 0 && !cancelled) {
            Notifier.post(ctx, (recentUrl ?: "songs").hashCode(), res.name.ifEmpty { "Songs" }, "Downloaded $ok new song" + if (ok == 1) "" else "s")
        }
    }
}
