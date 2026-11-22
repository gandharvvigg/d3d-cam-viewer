package com.gandharv.d3dcam

import android.net.Uri
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Minimal ONVIF client: stream URLs, pan/tilt, device info, time, reboot,
 * password and picture settings. Blocking — call from Dispatchers.IO.
 */
class Onvif private constructor(private val user: String, private var pass: String) {

    var deviceUrl: String = ""
        private set
    var mediaUrl: String = ""
        private set
    var ptzUrl: String? = null
        private set
    var imagingUrl: String? = null
        private set
    var sourceToken: String? = null
        private set
    val profiles = mutableListOf<String>()
    val streamUris = mutableListOf<String>()

    companion object {
        private val PORTS = listOf(80, 5000, 8080, 8899, 2020, 6688, 888, 81)

        fun connect(host: String, openPorts: Collection<Int>, user: String, pass: String): Onvif? {
            for (port in PORTS.filter { it in openPorts }) {
                val o = Onvif(user, pass)
                if (o.init("http://$host:$port/onvif/device_service", host, port)) return o
            }
            return null
        }

        private fun section(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name\\b[^>]*>(.*?)</(?:\\w+:)?$name>", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)

        fun tag(xml: String, name: String): String? =
            Regex("<(?:\\w+:)?$name\\b[^>]*>\\s*([^<]*?)\\s*</", RegexOption.DOT_MATCHES_ALL)
                .find(xml)?.groupValues?.get(1)

        private fun xaddr(xml: String): String? = tag(xml, "XAddr")

        private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")
    }

    /** Point the URL at the host/port we actually reached (cameras sometimes report a wrong IP). */
    private fun fix(url: String, host: String, port: Int): String = try {
        val u = Uri.parse(url)
        val p = if (u.port > 0) u.port else port
        u.buildUpon().encodedAuthority("$host:$p").build().toString()
    } catch (e: Exception) {
        url
    }

    private fun init(devUrl: String, host: String, port: Int): Boolean {
        deviceUrl = devUrl
        val caps = soap(devUrl, """<GetCapabilities xmlns="http://www.onvif.org/ver10/device/wsdl"><Category>All</Category></GetCapabilities>""")
        mediaUrl = caps?.let { section(it, "Media") }?.let { xaddr(it) }?.let { fix(it, host, port) } ?: devUrl
        ptzUrl = caps?.let { section(it, "PTZ") }?.let { xaddr(it) }?.let { fix(it, host, port) }
        imagingUrl = caps?.let { section(it, "Imaging") }?.let { xaddr(it) }?.let { fix(it, host, port) }

        val prof = soap(mediaUrl, """<GetProfiles xmlns="http://www.onvif.org/ver10/media/wsdl"/>""") ?: return false
        Regex("<(?:\\w+:)?Profiles\\b[^>]*\\btoken=\"([^\"]+)\"").findAll(prof)
            .forEach { profiles.add(it.groupValues[1]) }
        sourceToken = tag(prof, "SourceToken")

        for (t in profiles) {
            val r = soap(
                mediaUrl,
                """<GetStreamUri xmlns="http://www.onvif.org/ver10/media/wsdl"><StreamSetup><Stream xmlns="http://www.onvif.org/ver10/schema">RTP-Unicast</Stream><Transport xmlns="http://www.onvif.org/ver10/schema"><Protocol>RTSP</Protocol></Transport></StreamSetup><ProfileToken>$t</ProfileToken></GetStreamUri>"""
            ) ?: continue
            tag(r, "Uri")?.let {
                val uri = unescape(it)
                if (uri.startsWith("rtsp", true)) streamUris.add(uri)
            }
        }
        return profiles.isNotEmpty()
    }

    // ----- PTZ -----

    fun move(x: Float, y: Float): Boolean {
        val token = profiles.firstOrNull() ?: return false
        val body = """<ContinuousMove xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$token</ProfileToken><Velocity><PanTilt x="$x" y="$y" xmlns="http://www.onvif.org/ver10/schema"/></Velocity></ContinuousMove>"""
        return soap(ptzUrl ?: mediaUrl, body) != null
    }

    fun stop(): Boolean {
        val token = profiles.firstOrNull() ?: return false
        val body = """<Stop xmlns="http://www.onvif.org/ver20/ptz/wsdl"><ProfileToken>$token</ProfileToken><PanTilt>true</PanTilt><Zoom>true</Zoom></Stop>"""
        return soap(ptzUrl ?: mediaUrl, body) != null
    }

    // ----- device -----

    fun deviceInfo(): Map<String, String> {
        val r = soap(deviceUrl, """<GetDeviceInformation xmlns="http://www.onvif.org/ver10/device/wsdl"/>""") ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        for (k in listOf("Manufacturer", "Model", "FirmwareVersion", "SerialNumber", "HardwareId")) {
            tag(r, k)?.takeIf { it.isNotBlank() }?.let { out[k] = unescape(it) }
        }
        return out
    }

    fun syncTime(): Boolean {
        val c = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val tz = TimeZone.getDefault()
        val offMin = tz.rawOffset / 60000
        // POSIX TZ string has the sign inverted: IST (UTC+5:30) is "IST-5:30".
        val sign = if (offMin >= 0) "-" else "+"
        val posix = "UTC$sign${Math.abs(offMin) / 60}:${"%02d".format(Math.abs(offMin) % 60)}"
        val body = """<SetSystemDateAndTime xmlns="http://www.onvif.org/ver10/device/wsdl"><DateTimeType>Manual</DateTimeType><DaylightSavings>false</DaylightSavings><TimeZone><TZ xmlns="http://www.onvif.org/ver10/schema">$posix</TZ></TimeZone><UTCDateTime><Date xmlns="http://www.onvif.org/ver10/schema"><Year>${c.get(Calendar.YEAR)}</Year><Month>${c.get(Calendar.MONTH) + 1}</Month><Day>${c.get(Calendar.DAY_OF_MONTH)}</Day></Date><Time xmlns="http://www.onvif.org/ver10/schema"><Hour>${c.get(Calendar.HOUR_OF_DAY)}</Hour><Minute>${c.get(Calendar.MINUTE)}</Minute><Second>${c.get(Calendar.SECOND)}</Second></Time></UTCDateTime></SetSystemDateAndTime>"""
        return soap(deviceUrl, body) != null
    }

    fun reboot(): Boolean =
        soap(deviceUrl, """<SystemReboot xmlns="http://www.onvif.org/ver10/device/wsdl"/>""") != null

    fun setPassword(newPass: String): Boolean {
        val body = """<SetUser xmlns="http://www.onvif.org/ver10/device/wsdl"><User><Username xmlns="http://www.onvif.org/ver10/schema">${esc(user)}</Username><Password xmlns="http://www.onvif.org/ver10/schema">${esc(newPass)}</Password><UserLevel xmlns="http://www.onvif.org/ver10/schema">Administrator</UserLevel></User></SetUser>"""
        val ok = soap(deviceUrl, body) != null
        if (ok) pass = newPass
        return ok
    }

    // ----- imaging (values 0-100 as most cameras report) -----

    data class Imaging(val brightness: Float?, val contrast: Float?, val saturation: Float?, val ir: String?)

    fun getImaging(): Imaging? {
        val url = imagingUrl ?: return null
        val tok = sourceToken ?: return null
        val r = soap(url, """<GetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$tok</VideoSourceToken></GetImagingSettings>""") ?: return null
        return Imaging(
            tag(r, "Brightness")?.toFloatOrNull(),
            tag(r, "Contrast")?.toFloatOrNull(),
            tag(r, "ColorSaturation")?.toFloatOrNull(),
            tag(r, "IrCutFilter")
        )
    }

    fun setImaging(brightness: Float? = null, contrast: Float? = null, saturation: Float? = null, ir: String? = null): Boolean {
        val url = imagingUrl ?: return false
        val tok = sourceToken ?: return false
        val s = StringBuilder()
        brightness?.let { s.append("<Brightness xmlns=\"http://www.onvif.org/ver10/schema\">$it</Brightness>") }
        saturation?.let { s.append("<ColorSaturation xmlns=\"http://www.onvif.org/ver10/schema\">$it</ColorSaturation>") }
        contrast?.let { s.append("<Contrast xmlns=\"http://www.onvif.org/ver10/schema\">$it</Contrast>") }
        ir?.let { s.append("<IrCutFilter xmlns=\"http://www.onvif.org/ver10/schema\">$it</IrCutFilter>") }
        if (s.isEmpty()) return false
        val body = """<SetImagingSettings xmlns="http://www.onvif.org/ver20/imaging/wsdl"><VideoSourceToken>$tok</VideoSourceToken><ImagingSettings>$s</ImagingSettings><ForcePersistence>true</ForcePersistence></SetImagingSettings>"""
        return soap(url, body) != null
    }

    // ----- SOAP -----

    private fun securityHeader(): String {
        val nonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val created = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val sha = MessageDigest.getInstance("SHA-1")
        sha.update(nonce)
        sha.update(created.toByteArray())
        sha.update(pass.toByteArray())
        val digest = Base64.encodeToString(sha.digest(), Base64.NO_WRAP)
        val n = Base64.encodeToString(nonce, Base64.NO_WRAP)
        return """<s:Header><Security s:mustUnderstand="1" xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-secext-1.0.xsd"><UsernameToken><Username>${esc(user)}</Username><Password Type="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-username-token-profile-1.0#PasswordDigest">$digest</Password><Nonce EncodingType="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-soap-message-security-1.0#Base64Binary">$n</Nonce><Created xmlns="http://docs.oasis-open.org/wss/2004/01/oasis-200401-wss-wssecurity-utility-1.0.xsd">$created</Created></UsernameToken></Security></s:Header>"""
    }

    private fun soap(url: String, body: String): String? {
        val env = """<?xml version="1.0" encoding="UTF-8"?><s:Envelope xmlns:s="http://www.w3.org/2003/05/soap-envelope">${securityHeader()}<s:Body>$body</s:Body></s:Envelope>"""
        val r = Net.post(url, env, "application/soap+xml; charset=utf-8") ?: return null
        val text = r.text
        return if (r.code == 200 && !text.contains("Fault>")) text else null
    }
}
