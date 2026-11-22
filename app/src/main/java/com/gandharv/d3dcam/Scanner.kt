package com.gandharv.d3dcam

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

data class Found(
    val ip: String,
    val ports: List<Int>,
    val snapshotUrl: String?,
    val onvif: Boolean,
    val announced: Boolean = false,
    val model: String? = null
) {
    val score: Int
        get() = (if (snapshotUrl != null) 4 else 0) + (if (onvif) 3 else 0) + (if (announced) 3 else 0) +
                (if (model != null) 3 else 0) + (if (554 in ports || 10554 in ports) 2 else 0)
    val likelyCamera: Boolean get() = score >= 2
}

object Scanner {

    /** The Wi‑Fi network, even when mobile data is the "active" one (e.g. on a camera hotspot). */
    @Suppress("DEPRECATION")
    fun wifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        cm.activeNetwork?.let { n ->
            val c = cm.getNetworkCapabilities(n)
            if (c != null && (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) return n
        }
        return cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
    }

    fun onWifi(ctx: Context): Boolean = wifiNetwork(ctx) != null

    private fun wifiLink(ctx: Context): LinkProperties? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        return wifiNetwork(ctx)?.let { cm.getLinkProperties(it) }
    }

    /** This phone's Wi‑Fi IPv4 address and prefix length, e.g. ("192.168.1.23", 24). */
    fun localIpv4(ctx: Context): Pair<String, Int>? {
        val lp = wifiLink(ctx) ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress!! to la.prefixLength
        }
        return null
    }

    /** The Wi‑Fi gateway (the router — or the camera itself when the phone is on its hotspot). */
    fun gateway(ctx: Context): String? {
        val lp = wifiLink(ctx) ?: return null
        return lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
            ?.gateway?.hostAddress
    }

    /** Make all app traffic use Wi‑Fi (needed on camera hotspots that have no internet). */
    fun bindToWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val n = wifiNetwork(ctx) ?: return false
        return cm.bindProcessToNetwork(n)
    }

    fun unbind(ctx: Context) {
        ctx.getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null)
    }

    private fun ipToInt(ip: String): Int =
        ip.split('.').fold(0) { acc, s -> (acc shl 8) or (s.toInt() and 0xFF) }

    private fun intToIp(i: Int): String =
        "${(i ushr 24) and 0xFF}.${(i ushr 16) and 0xFF}.${(i ushr 8) and 0xFF}.${i and 0xFF}"

    /** All host addresses on our subnet (capped at 1022 hosts, i.e. a /22). */
    fun subnetHosts(ip: String, prefix: Int): List<String> {
        val p = prefix.coerceIn(22, 30)
        val mask = if (p == 0) 0 else (-1 shl (32 - p))
        val net = ipToInt(ip) and mask
        val count = 1 shl (32 - p)
        return (1 until count - 1).map { intToIp(net + it) }.filter { it != ip }
    }

    /** Listens for answers to a UDP multicast probe. */
    private fun multicastProbe(ctx: Context, msg: ByteArray, port: Int, listenMs: Long): Set<String> {
        val found = mutableSetOf<String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("cam-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 400
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(msg, msg.size, group, port)) }
                val buf = ByteArray(65535)
                val end = System.currentTimeMillis() + listenMs
                while (System.currentTimeMillis() < end) {
                    val pk = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(pk)
                        pk.address?.hostAddress?.let { found.add(it) }
                    } catch (_: SocketTimeoutException) {
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try { lock.release() } catch (_: Exception) {}
        }
        return found
    }

    /** ONVIF WS-Discovery. */
    private fun wsDiscovery(ctx: Context): Set<String> {
        val msg = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
        return multicastProbe(ctx, msg.toByteArray(), 3702, 3000)
    }

    /** "HDS" search used by Hi35xx camera firmware. */
    private fun hdsSearch(ctx: Context): Set<String> {
        val msg = "SEARCH * HDS/1.0\r\nCSeq:1\r\nClient-ID:${(10000..99999).random()}\r\nAccept-Type:text/HDS\r\nContent-Length:0\r\n\r\n"
        return multicastProbe(ctx, msg.toByteArray(), 8002, 2500)
    }

    /** Ask a device for its model name over the common camera CGIs. */
    private fun modelName(ip: String, ports: List<Int>, user: String, pass: String): String? {
        val u = Net.enc(user)
        val p = Net.enc(pass)
        for (port in Net.HTTP_PORTS.filter { it in ports }) {
            Net.get("http://$ip:$port/cgi-bin/hi3510/param.cgi?cmd=getserverinfo&-usr=$u&-pwd=$p", user, pass, 2000)
                ?.takeIf { it.code == 200 && it.text.contains("var ") }?.let { r ->
                    val v = CameraConfig.parseVars(r.text)
                    return listOfNotNull(v["model"], v["name"]).firstOrNull { it.isNotBlank() } ?: "IP camera"
                }
            Net.get("http://$ip:$port/get_status.cgi?loginuse=$u&loginpas=$p", user, pass, 2000)
                ?.takeIf { it.code == 200 && it.text.contains("var ") }?.let { r ->
                    val v = CameraConfig.parseVars(r.text)
                    return listOfNotNull(v["alias"], v["id"]).firstOrNull { it.isNotBlank() } ?: "IP camera"
                }
        }
        return null
    }

    suspend fun scan(
        ctx: Context, user: String, pass: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Found> = coroutineScope {
        val (myIp, prefix) = localIpv4(ctx) ?: throw IllegalStateException("Couldn't read this phone's Wi‑Fi address.")
        val onvifJob = async(Dispatchers.IO) { wsDiscovery(ctx) }
        val hdsJob = async(Dispatchers.IO) { hdsSearch(ctx) }

        val hosts = subnetHosts(myIp, prefix)
        val sem = Semaphore(64)
        val done = AtomicInteger()

        val open: List<Pair<String, List<Int>>> = hosts.map { h ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ports = Net.ALL_PORTS.map { p -> async { if (Net.portOpen(h, p, 450)) p else null } }
                        .awaitAll().filterNotNull()
                    val n = done.incrementAndGet()
                    withContext(Dispatchers.Main) { onProgress(n, hosts.size) }
                    if (ports.isEmpty()) null else h to ports
                }
            }
        }.awaitAll().filterNotNull()

        val onvifHosts = onvifJob.await()
        val hdsHosts = hdsJob.await()
        val portMap = open.toMap()
        val all = (open.map { it.first } + onvifHosts + hdsHosts).distinct().filter { it != myIp }

        all.map { h ->
            async(Dispatchers.IO) {
                val ports = portMap[h] ?: emptyList()
                Found(
                    ip = h,
                    ports = ports,
                    snapshotUrl = Net.findSnapshot(h, ports, user, pass),
                    onvif = h in onvifHosts,
                    announced = h in hdsHosts,
                    model = modelName(h, ports, user, pass)
                )
            }
        }.awaitAll().sortedWith(
            compareByDescending<Found> { it.score }.thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }
        )
    }

    /** Scan and return the most likely camera, or null. */
    suspend fun findBestCamera(ctx: Context, user: String, pass: String): Found? =
        scan(ctx, user, pass) { _, _ -> }.firstOrNull { it.likelyCamera }
}
