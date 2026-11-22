package com.gandharv.d3dcam

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer

/**
 * Downloads a recording from the camera and saves it to the phone's gallery (Movies/HiCam).
 * HXVS ".264" files are re-packaged into a standard MP4 (video only) so any player can open them.
 */
object ClipSaver {

    private val START = byteArrayOf(0, 0, 0, 1)

    fun save(ctx: Context, url: String, user: String, pass: String, onProgress: (Int) -> Unit): String {
        val name = url.substringAfterLast('/').substringBeforeLast('.')
        val isHx = url.endsWith(".264", true) || url.endsWith(".265", true) || url.endsWith(".h264", true)
        val tmpIn = File(ctx.cacheDir, "dl_${System.currentTimeMillis()}")
        try {
            download(url, user, pass, tmpIn, onProgress)
            return if (isHx) {
                val tmpOut = File(ctx.cacheDir, "$name.mp4")
                remux(tmpIn, tmpOut)
                publish(ctx, tmpOut, "$name.mp4", "video/mp4").also { tmpOut.delete() }
            } else {
                val ext = url.substringAfterLast('.', "mp4").lowercase()
                publish(ctx, tmpIn, "$name.$ext", if (ext == "avi") "video/x-msvideo" else "video/mp4")
            }
        } finally {
            tmpIn.delete()
        }
    }

    private fun download(url: String, user: String, pass: String, to: File, onProgress: (Int) -> Unit) {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 20000
        c.setRequestProperty("Authorization", "Basic " +
                android.util.Base64.encodeToString("$user:$pass".toByteArray(), android.util.Base64.NO_WRAP))
        try {
            if (c.responseCode != 200) throw IllegalStateException("Camera answered ${c.responseCode}")
            val total = c.contentLengthLong
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) { lastPct = pct; onProgress(pct) }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun le32(b: ByteArray) =
        (b[0].toInt() and 0xFF) or ((b[1].toInt() and 0xFF) shl 8) or
                ((b[2].toInt() and 0xFF) shl 16) or ((b[3].toInt() and 0xFF) shl 24)

    private class Chunk(val tag: String, val ts: Long, val data: ByteArray)

    private fun chunks(input: InputStream) = sequence {
        val d = DataInputStream(BufferedInputStream(input, 256 * 1024))
        val h = ByteArray(16)
        d.readFully(h)  // file header
        while (true) {
            try { d.readFully(h) } catch (_: Exception) { break }
            val tag = String(h, 0, 4, Charsets.US_ASCII)
            if (tag != "HXVF" && tag != "HXAF") break
            val size = le32(h.copyOfRange(4, 8))
            if (size < 0 || size > 4 * 1024 * 1024) break
            val ts = le32(h.copyOfRange(8, 12)).toLong() and 0xFFFFFFFFL
            val data = ByteArray(size)
            try { d.readFully(data) } catch (_: Exception) { break }
            yield(Chunk(tag, ts, data))
        }
    }

    private fun nals(data: ByteArray): List<ByteArray> {
        val out = ArrayList<ByteArray>()
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 && data[i + 2].toInt() == 1) { starts += i + 3; i += 3 }
            else i++
        }
        if (starts.isEmpty()) return listOf(data)
        for (k in starts.indices) {
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
            while (e > starts[k] && data[e - 1].toInt() == 0 && k + 1 < starts.size) e--
            if (e > starts[k]) out += data.copyOfRange(starts[k], e)
        }
        return out
    }

    private fun remux(src: File, dst: File) {
        val hevc = src.inputStream().use { val b = ByteArray(4); it.read(b); String(b, Charsets.US_ASCII) == "HXVT" }
        val (w, h) = src.inputStream().use {
            val b = ByteArray(16); it.read(b)
            le32(b.copyOfRange(4, 8)) to le32(b.copyOfRange(8, 12))
        }
        var vps: ByteArray? = null
        var sps: ByteArray? = null
        var pps: ByteArray? = null

        // First pass: find parameter sets.
        src.inputStream().use { inp ->
            for (c in chunks(inp)) {
                if (c.tag != "HXVF") continue
                for (n in nals(c.data)) {
                    if (hevc) when ((n[0].toInt() shr 1) and 0x3F) { 32 -> vps = n; 33 -> sps = n; 34 -> pps = n }
                    else when (n[0].toInt() and 0x1F) { 7 -> sps = n; 8 -> pps = n }
                }
                if (sps != null && pps != null && (!hevc || vps != null)) break
            }
        }
        val s = sps ?: throw IllegalStateException("No video found in this recording")
        val p = pps ?: throw IllegalStateException("No video found in this recording")

        val fmt = MediaFormat.createVideoFormat(if (hevc) "video/hevc" else "video/avc", if (w > 0) w else 1280, if (h > 0) h else 720)
        if (hevc) fmt.setByteBuffer("csd-0", ByteBuffer.wrap(START + vps!! + START + s + START + p))
        else {
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(START + s))
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(START + p))
        }
        val mux = MediaMuxer(dst.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val track = mux.addTrack(fmt)
        mux.start()
        val info = MediaCodec.BufferInfo()
        var base = -1L
        var lastPts = -1L
        val au = ByteArrayOutputStream()
        var auTs = -1L
        var auKey = false
        var started = false

        fun flush() {
            if (au.size() == 0) return
            val bytes = au.toByteArray(); au.reset()
            val key = auKey; auKey = false
            if (!started && !key) return
            started = true
            if (base < 0) base = auTs
            var pts = (auTs - base) * 1000
            if (pts <= lastPts) pts = lastPts + 1
            lastPts = pts
            info.set(0, bytes.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
            mux.writeSampleData(track, ByteBuffer.wrap(bytes), info)
        }

        try {
            src.inputStream().use { inp ->
                for (c in chunks(inp)) {
                    if (c.tag != "HXVF") continue
                    if (auTs >= 0 && c.ts != auTs) flush()
                    auTs = c.ts
                    for (n in nals(c.data)) {
                        val t = if (hevc) (n[0].toInt() shr 1) and 0x3F else n[0].toInt() and 0x1F
                        val isParam = if (hevc) t in 32..34 else t == 7 || t == 8
                        if (isParam) continue
                        if ((hevc && t in 16..21) || (!hevc && t == 5)) auKey = true
                        au.write(START); au.write(n)
                    }
                }
                flush()
            }
        } finally {
            try { mux.stop() } catch (_: Exception) {}
            mux.release()
        }
    }

    private fun publish(ctx: Context, file: File, name: String, mime: String): String {
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, mime)
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/HiCam")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("Couldn't create file")
            ctx.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            "Movies/HiCam/$name"
        } else {
            val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_MOVIES), "HiCam").apply { mkdirs() }
            val f = File(dir, name)
            file.copyTo(f, overwrite = true)
            f.absolutePath
        }
    }
}
