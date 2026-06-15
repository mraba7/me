package com.iptv.player

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

@SuppressLint("SetJavaScriptEnabled")
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var playerView: PlayerView
    private lateinit var rootLayout: FrameLayout

    // Two independent players → THE key feature: video from one channel, audio from another
    private var videoPlayer: ExoPlayer? = null   // shows picture (its own audio muted when separate)
    private var audioPlayer: ExoPlayer? = null    // plays sound only

    private val scope = CoroutineScope(Dispatchers.Main)

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        rootLayout = FrameLayout(this)
        setContentView(rootLayout)

        // ── Native video surface (behind the WebView) ──
        playerView = PlayerView(this).apply {
            useController = false
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(playerView)

        // ── WebView UI on top (transparent where video shows) ──
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            setBackgroundColor(0xFF0A0A0A.toInt())
            addJavascriptInterface(NativeBridge(), "Android")
            webViewClient = WebViewClient()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(webView)

        webView.loadUrl("file:///android_asset/index.html")
    }

    /* ════════════════════════════════════════════════════════════
       JavaScript ↔ Native bridge
       The WebView calls these. All run OUTSIDE the browser sandbox,
       so there is NO CORS restriction here.
       ════════════════════════════════════════════════════════════ */
    inner class NativeBridge {

        /** Generic HTTP GET — returns body as string. CORS-free. */
        @JavascriptInterface
        fun httpGet(url: String, callbackId: String) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    try {
                        val req = Request.Builder()
                            .url(url)
                            .header("User-Agent", "IPTVPlayer/1.0 (Android)")
                            .build()
                        http.newCall(req).execute().use { resp ->
                            if (!resp.isSuccessful) return@withContext err("HTTP ${resp.code}")
                            val body = resp.body?.string() ?: ""
                            ok(body)
                        }
                    } catch (e: Exception) {
                        err(e.message ?: "network error")
                    }
                }
                deliver(callbackId, result)
            }
        }

        /** Play video (with its own audio) OR video-only when separate audio is set. */
        @JavascriptInterface
        fun playVideo(url: String, muted: Boolean) {
            runOnUiThread {
                showVideoSurface(true)
                videoPlayer?.release()
                videoPlayer = buildPlayer(url).also { p ->
                    p.volume = if (muted) 0f else 1f
                    playerView.player = p
                    p.prepare(); p.playWhenReady = true
                }
            }
        }

        /** Play a SEPARATE audio source (the signature feature). */
        @JavascriptInterface
        fun playAudio(url: String) {
            runOnUiThread {
                audioPlayer?.release()
                audioPlayer = buildPlayer(url).also { p ->
                    p.volume = 1f
                    p.prepare(); p.playWhenReady = true
                }
            }
        }

        /** Stop the separate audio player and unmute the video again. */
        @JavascriptInterface
        fun stopAudio() {
            runOnUiThread {
                audioPlayer?.release(); audioPlayer = null
                videoPlayer?.volume = 1f
            }
        }

        @JavascriptInterface
        fun setVolume(percent: Int) {
            runOnUiThread {
                val v = (percent.coerceIn(0, 100)) / 100f
                // route volume to whichever player produces the sound
                if (audioPlayer != null) audioPlayer?.volume = v
                else videoPlayer?.volume = v
            }
        }

        @JavascriptInterface
        fun stopAll() {
            runOnUiThread {
                videoPlayer?.release(); videoPlayer = null
                audioPlayer?.release(); audioPlayer = null
                showVideoSurface(false)
            }
        }
    }

    /* ── helpers ── */
    private fun ok(data: String)  = "{\"ok\":true,\"data\":${jsonStr(data)}}"
    private fun err(msg: String)  = "{\"ok\":false,\"error\":${jsonStr(msg)}}"
    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"'  -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
        }
        return sb.append("\"").toString()
    }

    private fun deliver(callbackId: String, json: String) {
        // base64 to safely cross the bridge regardless of content
        val b64 = android.util.Base64.encodeToString(json.toByteArray(), android.util.Base64.NO_WRAP)
        webView.evaluateJavascript("window.__nativeCb && window.__nativeCb('$callbackId','$b64')", null)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun buildPlayer(url: String): ExoPlayer {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("IPTVPlayer/1.0 (Android)")
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(20000)
            .setReadTimeoutMs(60000)

        val source: MediaSource =
            if (url.contains(".m3u8")) {
                HlsMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            } else {
                ProgressiveMediaSource.Factory(httpFactory)
                    .createMediaSource(MediaItem.fromUri(url))
            }

        return ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpFactory))
            .build().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .build(),
                    false
                )
                setMediaSource(source)
                repeatMode = Player.REPEAT_MODE_OFF
            }
    }

    private fun showVideoSurface(show: Boolean) {
        playerView.visibility = if (show) View.VISIBLE else View.GONE
        // make webview transparent so video shows through the player area
        webView.setBackgroundColor(if (show) 0x00000000 else 0xFF0A0A0A.toInt())
    }

    /* ── Remote / D-pad: forward keys to the WebView for navigation ── */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Let the WebView's focus system handle D-pad; only intercept BACK
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // if a channel is playing, stop and return to list instead of exiting
            if (videoPlayer != null) {
                webView.evaluateJavascript("window.__onBack && window.__onBack()", null)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        super.onPause()
        videoPlayer?.playWhenReady = false
        audioPlayer?.playWhenReady = false
    }

    override fun onDestroy() {
        videoPlayer?.release()
        audioPlayer?.release()
        super.onDestroy()
    }
}
