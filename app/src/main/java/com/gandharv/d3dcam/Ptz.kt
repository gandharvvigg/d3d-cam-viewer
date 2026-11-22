package com.gandharv.d3dcam

enum class Dir { UP, DOWN, LEFT, RIGHT }

/**
 * Pan/tilt control. Cheap Wi‑Fi cameras use one of a few CGI dialects (or ONVIF);
 * we try each until one answers, then stick with it. Blocking — call off the main thread.
 */
class Ptz(
    private val ip: String,
    private val user: String,
    private val pass: String,
    private val httpPorts: List<Int>,
    private val onvif: Onvif?
) {
    private var method: String? = null
    private var lastDir: Dir? = null

    val workingMethod: String? get() = method

    fun start(d: Dir): Boolean {
        lastDir = d
        return send(d, true)
    }

    fun stop(): Boolean {
        val d = lastDir ?: return false
        return send(d, false)
    }

    private fun send(d: Dir, start: Boolean): Boolean {
        val all = candidates(d, start)
        method?.let { m -> all.firstOrNull { it.first == m }?.let { return it.second() } }
        for ((name, fn) in all) {
            if (fn()) {
                method = name
                return true
            }
        }
        return false
    }

    private fun ok(r: Net.Resp?): Boolean {
        if (r == null || r.code != 200) return false
        val b = r.text
        if (b.contains("<html", true) || b.contains("error", true) || b.contains("fail", true)) return false
        return true
    }

    private fun candidates(d: Dir, start: Boolean): List<Pair<String, () -> Boolean>> {
        val list = mutableListOf<Pair<String, () -> Boolean>>()
        onvif?.let { o ->
            list += "onvif" to {
                if (start) {
                    val (x, y) = when (d) {
                        Dir.UP -> 0f to 0.5f
                        Dir.DOWN -> 0f to -0.5f
                        Dir.LEFT -> -0.5f to 0f
                        Dir.RIGHT -> 0.5f to 0f
                    }
                    o.move(x, y)
                } else o.stop()
            }
        }
        val act = if (start) d.name.lowercase() else "stop"
        val vcmd = when (d) {
            Dir.UP -> 0
            Dir.DOWN -> 2
            Dir.LEFT -> 4
            Dir.RIGHT -> 6
        } + if (start) 0 else 1
        val u = Net.enc(user)
        val p = Net.enc(pass)
        for (port in httpPorts) {
            val base = "http://$ip:$port"
            list += "hi3510web:$port" to {
                ok(Net.get("$base/web/cgi-bin/hi3510/ptzctrl.cgi?-step=0&-act=$act&-speed=45", user, pass))
            }
            list += "hi3510:$port" to {
                ok(Net.get("$base/cgi-bin/hi3510/ptzctrl.cgi?-step=0&-act=$act&-speed=45", user, pass))
            }
            list += "vstar:$port" to {
                ok(Net.get("$base/decoder_control.cgi?loginuse=$u&loginpas=$p&command=$vcmd&onestep=0", user, pass))
            }
            list += "vstar2:$port" to {
                ok(Net.get("$base/decoder_control.cgi?user=$u&pwd=$p&command=$vcmd&onestep=0", user, pass))
            }
        }
        return list
    }
}
