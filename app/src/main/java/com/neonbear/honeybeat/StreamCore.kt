package com.neonbear.honeybeat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** One song of a streaming playlist. Nothing is downloaded: the song is looked up on YouTube when it is played. */
data class StreamTrack(val id: String, val title: String)

/** A playlist that is streamed instead of downloaded. */
data class StreamList(val url: String, val name: String, val tracks: List<StreamTrack>) {
    /** Library key. The prefix keeps it apart from downloaded playlists with the same name. */
    val key: String get() = "stream:" + safeName(name)
}

object StreamStore {
    private const val KEY = "stream_lists"

    fun load(p: android.content.SharedPreferences): List<StreamList> = try {
        val arr = JSONArray(p.getString(KEY, "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val t = o.getJSONArray("tracks")
            StreamList(
                o.getString("url"), o.getString("name"),
                (0 until t.length()).map { j -> t.getJSONObject(j).let { StreamTrack(it.getString("id"), it.getString("title")) } },
            )
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun save(p: android.content.SharedPreferences, list: List<StreamList>) {
        val arr = JSONArray()
        list.forEach { l ->
            val t = JSONArray()
            l.tracks.forEach { t.put(JSONObject().put("id", it.id).put("title", it.title)) }
            arr.put(JSONObject().put("url", l.url).put("name", l.name).put("tracks", t))
        }
        p.edit().putString(KEY, arr.toString()).apply()
    }
}

/** Direct audio address of a YouTube video, plus the request headers it needs. */
data class StreamUrl(val url: String, val headers: Map<String, String>, val at: Long = System.currentTimeMillis())

/** Remembers looked-up addresses for a few hours (they expire), so a song that was prepared starts right away. */
object StreamUrls {
    private val cache = ConcurrentHashMap<String, StreamUrl>()

    fun get(ctx: Context, id: String): StreamUrl {
        cache[id]?.takeIf { System.currentTimeMillis() - it.at < 4 * 3600 * 1000L }?.let { return it }
        val r = resolveStream(ctx, id)
        cache[id] = r
        return r
    }
}

object StreamJob {
    /** Blocking. Reads a YouTube playlist and saves it as a streaming playlist, with its cover. Returns a short message. */
    fun import(ctx: Context, rawUrl: String): String {
        val url = normalizeLink(rawUrl)
        if (!CaveState.running) CaveJob.cancelled = false
        CaveEngine.init(ctx)
        val res = resolve(ctx, url)
        val tracks = res.items.filter { it.key.startsWith("yt:") }.map { StreamTrack(it.key.removePrefix("yt:"), it.label) }
        if (tracks.isEmpty()) error("Only YouTube and YouTube Music playlists can be streamed.")
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val list = StreamList(url, res.name, tracks)
        val old = StreamStore.load(prefs)
        val all = if (old.any { it.url == url }) old.map { if (it.url == url) list else it } else old + list
        StreamStore.save(prefs, all)
        CaveState.ui { CaveState.streams = all }
        CaveJob.fetchCover(ctx, res, list.key)
        CaveState.addLog("stream: ${res.name}, ${tracks.size} songs")
        return "${res.name}: ${tracks.size} songs ready to stream."
    }

    /** Runs [import] in the background and reports back on the main thread. */
    fun importAsync(ctx: Context, url: String, onDone: (String) -> Unit) {
        val app = ctx.applicationContext
        Thread {
            val msg = try { import(app, url) } catch (e: Exception) { errText(e) }
            CaveState.ui { onDone(msg) }
        }.start()
    }

    fun remove(ctx: Context, url: String) {
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val all = StreamStore.load(prefs).filter { it.url != url }
        StreamStore.save(prefs, all)
        CaveState.ui { CaveState.streams = all }
    }
}
