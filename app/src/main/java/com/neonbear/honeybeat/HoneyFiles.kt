package com.neonbear.honeybeat

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject

/** What a restore found and did, so the app can switch the watcher on again. */
data class Restored(val added: Int, val auto: Boolean)

/**
 * A small .json file kept for every downloaded playlist. It holds the playlist link, its name, the auto-update flag and
 * the songs already downloaded. If HoneyBeat is uninstalled and installed again, the files are read back: the playlists
 * reappear and only songs that are really new get downloaded.
 *
 * Android only lets an app put audio files into Music, so a .json can only go there if the phone allows it. The same file
 * is therefore also written to Documents/HoneyBeat/<playlist>.json, which every phone accepts and every file manager shows.
 */
object HoneyFiles {
    private const val JSON_MIME = "application/json"
    private const val DOC_DIR = "Documents/HoneyBeat/"
    private const val NAME = "HoneyBeat.json"

    private class Target(val path: String, val name: String)

    private fun targets(folder: String) = listOf(
        Target("Documents/HoneyBeat/", "$folder.json"),
        Target("Music/HoneyBeat/$folder/", NAME),
    )

    fun render(r: Recent): String = JSONObject()
        .put("app", "HoneyBeat")
        .put("note", "Playlist link file. Keep it: if HoneyBeat is installed again it reads this and keeps checking the playlist for new songs.")
        .put("name", r.name)
        .put("url", r.url)
        .put("auto", r.auto)
        .put("songs", JSONArray(r.keys))
        .toString(2)

    fun parse(text: String): Recent? {
        try {
            val o = JSONObject(text.trim())
            val url = o.optString("url")
            val name = o.optString("name")
            if (url.isEmpty() || name.isEmpty()) return null
            val a = o.optJSONArray("songs")
            val keys = if (a == null) emptyList() else (0 until a.length()).map { a.getString(it) }
            return Recent(url, name, keys, o.optBoolean("auto", false))
        } catch (_: Exception) {
            return null
        }
    }

    private fun filesUri(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun find(ctx: Context, t: Target): Uri? {
        ctx.contentResolver.query(
            filesUri(), arrayOf(MediaStore.Files.FileColumns._ID),
            "${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?",
            arrayOf(t.path, t.name), null,
        )?.use { c -> if (c.moveToFirst()) return ContentUris.withAppendedId(filesUri(), c.getLong(0)) }
        return null
    }

    private fun create(ctx: Context, t: Target): Uri? {
        val v = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, t.name)
            put(MediaStore.MediaColumns.MIME_TYPE, JSON_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.path)
        }
        return try { ctx.contentResolver.insert(filesUri(), v) } catch (_: Exception) { null }
    }

    /** Writes (or rewrites) the link file of a downloaded playlist. Safe to call from any thread, never throws. */
    fun save(ctx: Context, r: Recent) {
        val text = render(r).toByteArray()
        var saved = 0
        for (t in targets(safeName(r.name))) {
            try {
                val uri = find(ctx, t) ?: create(ctx, t) ?: continue
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text) } ?: continue
                saved++
            } catch (_: Exception) {
                // this place refused it (Music usually does); the other one still has the file
            }
        }
        if (saved == 0) CaveState.addLog("couldn't save the playlist link file for ${r.name}")
    }

    /** Removes the link files of a playlist (when the playlist is deleted), so it does not come back after a reinstall. */
    fun remove(ctx: Context, folder: String) {
        for (t in targets(folder)) {
            try { find(ctx, t)?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        }
    }

    /** All link files on the phone. */
    private fun readAll(ctx: Context): List<Recent> {
        val out = ArrayList<Recent>()
        val p = MediaStore.Files.FileColumns.RELATIVE_PATH
        val n = MediaStore.Files.FileColumns.DISPLAY_NAME
        val sel = "($p LIKE ? AND $n = ?) OR ($p = ? AND $n LIKE ?)"
        val args = arrayOf("Music/HoneyBeat/%", NAME, DOC_DIR, "%.json")
        try {
            ctx.contentResolver.query(filesUri(), arrayOf(MediaStore.Files.FileColumns._ID), sel, args, null)?.use { c ->
                while (c.moveToNext()) {
                    val uri = ContentUris.withAppendedId(filesUri(), c.getLong(0))
                    try {
                        val text = ctx.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: continue
                        parse(text)?.let { out += it }
                    } catch (_: Exception) {
                        // unreadable file: skip it
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /**
     * Brings playlists back from the link files. Playlists the app already knows are left alone, and so are playlists
     * whose songs are gone (deleted on purpose). A playlist that is only in a file (fresh install) is added with the
     * songs it had already downloaded.
     */
    fun restoreInto(ctx: Context): Restored {
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val known = CaveStore.load(prefs)
        val folders = loadSongs(ctx).map { it.folder }.toSet()
        val fresh = LinkedHashMap<String, Recent>()
        for (r in readAll(ctx)) {
            if (known.any { it.url == r.url }) continue
            if (safeName(r.name) !in folders) continue
            val old = fresh[r.url]
            // two files for one link (Music and Documents): keep the one that knows more songs
            if (old == null || r.keys.size > old.keys.size) fresh[r.url] = r
        }
        if (fresh.isEmpty()) return Restored(0, false)
        val merged = known + fresh.values
        CaveStore.save(prefs, merged)
        CaveState.ui { CaveState.recents = merged }
        val auto = fresh.values.any { it.auto }
        if (auto) prefs.edit().putBoolean("watch_on", true).apply()
        CaveState.addLog("restored ${fresh.size} playlist(s) from their HoneyBeat link files")
        return Restored(fresh.size, auto)
    }
}
