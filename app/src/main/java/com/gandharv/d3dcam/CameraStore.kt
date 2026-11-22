package com.gandharv.d3dcam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class Camera(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var ip: String,
    var user: String = "admin",
    var pass: String = "admin",
    var streamUrl: String? = null,
    var customUrl: String = ""
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("name", name); put("ip", ip); put("user", user); put("pass", pass)
        put("streamUrl", streamUrl ?: ""); put("customUrl", customUrl)
    }

    companion object {
        fun fromJson(o: JSONObject) = Camera(
            id = o.getString("id"),
            name = o.optString("name", "Camera"),
            ip = o.optString("ip"),
            user = o.optString("user", "admin"),
            pass = o.optString("pass", "admin"),
            streamUrl = o.optString("streamUrl").ifEmpty { null },
            customUrl = o.optString("customUrl", "")
        )
    }
}

/** Saved cameras and app settings, kept in private app storage. */
class CameraStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("cam", Context.MODE_PRIVATE)

    init { migrate() }

    /** Version 1.0–1.2 kept a single camera in loose preferences. */
    private fun migrate() {
        if (prefs.contains("cameras")) return
        val ip = prefs.getString("ip", null)
        val list = mutableListOf<Camera>()
        if (!ip.isNullOrBlank()) {
            list += Camera(
                name = "Camera", ip = ip,
                user = prefs.getString("user", "admin") ?: "admin",
                pass = prefs.getString("pass", "admin") ?: "admin",
                streamUrl = prefs.getString("working_$ip", null),
                customUrl = prefs.getString("customUrl", "") ?: ""
            )
        }
        save(list)
        list.firstOrNull()?.let { lastId = it.id }
    }

    fun all(): List<Camera> {
        val arr = JSONArray(prefs.getString("cameras", "[]"))
        return (0 until arr.length()).map { Camera.fromJson(arr.getJSONObject(it)) }
    }

    private fun save(list: List<Camera>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString("cameras", arr.toString()).apply()
    }

    fun get(id: String?): Camera? = id?.let { i -> all().firstOrNull { it.id == i } }

    fun findByIp(ip: String): Camera? = all().firstOrNull { it.ip == ip }

    fun upsert(c: Camera) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == c.id }
        if (i >= 0) list[i] = c else list += c
        save(list)
    }

    fun update(id: String, block: (Camera) -> Unit): Camera? {
        val c = get(id) ?: return null
        block(c)
        upsert(c)
        return c
    }

    fun remove(id: String) {
        save(all().filter { it.id != id })
        if (lastId == id) lastId = all().firstOrNull()?.id
    }

    var lastId: String?
        get() = prefs.getString("lastCamera", null)
        set(v) { prefs.edit().putString("lastCamera", v).apply() }

    var autoStart: Boolean
        get() = prefs.getBoolean("autoStart", true)
        set(v) { prefs.edit().putBoolean("autoStart", v).apply() }

    var autoPip: Boolean
        get() = prefs.getBoolean("autoPip", true)
        set(v) { prefs.edit().putBoolean("autoPip", v).apply() }

    var homeSsid: String
        get() = prefs.getString("home_ssid", "") ?: ""
        set(v) { prefs.edit().putString("home_ssid", v).apply() }

    fun nextName(): String {
        val n = all().size + 1
        return if (n == 1) "Camera" else "Camera $n"
    }
}
