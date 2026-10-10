package com.neonbear.honeybeat

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val path: String,
    /** First folder under Music/Cave, or "" for songs directly in Music/Cave. */
    val folder: String,
    /** MediaStore track number (disc * 1000 + track), 0 when the file has none. */
    val track: Int = 0,
) {
    val uri: Uri get() = songUri(id)
}

/** A Cave playlist: one folder inside Music/Cave. The key "" is the built-in All music list. */
data class Folder(val key: String, val name: String, val songs: List<Song>)

fun songUri(id: Long): Uri =
    ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)

fun Song.toItem(): MediaItem = MediaItem.Builder()
    .setMediaId(id.toString())
    .setUri(uri)
    .setMediaMetadata(
        MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(album)
            .build()
    )
    .build()

private const val CAVE = "/Music/Cave/"

@Suppress("DEPRECATION")
fun loadSongs(ctx: Context): List<Song> {
    val out = ArrayList<Song>()
    val a = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val proj = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.DURATION,
        MediaStore.Audio.Media.DATA,
        MediaStore.Audio.Media.TRACK,
    )
    val sel = "${MediaStore.Audio.Media.DATA} LIKE '%$CAVE%' AND ${MediaStore.Audio.Media.DURATION} > 0"
    ctx.contentResolver.query(a, proj, sel, null, "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC")?.use { c ->
        val iId = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val iArtist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val iAlbum = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val iDur = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        val iData = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
        val iTrack = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
        while (c.moveToNext()) {
            val path = c.getString(iData) ?: ""
            val at = path.indexOf(CAVE, ignoreCase = true)
            val rel = if (at >= 0) path.substring(at + CAVE.length) else ""
            val folder = if (rel.contains('/')) rel.substringBefore('/') else ""
            out += Song(
                id = c.getLong(iId),
                title = c.getString(iTitle) ?: "Untitled",
                artist = c.getString(iArtist)?.takeIf { it != "<unknown>" } ?: "Unknown artist",
                album = c.getString(iAlbum)?.takeIf { it != "<unknown>" } ?: "",
                durationMs = c.getLong(iDur),
                path = path,
                folder = folder,
                track = c.getInt(iTrack),
            )
        }
    }
    return out
}

/** How a song list is ordered. Album and Artist also group the list under headers. */
enum class SortMode(val label: String) { Title("Title"), Artist("Artist"), Album("Album") }

fun sortModeOf(name: String): SortMode = SortMode.values().firstOrNull { it.name == name } ?: SortMode.Title

private val NOCASE = String.CASE_INSENSITIVE_ORDER

fun sortSongs(list: List<Song>, mode: SortMode): List<Song> = when (mode) {
    SortMode.Title -> list.sortedWith(compareBy<Song, String>(NOCASE) { it.title })
    SortMode.Artist -> list.sortedWith(
        compareBy<Song, String>(NOCASE) { it.artist }
            .thenBy(NOCASE) { it.album }
            .thenBy { if (it.track <= 0) Int.MAX_VALUE else it.track }
            .thenBy(NOCASE) { it.title }
    )
    // albums A-Z (songs without an album last), each album in track order
    SortMode.Album -> list.sortedWith(
        compareBy<Song> { it.album.isEmpty() }
            .thenBy(NOCASE) { it.album }
            .thenBy { if (it.track <= 0) Int.MAX_VALUE else it.track }
            .thenBy(NOCASE) { it.title }
    )
}

/** The header a song sits under for this sort, or null when the list has no groups. */
fun groupOf(s: Song, mode: SortMode): String? = when (mode) {
    SortMode.Title -> null
    SortMode.Artist -> s.artist
    SortMode.Album -> s.album.ifEmpty { "No album" }
}

/** All music first, then one playlist per folder in Music/Cave. */
fun buildFolders(songs: List<Song>): List<Folder> {
    val byFolder = songs.filter { it.folder.isNotEmpty() }.groupBy { it.folder }
    val sorted = byFolder.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
    return listOf(Folder("", "All music", songs)) + sorted.map { Folder(it, it, byFolder.getValue(it)) }
}

/** Cover art: the full-size embedded picture first (sharp), MediaStore's thumbnail as a fallback. */
object Covers {
    private val cache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missing = HashSet<String>()

    fun peek(id: Long, px: Int): Bitmap? = cache.get("$id:$px")

    private fun embedded(ctx: Context, id: Long, px: Int): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(ctx, songUri(id))
            val bytes = r.embeddedPicture ?: return null
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
            var s = 1
            while (o.outWidth / (s * 2) >= px && o.outHeight / (s * 2) >= px) s *= 2
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
        } catch (_: Exception) {
            null
        } finally {
            try { r.release() } catch (_: Exception) {}
        }
    }

    suspend fun load(ctx: Context, id: Long, px: Int): Bitmap? {
        if (id < 0) return null
        val key = "$id:$px"
        cache.get(key)?.let { return it }
        if (key in missing) return null
        val b = withContext(Dispatchers.IO) {
            embedded(ctx, id, px) ?: try {
                if (Build.VERSION.SDK_INT >= 29) ctx.contentResolver.loadThumbnail(songUri(id), android.util.Size(px, px), null) else null
            } catch (_: Exception) {
                null
            }
        }
        if (b != null) cache.put(key, b) else missing.add(key)
        return b
    }
}

fun fmt(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** Custom playlist covers picked by the user, stored as small square JPEGs in the app's files. */
object PlaylistCovers {
    var version by mutableIntStateOf(0)

    private fun file(ctx: Context, key: String): File {
        val dir = File(ctx.filesDir, "playlist-covers").apply { mkdirs() }
        return File(dir, (if (key.isEmpty()) "all" else key.hashCode().toString(16)) + ".jpg")
    }

    fun has(ctx: Context, key: String) = file(ctx, key).exists()

    fun load(ctx: Context, key: String): Bitmap? =
        file(ctx, key).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }

    fun save(ctx: Context, key: String, uri: Uri): Boolean = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        while (bounds.outWidth / sample > 1600 || bounds.outHeight / sample > 1600) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("unreadable image")
        saveBitmap(ctx, key, bmp)
    } catch (_: Exception) {
        false
    }

    fun saveBitmap(ctx: Context, key: String, bmp: Bitmap): Boolean = try {
        val side = minOf(bmp.width, bmp.height)
        val square = Bitmap.createBitmap(bmp, (bmp.width - side) / 2, (bmp.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(square, 800, 800, true)
        file(ctx, key).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        version++
        true
    } catch (_: Exception) {
        false
    }

    fun remove(ctx: Context, key: String) {
        file(ctx, key).delete()
        version++
    }
}

/** Small cache for the search result thumbnails. */
object NetImages {
    private val cache = LruCache<String, Bitmap>(80)
    private val failed = HashSet<String>()

    fun peek(url: String): Bitmap? = cache.get(url)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        if (url in failed) return null
        val b = withContext(Dispatchers.IO) {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 10000
                c.readTimeout = 10000
                c.inputStream.use { BitmapFactory.decodeStream(it) }
            } catch (_: Exception) {
                null
            }
        }
        if (b != null) cache.put(url, b) else failed.add(url)
        return b
    }
}
