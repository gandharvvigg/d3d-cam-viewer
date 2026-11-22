package com.gandharv.d3dcam

import android.util.Base64
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

/** Small blocking network helpers. Call from Dispatchers.IO only. */
object Net {

    class Resp(val code: Int, val type: String?, val body: ByteArray) {
        val text: String get() = String(body, Charsets.UTF_8)
    }

    /** Ports on which these cameras usually serve HTTP / ONVIF. */
    val HTTP_PORTS = listOf(80, 81, 5000, 8080, 8899, 88, 2020)

    /** Every port the scanner and viewer probe. */
    val ALL_PORTS = listOf(554, 10554, 80, 81, 5000, 8080, 8899, 88, 2020)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun basic(user: String, pass: String): String =
        "Basic " + Base64.encodeToString("$user:$pass".toByteArray(), Base64.NO_WRAP)

    fun portOpen(host: String, port: Int, timeoutMs: Int = 400): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs); true }
    } catch (e: Exception) {
        false
    }

    fun get(url: String, user: String, pass: String, timeoutMs: Int = 3000): Resp? =
        request("GET", url, user, pass, null, null, timeoutMs)

    fun post(url: String, body: String, contentType: String, timeoutMs: Int = 4000): Resp? =
        request("POST", url, null, null, body, contentType, timeoutMs)

    private fun request(
        method: String, url: String, user: String?, pass: String?,
        body: String?, contentType: String?, timeoutMs: Int
    ): Resp? {
        var c: HttpURLConnection? = null
        return try {
            c = URL(url).openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.useCaches = false
            if (user != null && pass != null) c.setRequestProperty("Authorization", basic(user, pass))
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType ?: "text/plain")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val stream = if (code < 400) c.inputStream else c.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            Resp(code, c.contentType, bytes)
        } catch (e: Exception) {
            null
        } finally {
            c?.disconnect()
        }
    }

    fun isJpeg(b: ByteArray): Boolean =
        b.size > 100 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()

    fun snapshotUrls(ip: String, port: Int, user: String, pass: String): List<String> {
        val u = enc(user)
        val p = enc(pass)
        val base = "http://$ip:$port"
        return listOf(
            "$base/tmpfs/auto.jpg",
            "$base/tmpfs/snap.jpg",
            "$base/web/tmpfs/auto.jpg",
            "$base/snapshot.cgi?user=$u&pwd=$p",
            "$base/snapshot.cgi?loginuse=$u&loginpas=$p",
            "$base/cgi-bin/snapshot.cgi?loginuse=$u&loginpas=$p",
            "$base/snap.jpg",
            "$base/image.jpg",
            "$base/onvif/snapshot",
            "$base/cgi-bin/hi3510/snap.cgi?&-getpic"
        )
    }

    /** Returns the first URL that answers with a real JPEG, or null. */
    fun findSnapshot(ip: String, openPorts: Collection<Int>, user: String, pass: String): String? {
        for (port in HTTP_PORTS.filter { it in openPorts }) {
            for (url in snapshotUrls(ip, port, user, pass)) {
                val r = get(url, user, pass, 2500) ?: continue
                if (r.code == 200 && isJpeg(r.body)) return url
            }
        }
        return null
    }
}
