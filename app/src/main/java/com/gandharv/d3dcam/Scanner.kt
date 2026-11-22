package com.gandharv.d3dcam

import android.content.Context
import android.net.ConnectivityManager
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
    val onvif: Boolean
) {
    val score: Int
        get() = (if (snapshotUrl != null) 4 else 0) + (if (onvif) 3 else 0) +
                (if (554 in ports || 10554 in ports) 2 else 0)
    val likelyCamera: Boolean get() = score >= 2
}

object Scanner {

    fun onWifi(ctx: Context): Boolean {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun localIpv4(ctx: Context): String? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        for (la in lp.linkAddresses) {
            val a = la.address
            if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress
        }
        return null
    }

    /** ONVIF WS-Discovery: ask every ONVIF camera on the LAN to announce itself. */
    private fun wsDiscovery(ctx: Context): Set<String> {
        val found = mutableSetOf<String>()
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val lock = wifi.createMulticastLock("onvif-discovery").apply {
            setReferenceCounted(false); acquire()
        }
        try {
            DatagramSocket().use { s ->
                s.soTimeout = 500
                val msg = """<?xml version="1.0" encoding="UTF-8"?><e:Envelope xmlns:e="http://www.w3.org/2003/05/soap-envelope" xmlns:w="http://schemas.xmlsoap.org/ws/2004/08/addressing" xmlns:d="http://schemas.xmlsoap.org/ws/2005/04/discovery" xmlns:dn="http://www.onvif.org/ver10/network/wsdl"><e:Header><w:MessageID>uuid:${UUID.randomUUID()}</w:MessageID><w:To e:mustUnderstand="true">urn:schemas-xmlsoap-org:ws:2005:04:discovery</w:To><w:Action e:mustUnderstand="true">http://schemas.xmlsoap.org/ws/2005/04/discovery/Probe</w:Action></e:Header><e:Body><d:Probe><d:Types>dn:NetworkVideoTransmitter</d:Types></d:Probe></e:Body></e:Envelope>"""
                val data = msg.toByteArray()
                val group = InetAddress.getByName("239.255.255.250")
                repeat(2) { s.send(DatagramPacket(data, data.size, group, 3702)) }
                val buf = ByteArray(65535)
                val end = System.currentTimeMillis() + 3000
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

    suspend fun scan(
        ctx: Context, user: String, pass: String,
        onProgress: (done: Int, total: Int) -> Unit
    ): List<Found> = coroutineScope {
        val myIp = localIpv4(ctx) ?: throw IllegalStateException("Couldn't read this phone's Wi‑Fi address.")
        val base = myIp.substringBeforeLast('.')
        val onvifJob = async(Dispatchers.IO) { wsDiscovery(ctx) }

        val hosts = (1..254).map { "$base.$it" }.filter { it != myIp }
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
        val all = (open.map { it.first } + onvifHosts).distinct()
        val portMap = open.toMap()

        all.map { h ->
            async(Dispatchers.IO) {
                val ports = portMap[h] ?: emptyList()
                Found(h, ports, Net.findSnapshot(h, ports, user, pass), h in onvifHosts)
            }
        }.awaitAll().sortedWith(compareByDescending<Found> { it.score }.thenBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 })
    }
}
