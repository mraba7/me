package com.iptv.player

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** In-memory store + disk cache so channels survive app restarts. */
object ChannelStore {
    var channels: List<Channel> = emptyList()
    var creds: XtreamCreds? = null
    var accountStatus: String? = null
    var expDate: String? = null

    fun saveToDisk(ctx: Context) {
        try {
            val arr = JSONArray()
            for (c in channels) {
                arr.put(JSONObject().apply {
                    put("id", c.id); put("sid", c.streamId); put("name", c.name)
                    put("group", c.group); put("logo", c.logo); put("url", c.url)
                })
            }
            ctx.getSharedPreferences("iptv_cache", Context.MODE_PRIVATE)
                .edit().putString("channels", arr.toString()).apply()
        } catch (_: Exception) {}
    }

    fun loadFromDisk(ctx: Context): Boolean {
        return try {
            val s = ctx.getSharedPreferences("iptv_cache", Context.MODE_PRIVATE)
                .getString("channels", null) ?: return false
            val arr = JSONArray(s)
            val list = ArrayList<Channel>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(Channel(
                    id = o.getString("id"),
                    streamId = o.optInt("sid"),
                    name = o.getString("name"),
                    group = o.getString("group"),
                    logo = o.getString("logo"),
                    url = o.getString("url")
                ))
            }
            channels = list
            list.isNotEmpty()
        } catch (_: Exception) { false }
    }
}
