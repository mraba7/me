package com.iptv.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.PlayerView

/**
 * Manages two independent players:
 *  - videoPlayer: shows the picture (HDR-capable). Muted when a separate audio source is active.
 *  - audioPlayer: plays sound from a DIFFERENT channel (the signature feature).
 */
@UnstableApi
class PlayerEngine(private val context: Context) {

    private var videoPlayer: ExoPlayer? = null
    private var audioPlayer: ExoPlayer? = null
    private var volume = 0.8f

    var onVideoError: ((String) -> Unit)? = null
    var onVideoReady: (() -> Unit)? = null
    var onVideoBuffering: ((Boolean) -> Unit)? = null

    private fun httpFactory() = DefaultHttpDataSource.Factory()
        .setUserAgent("IPTVPlayer/1.0 (Android)")
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(20000)
        .setReadTimeoutMs(60000)
        .setKeepPostFor302Redirects(true)

    /** NextLib renderers factory: DefaultRenderersFactory + FFmpeg (AC3/EAC3/DTS/MP2/TrueHD...). HDR + hardware decoding kept, software FFmpeg as fallback. */
    private fun renderersFactory() = io.github.anilbeesetti.nextlib.media3ext.ffdecoder.NextRenderersFactory(context).apply {
        setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        setEnableDecoderFallback(true)
    }

    private fun buildSource(url: String): MediaSource {
        val f = httpFactory()
        val item = MediaItem.fromUri(url)
        return if (url.contains(".m3u8")) {
            HlsMediaSource.Factory(f).setAllowChunklessPreparation(true).createMediaSource(item)
        } else {
            // .ts and other progressive streams
            ProgressiveMediaSource.Factory(f).createMediaSource(item)
        }
    }

    private fun newPlayer(): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowAudioMixedMimeTypeAdaptiveness(true)
                    .setTunnelingEnabled(true)   // better A/V sync + HDR passthrough on TV
            )
        }
        // Larger buffers = smoother live TV
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 60000, 2500, 5000)
            .build()

        return ExoPlayer.Builder(context, renderersFactory())
            .setTrackSelector(trackSelector)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory()))
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    /* handleAudioFocus = */ true
                )
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    fun attachTo(view: PlayerView) {
        // PlayerView is set when we (re)create the video player
        currentView = view
    }
    private var currentView: PlayerView? = null

    /** Play video (with own audio if [muted]==false). */
    fun playVideo(url: String, muted: Boolean) {
        videoPlayer?.release()
        val p = newPlayer()
        videoPlayer = p
        currentView?.player = p
        p.volume = if (muted) 0f else volume
        p.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> onVideoBuffering?.invoke(true)
                    Player.STATE_READY -> { onVideoBuffering?.invoke(false); onVideoReady?.invoke() }
                }
            }
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                onVideoError?.invoke(error.errorCodeName)
            }
        })
        p.setMediaSource(buildSource(url))
        p.prepare()
        p.playWhenReady = true
    }

    /** Play a separate audio source. */
    fun playAudio(url: String) {
        audioPlayer?.release()
        val p = newPlayer()
        audioPlayer = p
        p.volume = volume
        p.setMediaSource(buildSource(url))
        p.prepare()
        p.playWhenReady = true
    }

    fun stopAudio() {
        audioPlayer?.release(); audioPlayer = null
        videoPlayer?.volume = volume   // restore video sound
    }

    /** Audio sync: positive = audio ahead (delay it), negative = audio behind (advance it). In ms. */
    private var syncOffsetMs = 0L
    fun getSyncOffsetMs() = syncOffsetMs

    /** Apply an audio/video sync offset by seeking the audio player relative to the video clock. */
    fun applySyncOffset(offsetMs: Long) {
        syncOffsetMs = offsetMs.coerceIn(-5000L, 5000L)
        val a = audioPlayer ?: return
        val v = videoPlayer ?: return
        // Target audio position = video position - offset
        val target = (v.currentPosition - syncOffsetMs).coerceAtLeast(0L)
        a.seekTo(target)
    }

    /** Nudge the offset by a delta (ms) and re-apply. Returns the new offset. */
    fun nudgeSync(deltaMs: Long): Long {
        applySyncOffset(syncOffsetMs + deltaMs)
        return syncOffsetMs
    }

    fun resetSync(): Long { applySyncOffset(0L); return 0L }

    fun setVolume(v: Float) {
        volume = v.coerceIn(0f, 1f)
        if (audioPlayer != null) audioPlayer?.volume = volume
        else videoPlayer?.volume = volume
    }

    fun pause() { videoPlayer?.playWhenReady = false; audioPlayer?.playWhenReady = false }
    fun resume() { videoPlayer?.playWhenReady = true; audioPlayer?.playWhenReady = true }

    fun stopAll() {
        videoPlayer?.release(); videoPlayer = null
        audioPlayer?.release(); audioPlayer = null
        currentView?.player = null
    }

    fun release() = stopAll()
}
