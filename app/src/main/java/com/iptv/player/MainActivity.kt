package com.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

@UnstableApi
class MainActivity : AppCompatActivity() {

    private lateinit var engine: PlayerEngine
    private lateinit var adapter: ChannelAdapter

    private lateinit var playerView: PlayerView
    private lateinit var panel: LinearLayout
    private lateinit var scrim: View
    private lateinit var controlBar: LinearLayout
    private lateinit var idleHint: View
    private lateinit var buffering: ProgressBar
    private lateinit var dualInfo: TextView
    private lateinit var nowPlaying: TextView
    private lateinit var searchBox: EditText
    private lateinit var groupBar: LinearLayout
    private lateinit var modeVideo: Button
    private lateinit var modeAudio: Button
    private lateinit var modeHint: TextView
    private lateinit var footer: TextView
    private lateinit var volBar: SeekBar

    private var allChannels: List<Channel> = emptyList()
    private var currentGroup = "الكل"
    private var searchText = ""
    private var mode = "video"
    private var videoCh: Channel? = null
    private var audioCh: Channel? = null
    private var volume = 0.8f

    private val ui = Handler(Looper.getMainLooper())
    private val hideControls = Runnable { controlBar.visibility = View.GONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If channels were lost (process restart), go back to login
        if (ChannelStore.channels.isEmpty()) {
            if (!ChannelStore.loadFromDisk(this)) {
                startActivity(Intent(this, LoginActivity::class.java)); finish(); return
            }
        }

        setContentView(R.layout.activity_main)

        playerView = findViewById(R.id.playerView)
        panel = findViewById(R.id.channelPanel)
        scrim = findViewById(R.id.scrim)
        controlBar = findViewById(R.id.controlBar)
        idleHint = findViewById(R.id.idleHint)
        buffering = findViewById(R.id.buffering)
        dualInfo = findViewById(R.id.dualInfo)
        nowPlaying = findViewById(R.id.nowPlaying)
        searchBox = findViewById(R.id.searchBox)
        groupBar = findViewById(R.id.groupBar)
        modeVideo = findViewById(R.id.modeVideo)
        modeAudio = findViewById(R.id.modeAudio)
        modeHint = findViewById(R.id.modeHint)
        footer = findViewById(R.id.footer)
        volBar = findViewById(R.id.volBar)

        engine = PlayerEngine(this)
        engine.attachTo(playerView)
        engine.onVideoBuffering = { b -> ui.post { buffering.visibility = if (b) View.VISIBLE else View.GONE } }
        engine.onVideoReady = { ui.post { idleHint.visibility = View.GONE; buffering.visibility = View.GONE } }
        engine.onVideoError = { code -> ui.post {
            buffering.visibility = View.GONE
            Toast.makeText(this, "تعذّر تشغيل القناة ($code) — جرّب صيغة أخرى أو قناة ثانية", Toast.LENGTH_LONG).show()
        } }

        allChannels = ChannelStore.channels

        adapter = ChannelAdapter { ch -> onChannelClicked(ch) }
        val list = findViewById<RecyclerView>(R.id.channelList)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        searchBox.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { searchText = s?.toString() ?: ""; applyFilter() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })

        modeVideo.setOnClickListener { setMode("video") }
        modeAudio.setOnClickListener { setMode("audio") }

        findViewById<Button>(R.id.btnOpenList).setOnClickListener { showPanel(true) }
        findViewById<Button>(R.id.btnClose).setOnClickListener { showPanel(false) }
        scrim.setOnClickListener { showPanel(false) }
        findViewById<Button>(R.id.btnLogout).setOnClickListener { logout() }

