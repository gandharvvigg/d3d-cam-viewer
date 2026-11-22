package com.gandharv.d3dcam

import android.content.Context
import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * HiChip "Smart Link" Wi‑Fi provisioning, as used by D3D / CamHi-type cameras.
 *
 * A freshly reset camera listens to Wi‑Fi traffic. The phone (on the home Wi‑Fi) sends tiny UDP
 * packets to multicast groups 228.x.y.z — the camera can see the group address of each packet
 * even without being on the network, and the Wi‑Fi name and password are encoded in those
 * addresses, two bytes at a time:
 *
 *   228.84.85.(N+M+3)                 header: N = ssid byte pairs, M = password byte pairs
 *   228.(16+i).(s[2i-2]).(s[2i-1])    ssid pairs, i = 1..N
 *   228.(N+17).0.0                    separator
 *   228.(N+17+i).(p[2i-2]).(p[2i-1])  password pairs, i = 1..M
 *   228.(N+M+18).0.0                  separator
 *   228.(N+M+19).0.(security)         end, security 4 = WPA/WPA2
 *
 * Each address gets two 20-byte packets to port 12345, with a 50 ms pause after most of them,
 * and the whole round repeats every ~20 ms until stopped.
 */
object HiSmartLink {

    private const val PORT = 12345
    private const val SECURITY_WPA = 4

    private class Step(val addr: String, val pauseAfter: Boolean)

    private fun pairs(b: ByteArray): List<Pair<Int, Int>> {
        val n = (b.size + 1) / 2
        return (0 until n).map { i ->
            val a = b[2 * i].toInt() and 0xFF
            val c = if (2 * i + 1 < b.size) b[2 * i + 1].toInt() and 0xFF else 0
            a to c
        }
    }

    private fun steps(ssid: String, pass: String): List<Step> {
        val s = pairs(ssid.toByteArray(Charsets.UTF_8))
        val p = pairs(pass.toByteArray(Charsets.UTF_8))
        val n = s.size
        val m = p.size
        val out = mutableListOf<Step>()
        out += Step("228.84.85.${n + m + 3}", true)
        s.forEachIndexed { idx, (a, b) -> out += Step("228.${16 + idx + 1}.$a.$b", true) }
        out += Step("228.${n + 17}.0.0", false)
        p.forEachIndexed { idx, (a, b) -> out += Step("228.${n + 17 + idx + 1}.$a.$b", true) }
        out += Step("228.${n + m + 18}.0.0", false)
        out += Step("228.${n + m + 19}.0.$SECURITY_WPA", true)
        return out
    }

    /** Returns an error message, or null if the details can be sent. */
    fun validate(ssid: String, pass: String): String? {
        val sb = ssid.toByteArray(Charsets.UTF_8).size
        return when {
            ssid.isBlank() -> "Enter your Wi‑Fi name"
            sb > 32 -> "Wi‑Fi name is too long"
            pass.length < 8 -> "Smart Link needs a Wi‑Fi password of at least 8 characters"
            pass.toByteArray(Charsets.UTF_8).size > 64 -> "Wi‑Fi password is too long"
            (sb + 1) / 2 + 17 + (pass.toByteArray().size + 1) / 2 + 3 > 127 -> "Wi‑Fi name + password are too long for Smart Link"
            else -> null
        }
    }

    /**
     * Sends the Wi‑Fi details until the coroutine is cancelled or [durationMs] passes.
     * [network] should be the Wi‑Fi network the camera must join.
     */
    suspend fun send(
        ctx: Context, network: Network?, ssid: String, pass: String,
        durationMs: Long, onRound: (round: Int) -> Unit
    ) = withContext(Dispatchers.IO) {
        val plan = steps(ssid, pass).map { InetAddress.getByName(it.addr) to it.pauseAfter }
        val payload = ByteArray(20)
        val end = System.currentTimeMillis() + durationMs
        DatagramSocket().use { sock ->
            sock.reuseAddress = true
            sock.broadcast = true
            try { network?.bindSocket(sock) } catch (_: Exception) {}
            var round = 0
            while (isActive && System.currentTimeMillis() < end) {
                for ((addr, pause) in plan) {
                    if (!isActive) break
                    repeat(2) {
                        try { sock.send(DatagramPacket(payload, payload.size, addr, PORT)) } catch (_: Exception) {}
                    }
                    if (pause) delay(50)
                }
                round++
                withContext(Dispatchers.Main) { onRound(round) }
                delay(20)
            }
        }
    }
}
