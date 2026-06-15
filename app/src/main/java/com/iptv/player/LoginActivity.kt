package com.iptv.player

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope

class LoginActivity : AppCompatActivity() {

    private val api = XtreamApi()

    @UnstableApi
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        val inServer = findViewById<EditText>(R.id.inServer)
        val inUser = findViewById<EditText>(R.id.inUser)
        val inPass = findViewById<EditText>(R.id.inPass)
        val fmtTs = findViewById<RadioButton>(R.id.fmtTs)
        val fmtM3u8 = findViewById<RadioButton>(R.id.fmtM3u8)
        val msg = findViewById<TextView>(R.id.loginMsg)
        val progress = findViewById<ProgressBar>(R.id.loginProgress)
        val btn = findViewById<Button>(R.id.btnConnect)

        // Prefill saved credentials
        val prefs = getSharedPreferences("iptv", Context.MODE_PRIVATE)
        inServer.setText(prefs.getString("server", ""))
        inUser.setText(prefs.getString("user", ""))
        inPass.setText(prefs.getString("pass", ""))
        if (prefs.getString("fmt", "ts") == "m3u8") fmtM3u8.isChecked = true else fmtTs.isChecked = true

        // Auto-reconnect: if we have saved creds, reconnect in background and skip login
        val savedServer = prefs.getString("server", "") ?: ""
        val savedUser = prefs.getString("user", "") ?: ""
        val savedPass = prefs.getString("pass", "") ?: ""
        val savedFmt = prefs.getString("fmt", "ts") ?: "ts"
        if (savedServer.isNotEmpty() && savedUser.isNotEmpty() && savedPass.isNotEmpty()) {
            msg.visibility = View.VISIBLE
            msg.text = "🔄 جاري إعادة الاتصال…"
            progress.visibility = View.VISIBLE
            btn.isEnabled = false
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    api.connect(XtreamCreds(savedServer, savedUser, savedPass, savedFmt))
                }
                if (result.ok) {
                    ChannelStore.channels = result.channels
                    ChannelStore.creds = XtreamCreds(result.resolvedBase ?: savedServer, savedUser, savedPass, savedFmt)
                    ChannelStore.accountStatus = result.status
                    ChannelStore.expDate = result.expDate
                    ChannelStore.saveToDisk(this@LoginActivity)
                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    // reconnect failed — try cached channels, else show form
                    progress.visibility = View.GONE
                    btn.isEnabled = true
                    if (ChannelStore.loadFromDisk(this@LoginActivity)) {
                        ChannelStore.creds = XtreamCreds(savedServer, savedUser, savedPass, savedFmt)
                        startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                        finish()
                    } else {
                        msg.text = "⚠️ ${result.error} — عدّل البيانات وأعد المحاولة"
                    }
                }
            }
        }

        btn.setOnClickListener {
            val server = inServer.text.toString().trim()
            val user = inUser.text.toString().trim()
            val pass = inPass.text.toString().trim()
            val fmt = if (fmtM3u8.isChecked) "m3u8" else "ts"

            if (server.isEmpty() || user.isEmpty() || pass.isEmpty()) {
                msg.visibility = View.VISIBLE
                msg.text = "⚠️ يرجى تعبئة جميع الحقول"
                return@setOnClickListener
            }

            msg.visibility = View.GONE
            progress.visibility = View.VISIBLE
            btn.isEnabled = false

            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    api.connect(XtreamCreds(server, user, pass, fmt))
                }
                progress.visibility = View.GONE
                btn.isEnabled = true

                if (result.ok) {
                    // Save creds + channel cache
                    prefs.edit()
                        .putString("server", result.resolvedBase ?: server)
                        .putString("user", user)
                        .putString("pass", pass)
                        .putString("fmt", fmt)
                        .apply()

                    ChannelStore.channels = result.channels
                    ChannelStore.creds = XtreamCreds(result.resolvedBase ?: server, user, pass, fmt)
                    ChannelStore.accountStatus = result.status
                    ChannelStore.expDate = result.expDate
                    ChannelStore.saveToDisk(this@LoginActivity)

                    startActivity(Intent(this@LoginActivity, MainActivity::class.java))
                    finish()
                } else {
                    msg.visibility = View.VISIBLE
                    msg.text = "⚠️ ${result.error}"
                }
            }
        }
    }
}
