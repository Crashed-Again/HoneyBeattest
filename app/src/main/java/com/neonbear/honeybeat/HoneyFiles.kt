package com.neonbear.honeybeat

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore

/** What a restore found and did, so the app can switch the watcher on again. */
data class Restored(val added: Int, val auto: Boolean)

/**
 * A small link file kept next to every downloaded playlist: Music/HoneyBeat/<playlist>/HoneyBeat.m3u.
 * It holds the playlist link, its name, the auto-update flag and the songs already downloaded. It is a valid (empty)
 * .m3u playlist, so Android accepts it in the Music folder. If HoneyBeat is uninstalled and installed again, the files
 * are read back, the playlists reappear and only songs that are really new get downloaded.
 */
object HoneyFiles {
    private const val NAME = "HoneyBeat.m3u"
    private const val MIME = "audio/x-mpegurl"
    private const val DOC_DIR = "Documents/HoneyBeat/"
    private const val DOC_MIME = "text/plain"

    private class Target(val path: String, val name: String, val mime: String)

    /** Music is the home of the file. Documents is the fallback in case a phone refuses an .m3u in Music. */
    private fun targets(folder: String) = listOf(
        Target("Music/HoneyBeat/$folder/", NAME, MIME),
        Target(DOC_DIR, "$folder - HoneyBeat.txt", DOC_MIME),
    )

    private fun oneLine(s: String) = s.replace('\n', ' ').replace('\r', ' ').trim()

    fun render(r: Recent): String = buildString {
        append("#EXTM3U\n")
        append("#PLAYLIST:").append(oneLine(r.name)).append('\n')
        append("# HoneyBeat playlist link. Keep this file: if HoneyBeat is installed again it reads it\n")
        append("# and keeps checking this playlist for new songs.\n")
        append("#HONEYBEAT-URL:").append(oneLine(r.url)).append('\n')
        append("#HONEYBEAT-NAME:").append(oneLine(r.name)).append('\n')
        append("#HONEYBEAT-AUTO:").append(if (r.auto) "1" else "0").append('\n')
        r.keys.forEach { append("#HONEYBEAT-SONG:").append(oneLine(it)).append('\n') }
    }

    fun parse(text: String): Recent? {
        var url = ""
        var name = ""
        var auto = false
        val keys = ArrayList<String>()
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            when {
                l.startsWith("#HONEYBEAT-URL:") -> url = l.removePrefix("#HONEYBEAT-URL:").trim()
                l.startsWith("#HONEYBEAT-NAME:") -> name = l.removePrefix("#HONEYBEAT-NAME:").trim()
                l.startsWith("#HONEYBEAT-AUTO:") -> auto = l.removePrefix("#HONEYBEAT-AUTO:").trim() == "1"
                l.startsWith("#HONEYBEAT-SONG:") -> l.removePrefix("#HONEYBEAT-SONG:").trim().takeIf { it.isNotEmpty() }?.let { keys += it }
            }
        }
        if (url.isEmpty() || name.isEmpty()) return null
        return Recent(url, name, keys, auto)
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
            put(MediaStore.MediaColumns.MIME_TYPE, t.mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, t.path)
        }
        val r = ctx.contentResolver
        return try { r.insert(filesUri(), v) } catch (_: Exception) { null }
            ?: try { r.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v) } catch (_: Exception) { null }
    }

    /** Writes (or rewrites) the link file of a downloaded playlist. Safe to call from any thread, never throws. */
    fun save(ctx: Context, r: Recent) {
        val text = render(r).toByteArray()
        for (t in targets(safeName(r.name))) {
            try {
                val uri = find(ctx, t) ?: create(ctx, t) ?: continue
                ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(text) } ?: continue
                return
            } catch (_: Exception) {
                // try the next place
            }
        }
        CaveState.addLog("couldn't save the playlist link file for ${r.name}")
    }

    /** Removes the link file of a playlist (when the playlist is deleted), so it does not come back after a reinstall. */
    fun remove(ctx: Context, folder: String) {
        for (t in targets(folder)) {
            try { find(ctx, t)?.let { ctx.contentResolver.delete(it, null, null) } } catch (_: Exception) {}
        }
    }

    /** All link files on the phone. */
    private fun readAll(ctx: Context): List<Recent> {
        val out = ArrayList<Recent>()
        val sel = "(${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} = ?) OR " +
            "(${MediaStore.Files.FileColumns.RELATIVE_PATH} = ? AND ${MediaStore.Files.FileColumns.DISPLAY_NAME} LIKE ?)"
        val args = arrayOf("Music/HoneyBeat/%", NAME, DOC_DIR, "% - HoneyBeat.txt")
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
     * Brings playlists back from the link files. Playlists the app already knows are left alone; a playlist that is only
     * in a file (fresh install) is added with the songs it had already downloaded.
     */
    fun restoreInto(ctx: Context): Restored {
        val prefs = ctx.getSharedPreferences("honeybeat", Context.MODE_PRIVATE)
        val known = CaveStore.load(prefs)
        val fresh = LinkedHashMap<String, Recent>()
        for (r in readAll(ctx)) {
            if (known.any { it.url == r.url }) continue
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