        volBar.progress = (volume * 100).toInt()
        volBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                volume = p / 100f; engine.setVolume(volume)
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })

        buildGroups()
        applyFilter()
        updateFooter()
        setMode("video")

        // open panel on first launch so user sees channels (nothing is playing yet → no black-screen issue)
        showPanel(true)
    }

    private fun onChannelClicked(ch: Channel) {
        if (mode == "video") {
            val synced = audioCh == null || audioCh?.id == videoCh?.id
            videoCh = ch
            if (synced) audioCh = ch
            loadStreams()
        } else {
            audioCh = ch
            loadStreams()
        }
        adapter.videoId = videoCh?.id
        adapter.audioId = audioCh?.id
        adapter.notifyDataSetChanged()
        updateNowPlaying()
        // close panel after picking a video channel so the picture is visible
        if (mode == "video") ui.postDelayed({ showPanel(false) }, 200)
    }

    private fun loadStreams() {
        val v = videoCh ?: return
        val a = audioCh
        val same = a == null || a.id == v.id
        idleHint.visibility = View.GONE
        buffering.visibility = View.VISIBLE
        engine.playVideo(v.url, muted = !same)
        if (!same && a != null) engine.playAudio(a.url) else engine.stopAudio()
        engine.setVolume(volume)
        updateNowPlaying()
    }

    private fun updateNowPlaying() {
        val v = videoCh
        val a = audioCh
        if (v == null) { nowPlaying.text = ""; dualInfo.visibility = View.GONE; return }
        val sep = a != null && a.id != v.id
        nowPlaying.text = if (sep) "🎬 ${v.name}   |   🔊 ${a!!.name}" else "🎬 ${v.name}"
        if (sep) {
            dualInfo.visibility = View.VISIBLE
            dualInfo.text = "🎬 ${v.name}\n🔊 ${a!!.name}"
        } else dualInfo.visibility = View.GONE
    }

    private fun setMode(m: String) {
        mode = m
        if (m == "video") {
            modeVideo.setBackgroundResource(R.drawable.bg_mode_on_blue)
            modeVideo.setTextColor(0xFFFFFFFF.toInt())
            modeAudio.setBackgroundColor(0x00000000)
            modeAudio.setTextColor(0xFF9CA3AF.toInt())
            modeHint.text = "اختر قناة لمشاهدة صورتها"
            modeHint.setTextColor(0xFF93C5FD.toInt())
            modeHint.setBackgroundResource(R.drawable.bg_hint_blue)
        } else {
            modeAudio.setBackgroundResource(R.drawable.bg_mode_on_purple)
            modeAudio.setTextColor(0xFFFFFFFF.toInt())
            modeVideo.setBackgroundColor(0x00000000)
            modeVideo.setTextColor(0xFF9CA3AF.toInt())
            modeHint.text = "اختر قناة لسماع صوتها فقط"
            modeHint.setTextColor(0xFFC4B5FD.toInt())
            modeHint.setBackgroundResource(R.drawable.bg_hint_purple)
        }
    }

    private fun buildGroups() {
        groupBar.removeAllViews()
        val groups = mutableListOf("الكل")
        groups.addAll(allChannels.map { it.group }.distinct())
        for (g in groups) {
            val chip = Button(this).apply {
                text = g
                textSize = 11f
                isAllCaps = false
                setTextColor(if (g == currentGroup) 0xFFFFFFFF.toInt() else 0xFF9CA3AF.toInt())
                setBackgroundResource(if (g == currentGroup) R.drawable.bg_chip_on else R.drawable.bg_chip)
                minWidth = 0; minHeight = 0
                setPadding(20, 8, 20, 8)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = 8
                layoutParams = lp
                setOnClickListener { currentGroup = g; buildGroups(); applyFilter() }
            }
            groupBar.addView(chip)
        }
    }

    private fun applyFilter() {
        val filtered = allChannels.filter {
            it.name.contains(searchText, ignoreCase = true) &&
            (currentGroup == "الكل" || it.group == currentGroup)
        }
        adapter.videoId = videoCh?.id
        adapter.audioId = audioCh?.id
        adapter.submit(filtered)
        updateFooter()
    }

    private fun updateFooter() {
        footer.text = "${allChannels.size} قناة"
    }

    private fun showPanel(show: Boolean) {
        panel.visibility = if (show) View.VISIBLE else View.GONE
        scrim.visibility = if (show && videoCh != null) View.VISIBLE else View.GONE
        if (show) {
            controlBar.visibility = View.GONE
            searchBox.requestFocus()
        } else if (videoCh != null) {
            flashControls()
        }
    }

    private fun flashControls() {
        controlBar.visibility = View.VISIBLE
        ui.removeCallbacks(hideControls)
        ui.postDelayed(hideControls, 4000)
    }

    private fun logout() {
        engine.stopAll()
        getSharedPreferences("iptv", Context.MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("iptv_cache", Context.MODE_PRIVATE).edit().clear().apply()
        ChannelStore.channels = emptyList()
        startActivity(Intent(this, LoginActivity::class.java)); finish()
    }

    /* ── Remote / D-pad ── */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> { volume = (volume + 0.1f).coerceAtMost(1f); engine.setVolume(volume); volBar.progress = (volume*100).toInt(); flashControls(); return true }
            KeyEvent.KEYCODE_VOLUME_DOWN -> { volume = (volume - 0.1f).coerceAtLeast(0f); engine.setVolume(volume); volBar.progress = (volume*100).toInt(); flashControls(); return true }
            KeyEvent.KEYCODE_MENU -> { showPanel(panel.visibility != View.VISIBLE); return true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                if (panel.visibility != View.VISIBLE) { showPanel(true); return true }
            }
            KeyEvent.KEYCODE_BACK -> {
                if (panel.visibility == View.VISIBLE) { showPanel(false); return true }
                if (videoCh != null) { showPanel(true); return true }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() { super.onPause(); engine.pause() }
    override fun onResume() { super.onResume(); engine.resume() }
    override fun onDestroy() { engine.release(); super.onDestroy() }
}
