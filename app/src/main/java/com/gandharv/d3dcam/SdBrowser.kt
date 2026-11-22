package com.gandharv.d3dcam

import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** A video file on the camera's SD card. */
data class Recording(
    val url: String,
    val name: String,
    val start: Date?,
    val end: Date?,
    val alarm: Boolean,
    val dayKey: String
) {
    val isPlayable: Boolean get() = EXT.any { name.endsWith(it, true) }

    companion object {
        val EXT = listOf(".264", ".265", ".h264", ".mp4", ".avi")
    }
}

/**
 * Walks the camera's /sd/ web folder (date folders → record folders → files) and
 * collects the video files. Blocking — call on Dispatchers.IO.
 */
class SdBrowser(private val user: String, private val pass: String) {

    private val href = Regex("""href\s*=\s*["']([^"'?#]+)["']""", RegexOption.IGNORE_CASE)

    fun findRoot(candidates: List<String>): String? {
        for (root in candidates) {
            val r = Net.get(root, user, pass, 4000) ?: continue
            if (r.code == 200 && href.containsMatchIn(r.text)) return root
        }
        return null
    }

    fun list(root: String, onProgress: (String) -> Unit = {}): List<Recording> {
        val out = mutableListOf<Recording>()
        crawl(root, root, 0, out, onProgress)
        // Newest first: by the time in the file name, then by the numbers in the folder/file path
        // (dates and counters), so files with unreadable names still come out newest first.
        return out.sortedWith(
            compareByDescending<Recording> { it.start?.time ?: 0L }
                .thenByDescending { pathKey(it.url) }
                .thenByDescending { it.url }
        )
    }

    private fun pathKey(url: String): String =
        Regex("""\d+""").findAll(url.substringAfter("/sd/")).joinToString("") { it.value.padStart(14, '0') }

    private fun crawl(root: String, url: String, depth: Int, out: MutableList<Recording>, onProgress: (String) -> Unit) {
        if (depth > 3) return
        val r = Net.get(url, user, pass, 8000) ?: return
        if (r.code != 200) return
        val base = URI(url)
        val links = href.findAll(r.text).map { it.groupValues[1] }.distinct().toList()
        for (l in links) {
            if (l.startsWith("..") || l == "/" || l.startsWith("javascript", true) || l.startsWith("mailto", true)) continue
            val abs = try { base.resolve(l).toString() } catch (_: Exception) { continue }
            if (!abs.startsWith(root)) continue
            if (abs.length <= url.length) continue      // parent / self
            if (abs.endsWith("/")) {
                onProgress(abs.removePrefix(root).trimEnd('/'))
                crawl(root, abs, depth + 1, out, onProgress)
            } else {
                val name = abs.substringAfterLast('/')
                if (Recording.EXT.any { name.endsWith(it, true) }) out += parse(abs, name)
            }
        }
    }

    private fun parse(url: String, name: String): Recording {
        // Common names: P251122_100000_101000.264 (P = plan/continuous, A = alarm)
        //               20251122100000.avi, rec_20251122_100000.mp4
        var start: Date? = null
        var end: Date? = null
        Regex("""([PAMS])?(\d{6})_(\d{6})_(\d{6})""").find(name)?.let { m ->
            val d = m.groupValues[2]
            start = parseDate("20$d${m.groupValues[3]}", "yyyyMMddHHmmss")
            end = parseDate("20$d${m.groupValues[4]}", "yyyyMMddHHmmss")
        }
        if (start == null) Regex("""(20\d{6})[_-]?(\d{6})""").find(name)?.let { m ->
            start = parseDate(m.groupValues[1] + m.groupValues[2], "yyyyMMddHHmmss")
        }
        if (start == null) Regex("""/(20\d{6})/""").find(url)?.let { m ->
            start = parseDate(m.groupValues[1] + "000000", "yyyyMMddHHmmss")
        }
        val s0 = start
        val e0 = end
        if (s0 != null && e0 != null && e0.before(s0)) end = Date(e0.time + 24 * 3600_000L)
        val alarm = name.startsWith("A", true) || url.contains("alarm", true)
        val day = start?.let { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(it) } ?: "Unknown date"
        return Recording(url, name, start, end, alarm, day)
    }

    private fun parseDate(s: String, f: String): Date? = try {
        SimpleDateFormat(f, Locale.US).apply { isLenient = false }.parse(s)
    } catch (_: Exception) { null }
}
