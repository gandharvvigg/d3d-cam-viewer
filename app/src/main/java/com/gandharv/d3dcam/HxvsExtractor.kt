package com.gandharv.d3dcam

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import java.io.ByteArrayOutputStream

/**
 * Reads the HXVS/HXVT ".264/.265" files that HiChip cameras write to their SD card.
 *
 * Layout (little-endian):
 *   "HXVS"|"HXVT" width height pad                      — 16-byte file header
 *   "HXVF" size ts_ms flags  + size bytes of H.264/H.265  — video (one NAL unit per chunk)
 *   "HXAF" size ts_ms flags  + 4-byte prefix + G.711 A-law (size-4 bytes)
 *   "HXFI" size duration flags + 200000-byte table of (offset, time_ms) pairs at the end
 */
@UnstableApi
class HxvsExtractor : Extractor {

    companion object {
        private const val HXVS = 0x53565848  // "HXVS"
        private const val HXVT = 0x54565848  // "HXVT"
        private const val HXVF = 0x46565848  // "HXVF"
        private const val HXAF = 0x46415848  // "HXAF"
        private const val HXFI = 0x49465848  // "HXFI"
        private const val INDEX_BYTES = 200_000
        private const val MAX_CHUNK = 4 * 1024 * 1024
        private val START = byteArrayOf(0, 0, 0, 1)

        private fun le32(b: ByteArray, o: Int) =
            (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or
                    ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

        val factory: ExtractorsFactory = ExtractorsFactory {
            arrayOf<Extractor>(HxvsExtractor()) + DefaultExtractorsFactory().createExtractors()
        }
    }

    private lateinit var output: ExtractorOutput
    private var video: TrackOutput? = null
    private var audio: TrackOutput? = null

    private var hevc = false
    private var width = 0
    private var height = 0
    private var headerDone = false
    private var tracksDone = false
    private var indexState = 0           // 0 = not tried, 1 = seeking to it, 2 = done
    private var seekMapSent = false
    private var baseTs = -1L

    private var videoFormatSent = false
    private var vps: ByteArray? = null
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    private val au = ByteArrayOutputStream()
    private var auTs = -1L
    private var auKey = false
    private var needKey = true

    private val hdr = ByteArray(16)

    override fun sniff(input: ExtractorInput): Boolean {
        val b = ByteArray(4)
        if (!input.peekFully(b, 0, 4, true)) return false
        val tag = le32(b, 0)
        return tag == HXVS || tag == HXVT
    }

    override fun init(output: ExtractorOutput) {
        this.output = output
    }

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        if (!headerDone) {
            input.readFully(hdr, 0, 16)
            hevc = le32(hdr, 0) == HXVT
            width = le32(hdr, 4)
            height = le32(hdr, 8)
            headerDone = true
            if (!tracksDone) {
                tracksDone = true
                setupTracks(input)
            }
            val len = input.length
            if (indexState == 0 && len != C.LENGTH_UNSET.toLong() && len > INDEX_BYTES + 64) {
                indexState = 1
                seekPosition.position = len - (INDEX_BYTES + 16)
                return Extractor.RESULT_SEEK
            }
            indexState = 2
            sendUnseekable()
        }
        if (indexState == 1) {
            indexState = 2
            readIndex(input)
            seekPosition.position = 16
            return Extractor.RESULT_SEEK
        }
        if (!seekMapSent) sendUnseekable()
        return readChunk(input)
    }

    /** Peeks at the start of the file to find out whether it has audio. */
    private fun setupTracks(input: ExtractorInput) {
        var hasAudio = false
        var peeked = 0
        val h = ByteArray(16)
        try {
            for (i in 0 until 400) {
                if (!input.peekFully(h, 0, 16, true)) break
                val tag = le32(h, 0)
                val size = le32(h, 4)
                if (tag == HXAF) hasAudio = true
                if (baseTs < 0 && (tag == HXVF || tag == HXAF)) baseTs = le32(h, 8).toLong() and 0xFFFFFFFFL
                if ((tag != HXVF && tag != HXAF) || size < 0 || size > MAX_CHUNK) break
                input.advancePeekPosition(size)
                peeked += 16 + size
                if (hasAudio || peeked > 2_000_000) break
            }
        } catch (_: Exception) {
        }
        input.resetPeekPosition()

        video = output.track(0, C.TRACK_TYPE_VIDEO)
        if (hasAudio) {
            audio = output.track(1, C.TRACK_TYPE_AUDIO).also {
                it.format(
                    Format.Builder()
                        .setSampleMimeType(MimeTypes.AUDIO_ALAW)
                        .setChannelCount(1)
                        .setSampleRate(8000)
                        .build()
                )
            }
        }
        output.endTracks()
    }

    private fun sendUnseekable() {
        if (seekMapSent) return
        seekMapSent = true
        output.seekMap(SeekMap.Unseekable(C.TIME_UNSET))
    }

