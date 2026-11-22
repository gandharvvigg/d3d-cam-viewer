package com.gandharv.d3dcam

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Result of a setup action, shown to the user. */
data class Outcome(val ok: Boolean, val message: String)

/**
 * Camera settings over whichever protocol the camera speaks:
 *  - "hi"    : Hi3510/Hi3518 param.cgi firmware (very common in D3D-type cameras)
 *  - "vstar" : VStarcam/Foscam-style *.cgi firmware
 *  - ONVIF   : the standard protocol, where the camera supports it
 * Every call is blocking — run on Dispatchers.IO.
 */
class CameraConfig(
    val ip: String,
    val user: String,
    var pass: String,
    private val httpPorts: List<Int>,
    val onvif: Onvif?
) {
    private var hiBase: String? = null
    private var vsBase: String? = null

    val protocols: List<String>
        get() = buildList {
            if (hiBase != null) add("Hi3510 CGI")
            if (vsBase != null) add("VStarcam CGI")
            if (onvif != null) add("ONVIF")
        }

    companion object {
        private val VAR = Regex("""var\s+([\w\[\]]+)\s*=\s*['"]?([^'";\r\n]*)['"]?\s*;""")

        fun parseVars(text: String): Map<String, String> =
            VAR.findAll(text).associate { it.groupValues[1] to it.groupValues[2] }

        /** Probe ports, ONVIF and CGI dialects. */
        fun open(ip: String, user: String, pass: String): CameraConfig {
            val open = Net.ALL_PORTS.filter { Net.portOpen(ip, it, 1200) }
            val onvif = Onvif.connect(ip, open, user, pass)
            return CameraConfig(ip, user, pass, Net.HTTP_PORTS.filter { it in open }, onvif).also { it.detect() }
        }
    }

    private fun u() = Net.enc(user)
    private fun p() = Net.enc(pass)

    private fun detect() {
        for (port in httpPorts) {
            if (hiBase == null) {
                for (prefix in listOf("/cgi-bin/hi3510", "/web/cgi-bin/hi3510")) {
                    val base = "http://$ip:$port$prefix"
                    val r = Net.get("$base/param.cgi?cmd=getserverinfo&-usr=${u()}&-pwd=${p()}", user, pass)
                    if (r != null && r.code == 200 && r.text.contains("var ")) { hiBase = base; break }
                }
            }
            if (vsBase == null) {
                val base = "http://$ip:$port"
                val r = Net.get("$base/get_status.cgi?loginuse=${u()}&loginpas=${p()}&user=${u()}&pwd=${p()}", user, pass)
                if (r != null && r.code == 200 && r.text.contains("var ")) vsBase = base
            }
        }
    }

    // ---------- low-level ----------

    private fun hi(cmd: String, args: String = ""): Net.Resp? {
        val b = hiBase ?: return null
        return Net.get("$b/param.cgi?cmd=$cmd$args&-usr=${u()}&-pwd=${p()}", user, pass, 5000)
    }

    private fun hiOk(r: Net.Resp?) = r != null && r.code == 200 && !r.text.contains("[Error]", true)

    private fun vs(path: String, args: String = ""): Net.Resp? {
        val b = vsBase ?: return null
        val sep = if (args.isEmpty()) "" else "&"
        return Net.get("$b/$path?loginuse=${u()}&loginpas=${p()}&user=${u()}&pwd=${p()}$sep$args", user, pass, 5000)
    }

    private fun vsOk(r: Net.Resp?) = r != null && r.code == 200 &&
            !r.text.contains("error", true) && !r.text.contains("fail", true) && !r.text.contains("<html", true)

    private fun summarize(name: String, tried: List<Pair<String, Boolean>>): Outcome {
        if (tried.isEmpty()) return Outcome(false, "$name: this camera doesn't offer a known way to do this.")
        val okVia = tried.filter { it.second }.map { it.first }
        return if (okVia.isNotEmpty()) Outcome(true, "$name: done (via ${okVia.joinToString()}).")
        else Outcome(false, "$name: the camera rejected it (tried ${tried.joinToString { it.first }}).")
    }

    // ---------- device info ----------

    fun info(): Map<String, String> {
        val out = linkedMapOf<String, String>()
        out["IP address"] = ip
        hi("getserverinfo")?.takeIf { hiOk(it) }?.let { r ->
            val v = parseVars(r.text)
            v["model"]?.let { out["Model"] = it }
            v["name"]?.let { out["Name"] = it }
            v["softVersion"]?.let { out["Firmware"] = it }
            v["hardVersion"]?.let { out["Hardware"] = it }
            v["startdate"]?.let { out["Running since"] = it }
            v["sdstatus"]?.let { out["SD card"] = it }
        }
        vs("get_status.cgi")?.takeIf { vsOk(it) }?.let { r ->
            val v = parseVars(r.text)
            v["alias"]?.takeIf { it.isNotBlank() }?.let { out["Name"] = it }
            v["id"]?.let { out["Device ID"] = it }
            v["sys_ver"]?.let { out["Firmware"] = it }
            v["mac"]?.let { out["MAC"] = it }
            v["wifimac"]?.let { out["Wi‑Fi MAC"] = it }
            v["sdstatus"]?.let { out["SD card"] = it }
        }
        onvif?.deviceInfo()?.forEach { (k, v) -> out.putIfAbsent(k, v) }
        if (protocols.isNotEmpty()) out["Protocols"] = protocols.joinToString()
        return out
    }

    // ---------- Wi‑Fi ----------

    fun scanWifi(): List<String> {
        val names = linkedSetOf<String>()
        hi("searchwireless")?.takeIf { hiOk(it) }?.let { r ->
            parseVars(r.text).filterKeys { it.contains("ssid", true) }.values.forEach { if (it.isNotBlank()) names += it }
        }
        if (vsBase != null) {
            vs("wifi_scan.cgi")
            Thread.sleep(3000)
            vs("get_wifi_scan_result.cgi")?.let { r ->
                Regex("""ap_ssid\[\d+\]\s*=\s*['"]([^'"]*)""").findAll(r.text)
                    .forEach { if (it.groupValues[1].isNotBlank()) names += it.groupValues[1] }
            }
        }
        return names.toList()
    }

    fun setWifi(ssid: String, key: String): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        val s = Net.enc(ssid)
        val k = Net.enc(key)
        if (hiBase != null) {
            // wf_auth: 0 open, 3 WPA2-PSK. wf_enc: 1 AES.
            val auth = if (key.isEmpty()) 0 else 3
            val ok = hiOk(hi("setwirelessattr", "&-wf_enable=1&-wf_ssid=$s&-wf_auth=$auth&-wf_key=$k&-wf_enc=1&-wf_mode=0"))
            tried += "Hi3510" to ok
        }
        if (vsBase != null) {
            val enc = if (key.isEmpty()) 0 else 4
            val ok = vsOk(vs("set_wifi.cgi", "enable=1&ssid=$s&channel=0&mode=0&encrypt=$enc&authtype=$enc&keyformat=0&defkey=0&key1=&key2=&key3=&key4=&key1_bits=0&key2_bits=0&key3_bits=0&key4_bits=0&wpa_psk=$k"))
            tried += "VStarcam" to ok
        }
        val o = summarize("Wi‑Fi", tried)
        return if (o.ok) Outcome(true, "Wi‑Fi details saved. The camera will restart and join \"$ssid\" (about 1 minute).") else o
    }

    // ---------- password ----------

    fun changePassword(newPass: String): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        if (hiBase != null) {
            tried += "Hi3510" to hiOk(hi("setuserattr", "&-at_username=${u()}&-at_password=${Net.enc(newPass)}&-at_authlevel=255"))
        }
        if (vsBase != null) {
            val v = vs("get_params.cgi")?.let { parseVars(it.text) } ?: emptyMap()
            val args = StringBuilder()
            var matched = false
            for (i in 1..8) {
                val un = v["user$i"] ?: continue
                val pw = if (un == user) { matched = true; newPass } else (v["pwd$i"] ?: "")
                if (args.isNotEmpty()) args.append('&')
                args.append("user$i=${Net.enc(un)}&pwd$i=${Net.enc(pw)}")
                v["pri$i"]?.let { args.append("&pri$i=$it") }
            }
            if (matched) tried += "VStarcam" to vsOk(vs("set_users.cgi", args.toString()))
        }
        if (onvif != null && tried.none { it.second }) tried += "ONVIF" to onvif.setPassword(newPass)
        val o = summarize("Password", tried)
        if (o.ok) pass = newPass
        return o
    }

    // ---------- picture ----------

    data class Picture(
        val brightness: Int? = null, val contrast: Int? = null, val saturation: Int? = null,
        val flip: Boolean? = null, val mirror: Boolean? = null
    )

    /** Reads current settings. Values are 0-100 %. */
    fun getPicture(): Picture {
        hi("getimageattr")?.takeIf { hiOk(it) }?.let { r ->
            val v = parseVars(r.text)
            fun pct(k: String) = v[k]?.toIntOrNull()?.let { it * 100 / 255 }
            return Picture(pct("brightness"), pct("contrast"), pct("saturation"), v["flip"] == "on", v["mirror"] == "on")
        }
        vs("get_camera_params.cgi")?.takeIf { vsOk(it) }?.let { r ->
            val v = parseVars(r.text)
            val f = v["flip"]?.toIntOrNull()
            return Picture(
                v["vbright"]?.toIntOrNull()?.let { it * 100 / 255 },
                v["vcontrast"]?.toIntOrNull()?.let { it * 100 / 255 },
                null,
                f?.let { (it and 1) == 1 }, f?.let { (it and 2) == 2 }
            )
        }
        onvif?.getImaging()?.let { i ->
            return Picture(i.brightness?.toInt(), i.contrast?.toInt(), i.saturation?.toInt())
        }
        return Picture()
    }

    fun setPicture(brightness: Int, contrast: Int, saturation: Int): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        fun x255(p: Int) = (p * 255 / 100).coerceIn(0, 255)
        if (hiBase != null) tried += "Hi3510" to hiOk(hi("setimageattr", "&-brightness=${x255(brightness)}&-contrast=${x255(contrast)}&-saturation=${x255(saturation)}"))
        if (vsBase != null) {
            val a = vsOk(vs("camera_control.cgi", "param=1&value=${x255(brightness)}"))
            val b = vsOk(vs("camera_control.cgi", "param=2&value=${x255(contrast)}"))
            tried += "VStarcam" to (a || b)
        }
        if (onvif != null && tried.none { it.second }) {
            tried += "ONVIF" to onvif.setImaging(brightness.toFloat(), contrast.toFloat(), saturation.toFloat())
        }
        return summarize("Picture", tried)
    }

    fun setFlip(flip: Boolean, mirror: Boolean): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        if (hiBase != null) tried += "Hi3510" to hiOk(hi("setimageattr", "&-flip=${if (flip) "on" else "off"}&-mirror=${if (mirror) "on" else "off"}"))
        if (vsBase != null) {
            val v = (if (flip) 1 else 0) + (if (mirror) 2 else 0)
            tried += "VStarcam" to vsOk(vs("camera_control.cgi", "param=5&value=$v"))
        }
        return summarize("Flip / mirror", tried)
    }

    /** mode: "auto", "on" (night vision lights always on), "off". */
    fun setNightVision(mode: String): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        if (hiBase != null) {
            val v = when (mode) { "on" -> "open"; "off" -> "close"; else -> "auto" }
            tried += "Hi3510" to hiOk(hi("setinfrared", "&-infraredstat=$v"))
        }
        if (onvif != null && tried.none { it.second }) {
            // IR cut filter ON = daytime colour (IR lights off); OFF = night mode.
            val ir = when (mode) { "on" -> "OFF"; "off" -> "ON"; else -> "AUTO" }
            tried += "ONVIF" to onvif.setImaging(ir = ir)
        }
        return summarize("Night vision", tried)
    }

    /** level: "high", "medium", "low" — changes bitrate and frame rate of the main stream. */
    fun setQuality(level: String): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        val (bps, fps) = when (level) { "high" -> 2048 to 25; "low" -> 512 to 10; else -> 1024 to 15 }
        if (hiBase != null) tried += "Hi3510" to hiOk(hi("setvencattr", "&-chn=11&-bps=$bps&-fps=$fps&-brmode=1"))
        return summarize("Video quality", tried)
    }

    // ---------- SD card & recording ----------

    data class SdInfo(val present: Boolean?, val status: String?, val totalMb: Long?, val freeMb: Long?) {
        fun describe(): String {
            if (present == false) return "No SD card detected"
            val parts = mutableListOf<String>()
            status?.takeIf { it.isNotBlank() }?.let { parts += it }
            if (totalMb != null && totalMb > 0) {
                val free = freeMb?.let { "%.1f GB free of ".format(it / 1024.0) } ?: ""
                parts += free + "%.1f GB".format(totalMb / 1024.0)
            }
            return if (parts.isEmpty()) "SD card status unknown" else parts.joinToString(" · ")
        }
    }

    /** Camera reports space in KB on most firmware; convert to MB. */
    private fun toMb(v: String?): Long? {
        val n = v?.trim()?.toLongOrNull() ?: return null
        return when {
            n > 4_000_000 -> n / 1024          // KB
            else -> n                          // already MB
        }
    }

    fun sdInfo(): SdInfo {
        hi("getserverinfo")?.takeIf { hiOk(it) }?.let { r ->
            val v = parseVars(r.text)
            val status = v["sdstatus"]
            val total = toMb(v["sdtotalspace"] ?: v["sdtotal"])
            val free = toMb(v["sdfreespace"] ?: v["sdfree"])
            val present = when {
                status == null -> null
                status.contains("out", true) || status.contains("no", true) || status == "0" -> false
                else -> true
            }
            if (status != null || total != null) return SdInfo(present, status, total, free)
        }
        vs("get_status.cgi")?.takeIf { vsOk(it) }?.let { r ->
            val v = parseVars(r.text)
            val st = v["sdstatus"]
            return SdInfo(st?.let { it != "0" }, when (st) { "0" -> "No card"; "1" -> "Ready"; "2" -> "Recording"; else -> st },
                v["sdtotal"]?.toLongOrNull(), v["sdfree"]?.toLongOrNull())
        }
        return SdInfo(null, null, null, null)
    }

    data class RecordingState(val enabled: Boolean, val channel: Int?, val segmentSec: Int?)

    fun recordingState(): RecordingState? {
        hi("getplanrecattr")?.takeIf { hiOk(it) }?.let { r ->
            val v = parseVars(r.text)
            val en = v["planrec_enable"] ?: v["enable"]
            return RecordingState(en == "1", (v["planrec_chn"] ?: v["chn"])?.toIntOrNull(),
                (v["planrec_time"] ?: v["duration"])?.toIntOrNull())
        }
        return null
    }

    /**
     * Continuous recording to the SD card. [hd] records the main (HD) stream;
     * [segmentSec] is the length of each file (15–900 s).
     */
    fun setContinuousRecording(enable: Boolean, hd: Boolean = true, segmentSec: Int = 600): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        if (hiBase != null) {
            val chn = if (hd) 11 else 12
            val seg = segmentSec.coerceIn(15, 900)
            val a = hiOk(hi("setplanrecattr", "&-planrec_enable=${if (enable) 1 else 0}&-planrec_time=$seg&-planrec_chn=$chn"))
            if (enable) {
                // Record every half-hour slot of every day.
                val all = "P".repeat(48)
                val days = (0..6).joinToString("") { "&-week$it=$all" }
                hiOk(hi("setscheduleex", "&-ename=plan$days"))
            }
            tried += "Hi3510" to a
        }
        if (vsBase != null) {
            tried += "VStarcam" to vsOk(vs("set_recordsch.cgi", "record_schedule_enable=${if (enable) 1 else 0}&record_cover=1&record_timer=${segmentSec / 60}"))
        }
        val o = summarize("Continuous recording", tried)
        return if (o.ok) Outcome(true, if (enable) "Continuous ${if (hd) "HD " else ""}recording to the SD card is on. When the card is full, the oldest recordings are replaced."
        else "Continuous recording is off.") else o
    }

    fun formatSd(): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        hiBase?.let { b ->
            val r = Net.get("$b/sdfrmt.cgi?-usr=${u()}&-pwd=${p()}", user, pass, 60_000)
            tried += "Hi3510" to (r != null && r.code == 200 && !r.text.contains("[Error]", true))
        }
        if (vsBase != null) tried += "VStarcam" to vsOk(vs("set_formatsd.cgi"))
        val o = summarize("Format SD card", tried)
        return if (o.ok) Outcome(true, "SD card formatted. All recordings were erased.") else o
    }

    /** Base URLs the SD card can be browsed at. */
    val sdRoots: List<String>
        get() = httpPorts.map { "http://$ip:$it/sd/" }

    // ---------- tools ----------

    fun syncTime(): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        val tz = TimeZone.getDefault()
        if (hiBase != null) {
            val t = SimpleDateFormat("yyyy.MM.dd.HH.mm.ss", Locale.US).format(Date())
            val off = tz.rawOffset / 60000
            val gmt = "GMT%s%02d:%02d".format(if (off >= 0) "+" else "-", Math.abs(off) / 60, Math.abs(off) % 60)
            // Firmware differs in the time-zone names it accepts; try the common forms in turn.
            val zones = listOf(tz.id, if (tz.id == "Asia/Kolkata") "Asia/Calcutta" else null, gmt).filterNotNull().distinct()
            var ok = false
            for (z in zones) {
                if (hiOk(hi("setservertime", "&-time=$t&-timezone=${Net.enc(z)}&-dstmode=off"))) { ok = true; break }
            }
            if (!ok) ok = hiOk(hi("setservertime", "&-time=$t&-dstmode=off"))
            tried += "Hi3510" to ok
        }
        if (vsBase != null) {
            val now = System.currentTimeMillis() / 1000
            val tzSec = -tz.rawOffset / 1000
            tried += "VStarcam" to vsOk(vs("set_datetime.cgi", "now=$now&tz=$tzSec&ntp_enable=1&ntp_svr=pool.ntp.org"))
        }
        // ONVIF as well, so the camera's standard clock is set even if the CGI ignored the zone.
        if (onvif != null) tried += "ONVIF" to onvif.syncTime()
        return summarize("Time", tried)
    }

    /**
     * Makes the camera set its own clock from an internet time server every time it starts
     * (and periodically), in the phone's time zone. Also sets the time right now.
     */
    fun enableAutoTime(server: String = "in.pool.ntp.org"): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        val tz = TimeZone.getDefault()
        if (hiBase != null) {
            val t = SimpleDateFormat("yyyy.MM.dd.HH.mm.ss", Locale.US).format(Date())
            hi("setservertime", "&-time=$t&-timezone=${Net.enc(tz.id)}&-dstmode=off")
            tried += "Hi3510" to hiOk(hi("setntpattr", "&-ntpenable=1&-ntpserver=${Net.enc(server)}&-ntpinterval=1"))
        }
        if (vsBase != null) {
            val now = System.currentTimeMillis() / 1000
            tried += "VStarcam" to vsOk(vs("set_datetime.cgi", "now=$now&tz=${-tz.rawOffset / 1000}&ntp_enable=1&ntp_svr=${Net.enc(server)}"))
        }
        if (onvif != null) tried += "ONVIF" to onvif.enableNtp(server)
        val o = summarize("Automatic time", tried)
        return if (o.ok) Outcome(true, "Automatic time is on. The camera will get the correct time from the internet ($server) every time it starts.") else o
    }

    fun reboot(): Outcome {
        val tried = mutableListOf<Pair<String, Boolean>>()
        hiBase?.let { b ->
            val r = Net.get("$b/sysreboot.cgi?-usr=${u()}&-pwd=${p()}", user, pass, 4000)
            // The camera often drops the connection while rebooting, so "no answer" also counts.
            tried += "Hi3510" to (r == null || r.code == 200)
        }
        if (vsBase != null) {
            val r = vs("reboot.cgi")
            tried += "VStarcam" to (r == null || r.code == 200)
        }
        if (onvif != null && tried.none { it.second }) tried += "ONVIF" to onvif.reboot()
        val o = summarize("Restart", tried)
        return if (o.ok) Outcome(true, "Camera is restarting. Give it about a minute.") else o
    }

    /** After a Wi‑Fi change the camera needs a restart on most firmware. */
    fun rebootQuietly() { reboot() }
}
