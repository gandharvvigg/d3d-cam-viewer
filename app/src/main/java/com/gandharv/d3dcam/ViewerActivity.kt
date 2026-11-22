package com.gandharv.d3dcam

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import com.gandharv.d3dcam.databinding.ActivityViewerBinding
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

@OptIn(UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private val prefs by lazy { getSharedPreferences("cam", MODE_PRIVATE) }

    private lateinit var ip: String
    private lateinit var user: String
    private lateinit var pass: String
    private var customUrl: String = ""

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var onvifUris: List<String> = emptyList()
    private var mjpegJob: Job? = null
    private var connectJob: Job? = null
    private var lastFrame: Bitmap? = null
    private var ptz: Ptz? = null
    private var muted = false

    private val ptzDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val rtspPaths = listOf(
        "/onvif1", "/11", "/12", "/live/ch0", "/live/ch00_0", "/1", "/0", "/ch0_0.h264",
        "/stream1", "/live.sdp", "/h264", "/videoMain", "/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)

        ip = intent.getStringExtra("ip") ?: ""
        user = intent.getStringExtra("user") ?: "admin"
        pass = intent.getStringExtra("pass") ?: "admin"
        customUrl = intent.getStringExtra("url") ?: ""
        b.camTitle.text = "Camera $ip"

        setupControls()
        applyOrientation(resources.configuration)
        connect()
    }

    // ---------- connection ----------

    private fun status(s: String) {
        b.statusText.visibility = View.VISIBLE
        b.statusText.text = s
    }

    private fun connect() {
        connectJob?.cancel()
        stopVideo()
        connectJob = lifecycleScope.launch { doConnect() }
    }

    private fun withCreds(url: String): String {
        if (!url.startsWith("rtsp://", true) || url.substringAfter("://").substringBefore('/').contains('@')) return url
        return "rtsp://${Net.enc(user)}:${Net.enc(pass)}@" + url.substringAfter("://")
    }

    private fun redact(url: String) = url.replace(Regex("//[^/@]*@"), "//")

    private suspend fun doConnect() {
        status("Checking camera at $ip…")
        val open: List<Int> = withContext(Dispatchers.IO) {
            Net.ALL_PORTS.map { p -> async { if (Net.portOpen(ip, p, 1200)) p else null } }.awaitAll().filterNotNull()
        }
        if (open.isEmpty() && customUrl.isEmpty()) {
            status("Can't reach $ip. Is the phone on the same Wi‑Fi and the camera powered?")
            return
        }
        val httpPorts = Net.HTTP_PORTS.filter { it in open }

        status("Looking for video stream…")
        val onvif = withContext(Dispatchers.IO) { Onvif.connect(ip, open, user, pass) }
        onvifUris = onvif?.streamUris?.map { withCreds(it) } ?: emptyList()
        ptz = Ptz(ip, user, pass, httpPorts, onvif)

        val urls = mutableListOf<String>()
        if (customUrl.startsWith("rtsp", true)) urls += withCreds(customUrl)
        prefs.getString("working_$ip", null)?.let { urls += it }
        urls += onvifUris
        if (554 in open) rtspPaths.forEach { urls += withCreds("rtsp://$ip:554$it") }
        if (10554 in open) {
            urls += withCreds("rtsp://$ip:10554/tcp/av0_0")
            urls += withCreds("rtsp://$ip:10554/udp/av0_0")
        }

        for (u in urls.distinct()) {
            status("Trying ${redact(u)}")
            if (tryPlay(u)) {
                prefs.edit().putString("working_$ip", u).apply()
                status("Live · ${redact(u)}")
                lifecycleScope.launch { delay(4000); if (player != null) b.statusText.visibility = View.GONE }
                return
            }
        }

        // No RTSP — fall back to refreshing still pictures.
        status("No video stream found, trying picture mode…")
        val snap = if (customUrl.startsWith("http", true)) customUrl
        else withContext(Dispatchers.IO) { Net.findSnapshot(ip, open, user, pass) }
        if (snap != null) startMjpeg(snap)
        else status("Couldn't open the camera. Check the username/password, or enter a custom stream URL.")
    }

    private suspend fun tryPlay(url: String): Boolean {
        stopVideo()
        b.mjpegView.visibility = View.GONE
        b.playerView.visibility = View.VISIBLE

        val p = ExoPlayer.Builder(this).build()
        player = p
        b.playerView.player = p
        p.volume = if (muted) 0f else 1f

        val result = CompletableDeferred<Boolean>()
        val probe = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) result.complete(true)
            }
            override fun onRenderedFirstFrame() { result.complete(true) }
            override fun onPlayerError(error: PlaybackException) { result.complete(false) }
        }
        p.addListener(probe)
        val source = RtspMediaSource.Factory()
            .setTimeoutMs(6000)
            .createMediaSource(MediaItem.fromUri(url))
        p.setMediaSource(source)
        p.playWhenReady = true
        p.prepare()

        val ok = withTimeoutOrNull(10_000) { result.await() } ?: false
        p.removeListener(probe)
        if (!ok) {
            stopVideo()
            return false
        }
        currentUrl = url
        p.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                status("Stream dropped — reconnecting…")
                lifecycleScope.launch {
                    delay(2000)
                    if (!isFinishing) connect()
                }
            }
        })
        return true
    }

    private fun startMjpeg(url: String) {
        stopVideo()
        b.playerView.visibility = View.GONE
        b.mjpegView.visibility = View.VISIBLE
        status("Picture mode (no live video stream)")
        mjpegJob = lifecycleScope.launch {
            var fails = 0
            while (isActive) {
                val bmp = withContext(Dispatchers.IO) {
                    Net.get(url, user, pass, 4000)?.takeIf { it.code == 200 && Net.isJpeg(it.body) }
                        ?.let { BitmapFactory.decodeByteArray(it.body, 0, it.body.size) }
                }
                if (bmp != null) {
                    lastFrame = bmp
                    b.mjpegView.setImageBitmap(bmp)
                    fails = 0
                } else if (++fails == 10) {
                    status("Camera stopped responding…")
                }
                delay(150)
            }
        }
    }

    private fun stopVideo() {
        mjpegJob?.cancel()
        mjpegJob = null
        player?.release()
        player = null
        b.playerView.player = null
    }

    // ---------- controls ----------

    @SuppressLint("ClickableViewAccessibility")
    private fun bindPtz(v: View, d: Dir) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    val c = ptz
                    if (c == null) {
                        Toast.makeText(this, "Still connecting…", Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(ptzDispatcher) {
                            val ok = c.start(d)
                            if (!ok) withContext(Dispatchers.Main) {
                                Toast.makeText(this@ViewerActivity, "Camera didn't accept the move command", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    ptz?.let { c -> lifecycleScope.launch(ptzDispatcher) { c.stop() } }
                    if (ev.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> false
            }
        }
    }

    private fun setupControls() {
        bindPtz(b.btnUp, Dir.UP)
        bindPtz(b.btnDown, Dir.DOWN)
        bindPtz(b.btnLeft, Dir.LEFT)
        bindPtz(b.btnRight, Dir.RIGHT)
        b.btnStop.setOnClickListener { ptz?.let { c -> lifecycleScope.launch(ptzDispatcher) { c.stop() } } }

        b.btnReconnect.setOnClickListener { connect() }

        b.btnMute.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            b.btnMute.text = if (muted) "Unmute" else "Mute"
        }

        b.btnQuality.setOnClickListener {
            val cur = currentUrl ?: return@setOnClickListener Toast.makeText(this, "Only works with live video", Toast.LENGTH_SHORT).show()
            val alt = alternateStream(cur)
            if (alt == null) {
                Toast.makeText(this, "No second stream known for this camera", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            connectJob?.cancel()
            connectJob = lifecycleScope.launch {
                status("Switching stream…")
                if (tryPlay(alt)) {
                    prefs.edit().putString("working_$ip", alt).apply()
                    status("Live · ${redact(alt)}")
                } else {
                    status("Other stream failed, going back…")
                    if (!tryPlay(cur)) connect()
                }
            }
        }

        b.btnSnap.setOnClickListener { takePhoto() }
    }

    private fun alternateStream(u: String): String? {
        val i = onvifUris.indexOf(u)
        if (i >= 0 && onvifUris.size > 1) return onvifUris[(i + 1) % onvifUris.size]
        val pairs = listOf(
            "/onvif1" to "/onvif2", "/11" to "/12", "/live/ch0" to "/live/ch1",
            "av0_0" to "av0_1", "/stream1" to "/stream2", "/ch0_0.h264" to "/ch0_1.h264"
        )
        for ((a, c) in pairs) {
            if (u.endsWith(a)) return u.removeSuffix(a) + c
            if (u.endsWith(c)) return u.removeSuffix(c) + a
        }
        return null
    }

    private fun takePhoto() {
        val bmp: Bitmap? = if (player != null) {
            (b.playerView.videoSurfaceView as? TextureView)?.bitmap
        } else lastFrame
        if (bmp == null) {
            Toast.makeText(this, "No picture yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { runCatching { saveBitmap(bmp) }.getOrNull() }
            Toast.makeText(
                this@ViewerActivity,
                if (where != null) "Saved to $where" else "Couldn't save photo",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun saveBitmap(bmp: Bitmap): String {
        val name = "D3DCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".jpg"
        return if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/D3DCam")
            }
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v)
                ?: throw IllegalStateException("insert failed")
            contentResolver.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            "Pictures/D3DCam"
        } else {
            val dir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
            val f = File(dir, name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            f.absolutePath
        }
    }

    // ---------- layout ----------

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyOrientation(newConfig)
    }

    private fun applyOrientation(cfg: Configuration) {
        val landscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lp = b.videoFrame.layoutParams
        val ctl = WindowInsetsControllerCompat(window, b.root)
        if (landscape) {
            b.controls.visibility = View.GONE
            lp.height = android.view.ViewGroup.LayoutParams.MATCH_PARENT
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ctl.hide(WindowInsetsCompat.Type.systemBars())
            ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            b.controls.visibility = View.VISIBLE
            lp.height = resources.displayMetrics.widthPixels * 9 / 16
            WindowCompat.setDecorFitsSystemWindows(window, true)
            ctl.show(WindowInsetsCompat.Type.systemBars())
        }
        b.videoFrame.layoutParams = lp
    }

    override fun onStop() {
        super.onStop()
        connectJob?.cancel()
        stopVideo()
    }

    override fun onRestart() {
        super.onRestart()
        connect()
    }

    override fun onDestroy() {
        super.onDestroy()
        ptzDispatcher.close()
    }
}
