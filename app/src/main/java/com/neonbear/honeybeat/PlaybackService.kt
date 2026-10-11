package com.neonbear.honeybeat

import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.IOException

/** The address a streamed song is stored under: https://www.youtube.com/watch?v=<id>. It is swapped for the real audio address when played. */
private fun streamIdOf(uri: Uri?): String? =
    if (uri != null && uri.host == "www.youtube.com" && uri.path == "/watch") uri.getQueryParameter("v") else null

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        // Streamed songs: the player is given a YouTube watch address; right before loading, it is turned into the
        // direct audio address (looking it up takes a few seconds, so the next song is looked up ahead of time).
        val resolving = ResolvingDataSource.Factory(DefaultDataSource.Factory(this), object : ResolvingDataSource.Resolver {
            override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
                val id = streamIdOf(dataSpec.uri) ?: return dataSpec
                try {
                    val r = StreamUrls.get(applicationContext, id)
                    return dataSpec.buildUpon().setUri(Uri.parse(r.url)).setHttpRequestHeaders(r.headers).build()
                } catch (e: Exception) {
                    throw IOException("Couldn't stream this song: ${errText(e)}", e)
                }
            }
        })
        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(resolving))
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { prepareNext(player) }
        })
        session = MediaSession.Builder(this, player).build()
    }

    private fun prepareNext(p: Player) {
        if (!p.hasNextMediaItem()) return
        val id = streamIdOf(p.getMediaItemAt(p.nextMediaItemIndex).localConfiguration?.uri) ?: return
        val app = applicationContext
        Thread { try { StreamUrls.get(app, id) } catch (_: Exception) {} }.start()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }
}
