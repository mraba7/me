package com.iptv.player

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XtreamApi {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    data class LoginResult(
        val ok: Boolean,
        val error: String? = null,
        val channels: List<Channel> = emptyList(),
        val status: String? = null,
        val expDate: String? = null,
        val activeCons: String? = null,
        val maxCons: String? = null,
        val resolvedBase: String? = null
    )

    private fun normalize(raw: String): String {
        var s = raw.trim().trimEnd('/')
        if (!s.startsWith("http://", true) && !s.startsWith("https://", true)) s = "http://$s"
        return s
    }

    private fun get(url: String): String? {
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "IPTVPlayer/1.0 (Android)")
                .build()
            http.newCall(req).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Connects, validates, and loads all live channels. Tries http then https. */
    fun connect(creds: XtreamCreds): LoginResult {
        var base = normalize(creds.server)
        val u = creds.user
        val p = creds.pass

        fun api(b: String) =
            "$b/player_api.php?username=$u&password=$p"

        // 1. Auth (try given, then flip protocol)
        var authStr = get(api(base))
        if (authStr == null || authStr.trimStart().startsWith("<")) {
            val flipped = if (base.startsWith("https"))
                base.replaceFirst("https://", "http://")
            else base.replaceFirst("http://", "https://")
            val alt = get(api(flipped))
            if (alt != null && !alt.trimStart().startsWith("<")) {
                authStr = alt; base = flipped
            }
        }
        if (authStr == null) return LoginResult(false, "تعذّر الوصول للخادم – تحقق من الرابط والإنترنت")
        if (authStr.trimStart().startsWith("<")) return LoginResult(false, "الخادم رجّع صفحة بدل بيانات – تحقق من الرابط")

        val authJson = try { JSONObject(authStr) } catch (e: Exception) {
            return LoginResult(false, "رد غير صالح من الخادم")
        }
        val userInfo = authJson.optJSONObject("user_info")
            ?: return LoginResult(false, "بيانات الدخول غير صحيحة")
        val auth = userInfo.opt("auth")
        if (auth == 0 || auth == "0") return LoginResult(false, "بيانات الدخول غير صحيحة")

        val status = userInfo.optString("status", "—")
        val exp = userInfo.optString("exp_date", "")
        val expDate = if (exp.isNotEmpty() && exp != "null")
            try { java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(exp.toLong() * 1000)) }
            catch (e: Exception) { "—" } else "غير محدد"
        val activeCons = userInfo.optString("active_cons", "0")
        val maxCons = userInfo.optString("max_connections", "∞")

        // 2. Categories
        val catMap = HashMap<String, String>()
        get(api(base) + "&action=get_live_categories")?.let { catStr ->
            try {
                val arr = JSONArray(catStr)
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    catMap[c.optString("category_id")] = c.optString("category_name")
                }
            } catch (_: Exception) {}
        }

        // 3. Live streams
        val streamStr = get(api(base) + "&action=get_live_streams")
            ?: return LoginResult(false, "تم الدخول لكن تعذّر جلب القنوات")
        val channels = ArrayList<Channel>()
        try {
            val arr = JSONArray(streamStr)
            for (i in 0 until arr.length()) {
                val s = arr.getJSONObject(i)
                val sid = s.optInt("stream_id")
                channels.add(
                    Channel(
                        id = sid.toString(),
                        streamId = sid,
                        name = s.optString("name", "قناة").trim(),
                        group = catMap[s.optString("category_id")] ?: "عام",
                        logo = s.optString("stream_icon", ""),
                        url = "$base/live/$u/$p/$sid.${creds.fmt}"
                    )
                )
            }
        } catch (e: Exception) {
            return LoginResult(false, "خطأ في قراءة القنوات")
        }

        if (channels.isEmpty()) return LoginResult(false, "لا توجد قنوات مباشرة في هذا الحساب")

        return LoginResult(
            ok = true, channels = channels,
            status = status, expDate = expDate,
            activeCons = activeCons, maxCons = maxCons,
            resolvedBase = base
        )
    }
}