    private fun readIndex(input: ExtractorInput) {
        val h = ByteArray(16)
        if (!input.readFully(h, 0, 16, true) || le32(h, 0) != HXFI) {
            sendUnseekable(); return
        }
        val durationMs = le32(h, 8).toLong() and 0xFFFFFFFFL
        val table = ByteArray(INDEX_BYTES)
        if (!input.readFully(table, 0, INDEX_BYTES, true)) {
            sendUnseekable(); return
        }
        val times = ArrayList<Long>()
        val offsets = ArrayList<Long>()
        var lastTime = -1L
        var i = 0
        while (i + 8 <= INDEX_BYTES) {
            val off = le32(table, i).toLong() and 0xFFFFFFFFL
            val t = le32(table, i + 4).toLong() and 0xFFFFFFFFL
            if (off == 0L) break
            if (t != lastTime) {
                times += t * 1000
                offsets += off
                lastTime = t
            }
            i += 8
        }
        if (times.isEmpty()) {
            sendUnseekable(); return
        }
        val durUs = if (durationMs > 0) durationMs * 1000 else times.last()
        seekMapSent = true
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = durUs
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                var lo = 0
                var hi = times.size - 1
                while (lo < hi) {
                    val mid = (lo + hi + 1) / 2
                    if (times[mid] <= timeUs) lo = mid else hi = mid - 1
                }
                val p = SeekPoint(times[lo], offsets[lo])
                return if (lo + 1 < times.size) SeekMap.SeekPoints(p, SeekPoint(times[lo + 1], offsets[lo + 1]))
                else SeekMap.SeekPoints(p)
            }
        })
    }

    private fun timeUs(ts: Long): Long {
        if (baseTs < 0) baseTs = ts
        return ((ts - baseTs).coerceAtLeast(0)) * 1000
    }

    private fun readChunk(input: ExtractorInput): Int {
        if (!input.readFully(hdr, 0, 16, true)) {
            flushAu(); return Extractor.RESULT_END_OF_INPUT
        }
        val tag = le32(hdr, 0)
        val size = le32(hdr, 4)
        val ts = le32(hdr, 8).toLong() and 0xFFFFFFFFL
        val flags = le32(hdr, 12)
        when (tag) {
            HXVF -> {
                if (size < 0 || size > MAX_CHUNK) { flushAu(); return Extractor.RESULT_END_OF_INPUT }
                val data = ByteArray(size)
                input.readFully(data, 0, size)
                if (auTs >= 0 && ts != auTs) flushAu()
                auTs = ts
                if (flags == 1) auKey = true
                addNals(data)
            }
            HXAF -> {
                if (size < 4 || size > MAX_CHUNK) { flushAu(); return Extractor.RESULT_END_OF_INPUT }
                input.skipFully(4)
                val n = size - 4
                val a = audio
                if (a == null || needKey) {
                    input.skipFully(n)
                } else {
                    a.sampleData(input, n, false).let { var left = n - it; while (left > 0) left -= a.sampleData(input, left, false) }
                    a.sampleMetadata(timeUs(ts), C.BUFFER_FLAG_KEY_FRAME, n, 0, null)
                }
            }
            HXFI -> { flushAu(); return Extractor.RESULT_END_OF_INPUT }
            else -> {
                // Lost sync — give up on this file rather than play garbage.
                flushAu(); return Extractor.RESULT_END_OF_INPUT
            }
        }
        return Extractor.RESULT_CONTINUE
    }

    /** Splits a chunk into NAL units, keeps parameter sets, appends Annex-B data to the access unit. */
    private fun addNals(data: ByteArray) {
        val starts = ArrayList<Int>()   // index of first byte after each start code
        var i = 0
        while (i + 3 <= data.size) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0 &&
                (data[i + 2].toInt() == 1 || (i + 3 < data.size && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1))
            ) {
                val s = if (data[i + 2].toInt() == 1) i + 3 else i + 4
                starts += s
                i = s
            } else i++
        }
        if (starts.isEmpty()) {
            handleNal(data, 0, data.size)
            return
        }
        for (k in starts.indices) {
            val s = starts[k]
            var e = if (k + 1 < starts.size) starts[k + 1] - 3 else data.size
            if (k + 1 < starts.size && e > s && data[e - 1].toInt() == 0) e--   // 4-byte start code
            if (e > s) handleNal(data, s, e)
        }
    }

    private fun handleNal(d: ByteArray, s: Int, e: Int) {
        val nal = d.copyOfRange(s, e)
        if (nal.isEmpty()) return
        if (hevc) {
            when ((nal[0].toInt() shr 1) and 0x3F) {
                32 -> vps = nal
                33 -> sps = nal
                34 -> pps = nal
                in 16..21 -> auKey = true
            }
        } else {
            when (nal[0].toInt() and 0x1F) {
                7 -> sps = nal
                8 -> pps = nal
                5 -> auKey = true
            }
        }
        au.write(START)
        au.write(nal)
    }

    private fun sendVideoFormat(): Boolean {
        if (videoFormatSent) return true
        val s = sps ?: return false
        val p = pps ?: return false
        val init: List<ByteArray> = if (hevc) {
            val v = vps ?: return false
            listOf(START + v + START + s + START + p)
        } else listOf(START + s, START + p)
        video?.format(
            Format.Builder()
                .setSampleMimeType(if (hevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264)
                .setWidth(if (width in 16..8192) width else Format.NO_VALUE)
                .setHeight(if (height in 16..8192) height else Format.NO_VALUE)
                .setInitializationData(init)
                .build()
        )
        videoFormatSent = true
        return true
    }

    private fun flushAu() {
        if (au.size() == 0) { auKey = false; return }
        val bytes = au.toByteArray()
        au.reset()
        val key = auKey
        auKey = false
        val t = auTs
        if (!sendVideoFormat()) return
        if (needKey && !key) return
        needKey = false
        val v = video ?: return
        v.sampleData(ParsableByteArray(bytes), bytes.size)
        v.sampleMetadata(timeUs(t), if (key) C.BUFFER_FLAG_KEY_FRAME else 0, bytes.size, 0, null)
    }

    override fun seek(position: Long, timeUs: Long) {
        au.reset()
        auTs = -1
        auKey = false
        needKey = true
        if (position == 0L) {
            headerDone = false
        }
    }

    override fun release() {}
}
