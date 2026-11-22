package com.gandharv.d3dcam

import android.annotation.SuppressLint
import android.app.PictureInPictureParams
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Rational
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
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

/** Full-screen live view with overlay controls, pinch zoom, pan/tilt and picture-in-picture. */
@OptIn(UnstableApi::class)
class ViewerActivity : AppCompatActivity() {

    private lateinit var b: ActivityViewerBinding
    private lateinit var store: CameraStore
    private lateinit var cam: Camera

    private var player: ExoPlayer? = null
    private var currentUrl: String? = null
    private var onvifUris: List<String> = emptyList()
    private var mjpegJob: Job? = null
    private var connectJob: Job? = null
    private var hideJob: Job? = null
    private var lastFrame: Bitmap? = null
    private var ptz: Ptz? = null
    private var muted = false
    private var relocated = false
    private var live = false
    private var inPip = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f

    private val ptzDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private val rtspPaths = listOf(
        "/11", "/12", "/onvif1", "/live/ch0", "/live/ch00_0", "/1", "/0", "/ch0_0.h264",
        "/stream1", "/live.sdp", "/h264", "/videoMain", "/"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CameraStore(this)
        cam = store.get(intent.getStringExtra("camId")) ?: run { finish(); return }
        store.lastId = cam.id

        b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        goImmersive()
        val prefs = getSharedPreferences("cam", MODE_PRIVATE)
        requestedOrientation = if (prefs.getBoolean("landscape", true))
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        b.ptzPad.visibility = if (prefs.getBoolean("showPtz", true)) View.VISIBLE else View.GONE

        b.camTitle.text = cam.name
        setupControls()
        setupGestures()
        showOverlay()
        connect()
        checkRecording()
    }

    private fun goImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, b.root).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ---------------- connection ----------------

    private fun status(s: String?, spinner: Boolean = false) {
        b.statusText.visibility = if (s.isNullOrEmpty() || inPip) View.GONE else View.VISIBLE
        b.statusText.text = s ?: ""
        b.spinner.visibility = if (spinner) View.VISIBLE else View.GONE
    }

    private fun connect() {
        connectJob?.cancel()
        stopVideo()
        live = false
        connectJob = lifecycleScope.launch { doConnect() }
    }

    private fun withCreds(url: String): String {
        if (!url.startsWith("rtsp://", true) || url.substringAfter("://").substringBefore('/').contains('@')) return url
        return "rtsp://${Net.enc(cam.user)}:${Net.enc(cam.pass)}@" + url.substringAfter("://")
    }

    private fun redact(url: String) = url.replace(Regex("//[^/@]*@"), "//")

    private suspend fun doConnect() {
        status("Connecting to ${cam.name}…", true)

        // Fast path: the stream that worked last time.
        cam.streamUrl?.let { saved ->
            if (tryPlay(withCreds(saved))) { onLive(saved); afterLive(); return }
        }

        if (!Scanner.onWifi(this)) {
            status("This phone isn't on Wi‑Fi. Connect to the same network as the camera.")
            return
        }
        val ip = cam.ip
        val open: List<Int> = withContext(Dispatchers.IO) {
            Net.ALL_PORTS.map { p -> async { if (Net.portOpen(ip, p, 1200)) p else null } }.awaitAll().filterNotNull()
        }
        if (open.isEmpty() && cam.customUrl.isEmpty()) {
            if (!relocated) {
                relocated = true
                status("Camera not at $ip — searching your Wi‑Fi…", true)
                val best = try { Scanner.findBestCamera(this, cam.user, cam.pass) } catch (e: Exception) { null }
                if (best != null && best.ip != ip) {
                    cam.ip = best.ip
                    cam.streamUrl = null
                    store.upsert(cam)
                    doConnect()
                    return
                }
            }
            status("Can't reach the camera. Check it's powered and on this Wi‑Fi, then tap ↻.")
            return
        }
        val httpPorts = Net.HTTP_PORTS.filter { it in open }

        status("Finding the video stream…", true)
        val onvif = withContext(Dispatchers.IO) { Onvif.connect(ip, open, cam.user, cam.pass) }
        onvifUris = onvif?.streamUris?.map { withCreds(it) } ?: emptyList()
        ptz = Ptz(ip, cam.user, cam.pass, httpPorts, onvif)

        val urls = mutableListOf<String>()
        if (cam.customUrl.startsWith("rtsp", true)) urls += withCreds(cam.customUrl)
        if (554 in open) rtspPaths.forEach { urls += withCreds("rtsp://$ip:554$it") }
        urls += onvifUris
        if (10554 in open) {
            urls += withCreds("rtsp://$ip:10554/tcp/av0_0")
            urls += withCreds("rtsp://$ip:10554/udp/av0_0")
        }

        for (u in urls.distinct()) {
            status("Trying ${redact(u)}", true)
            if (tryPlay(u)) { onLive(u); return }
        }

        status("No video stream found, using picture mode…", true)
        val snap = if (cam.customUrl.startsWith("http", true)) cam.customUrl
        else withContext(Dispatchers.IO) { Net.findSnapshot(ip, open, cam.user, cam.pass) }
        if (snap != null) startMjpeg(snap)
        else status("Couldn't open the video. Check the camera password in Camera settings, then tap ↻.")
    }

    private fun onLive(url: String) {
        live = true
        cam.streamUrl = redact(url)
        store.upsert(cam)
        status(null)
        b.btnQuality.text = if (isSubStream(url)) "SD" else "HD"
    }

    /** After the fast path, set up pan/tilt in the background. */
    private fun afterLive() {
        lifecycleScope.launch {
            val ip = cam.ip
            val open = withContext(Dispatchers.IO) {
                Net.ALL_PORTS.map { p -> async { if (Net.portOpen(ip, p, 1200)) p else null } }.awaitAll().filterNotNull()
            }
            val onvif = withContext(Dispatchers.IO) { Onvif.connect(ip, open, cam.user, cam.pass) }
            onvifUris = onvif?.streamUris?.map { withCreds(it) } ?: emptyList()
            ptz = Ptz(ip, cam.user, cam.pass, Net.HTTP_PORTS.filter { it in open }, onvif)
        }
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
        p.setMediaSource(
            RtspMediaSource.Factory().setTimeoutMs(6000).createMediaSource(MediaItem.fromUri(url))
        )
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
                live = false
                status("Connection lost — reconnecting…", true)
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
        live = true
        status(null)
        mjpegJob = lifecycleScope.launch {
            var fails = 0
            while (isActive) {
                val bmp = withContext(Dispatchers.IO) {
                    Net.get(url, cam.user, cam.pass, 4000)?.takeIf { it.code == 200 && Net.isJpeg(it.body) }
                        ?.let { BitmapFactory.decodeByteArray(it.body, 0, it.body.size) }
                }
                if (bmp != null) {
                    lastFrame = bmp
                    b.mjpegView.setImageBitmap(bmp)
                    if (fails >= 10) status(null)
                    fails = 0
                } else if (++fails == 10) {
                    status("Camera stopped responding…", true)
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

    private fun checkRecording() {
        lifecycleScope.launch {
            val rec = withContext(Dispatchers.IO) {
                runCatching { CameraConfig.open(cam.ip, cam.user, cam.pass).recordingState() }.getOrNull()
            }
            b.recBadge.visibility = if (rec?.enabled == true && !inPip) View.VISIBLE else View.GONE
        }
    }

    // ---------------- controls ----------------

    private fun showOverlay() {
        if (inPip) return
        b.overlay.visibility = View.VISIBLE
        b.overlay.animate().alpha(1f).setDuration(150).start()
        hideJob?.cancel()
        hideJob = lifecycleScope.launch {
            delay(5000)
            hideOverlay()
        }
    }

    private fun hideOverlay() {
        hideJob?.cancel()
        b.overlay.animate().alpha(0f).setDuration(250).withEndAction {
            if (b.overlay.alpha == 0f) b.overlay.visibility = View.GONE
        }.start()
    }

    private fun toggleOverlay() {
        if (b.overlay.visibility == View.VISIBLE && b.overlay.alpha > 0.5f) hideOverlay() else showOverlay()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun bindPtz(v: View, d: Dir) {
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    showOverlay()
                    val c = ptz
                    if (c == null) {
                        Toast.makeText(this, "Still connecting…", Toast.LENGTH_SHORT).show()
                    } else {
                        lifecycleScope.launch(ptzDispatcher) {
                            if (!c.start(d)) withContext(Dispatchers.Main) {
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
        val prefs = getSharedPreferences("cam", MODE_PRIVATE)
        bindPtz(b.btnUp, Dir.UP)
        bindPtz(b.btnDown, Dir.DOWN)
        bindPtz(b.btnLeft, Dir.LEFT)
        bindPtz(b.btnRight, Dir.RIGHT)

        b.btnBack.setOnClickListener { openCameraList() }
        b.btnSettings.setOnClickListener {
            startActivity(Intent(this, SetupActivity::class.java).putExtra("camId", cam.id))
        }
        b.btnRecordings.setOnClickListener {
            startActivity(Intent(this, RecordingsActivity::class.java).putExtra("camId", cam.id))
        }
        b.btnReconnect.setOnClickListener { relocated = false; cam.streamUrl = null; connect() }
        b.btnMute.setOnClickListener {
            muted = !muted
            player?.volume = if (muted) 0f else 1f
            b.btnMute.text = if (muted) "🔇" else "🔊"
            showOverlay()
        }
        b.btnPtz.setOnClickListener {
            val show = b.ptzPad.visibility != View.VISIBLE
            b.ptzPad.visibility = if (show) View.VISIBLE else View.GONE
            prefs.edit().putBoolean("showPtz", show).apply()
            showOverlay()
        }
        b.btnRotate.setOnClickListener {
            val land = !prefs.getBoolean("landscape", true)
            prefs.edit().putBoolean("landscape", land).apply()
            requestedOrientation = if (land) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
        b.btnPip.setOnClickListener { enterPip() }
        if (Build.VERSION.SDK_INT < 26) b.btnPip.visibility = View.GONE
        b.btnQuality.setOnClickListener { switchQuality() }
        b.btnSnap.setOnClickListener { takePhoto() }
    }

    private fun openCameraList() {
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("noAutoStart", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                zoom = (zoom * d.scaleFactor).coerceIn(1f, 5f)
                applyZoom()
                return true
            }
        })
        val gest = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { toggleOverlay(); return true }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                zoom = if (zoom > 1.01f) 1f else 2.5f
                if (zoom == 1f) { panX = 0f; panY = 0f }
                applyZoom()
                return true
            }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (zoom <= 1.01f) return false
                panX -= dx; panY -= dy
                applyZoom()
                return true
            }
        })
        b.root.setOnTouchListener { _, ev ->
            scale.onTouchEvent(ev)
            if (!scale.isInProgress) gest.onTouchEvent(ev)
            true
        }
    }

    private fun applyZoom() {
        val l = b.zoomLayer
        val maxX = l.width * (zoom - 1) / 2
        val maxY = l.height * (zoom - 1) / 2
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
        l.scaleX = zoom; l.scaleY = zoom
        l.translationX = panX; l.translationY = panY
    }

    private fun isSubStream(u: String) =
        u.endsWith("/12") || u.endsWith("onvif2") || u.endsWith("/live/ch1") || u.endsWith("av0_1") || u.endsWith("stream2")

    private fun alternateStream(u: String): String? {
        val i = onvifUris.indexOf(u)
        if (i >= 0 && onvifUris.size > 1) return onvifUris[(i + 1) % onvifUris.size]
        val pairs = listOf(
            "/11" to "/12", "/onvif1" to "/onvif2", "/live/ch0" to "/live/ch1",
            "av0_0" to "av0_1", "/stream1" to "/stream2", "/ch0_0.h264" to "/ch0_1.h264"
        )
        for ((a, c) in pairs) {
            if (u.endsWith(a)) return u.removeSuffix(a) + c
            if (u.endsWith(c)) return u.removeSuffix(c) + a
        }
        return null
    }

    private fun switchQuality() {
        showOverlay()
        val cur = currentUrl ?: return Toast.makeText(this, "Only works with live video", Toast.LENGTH_SHORT).show()
        val alt = alternateStream(cur) ?: return Toast.makeText(this, "No second stream known for this camera", Toast.LENGTH_SHORT).show()
        connectJob?.cancel()
        connectJob = lifecycleScope.launch {
            status("Switching to ${if (isSubStream(alt)) "SD" else "HD"}…", true)
            if (tryPlay(alt)) onLive(alt)
            else {
                status("That stream isn't available.", true)
                if (tryPlay(cur)) onLive(cur) else connect()
            }
        }
    }

    private fun takePhoto() {
        showOverlay()
        val bmp: Bitmap? = if (player != null) (b.playerView.videoSurfaceView as? TextureView)?.bitmap else lastFrame
        if (bmp == null) {
            Toast.makeText(this, "No picture yet", Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            val where = withContext(Dispatchers.IO) { runCatching { saveBitmap(bmp) }.getOrNull() }
            Toast.makeText(this@ViewerActivity, if (where != null) "Saved to $where" else "Couldn't save photo", Toast.LENGTH_SHORT).show()
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
            val f = File(getExternalFilesDir(Environment.DIRECTORY_PICTURES), name)
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
            f.absolutePath
        }
    }

    // ---------------- picture-in-picture ----------------

    private fun enterPip() {
        if (Build.VERSION.SDK_INT < 26) return
        try {
            enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build())
        } catch (_: Exception) {
            Toast.makeText(this, "Picture-in-picture isn't available on this phone", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (store.autoPip && live && Build.VERSION.SDK_INT >= 26) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPip = isInPictureInPictureMode
        if (inPip) {
            b.overlay.visibility = View.GONE
            b.statusText.visibility = View.GONE
            b.recBadge.visibility = View.GONE
            zoom = 1f; panX = 0f; panY = 0f; applyZoom()
        } else {
            showOverlay()
            checkRecording()
        }
    }

    // ---------------- lifecycle ----------------

    override fun onStart() {
        super.onStart()
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    if (!live && connectJob?.isActive != true && !isFinishing) {
                        relocated = false
                        connect()
                    }
                }
            }
        }
        netCallback = cb
        try { cm.registerDefaultNetworkCallback(cb) } catch (_: Exception) {}
    }

    override fun onStop() {
        super.onStop()
        netCallback?.let { cb ->
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        netCallback = null
        if (!inPip) {
            connectJob?.cancel()
            stopVideo()
            live = false
        }
    }

    override fun onRestart() {
        super.onRestart()
        store.get(cam.id)?.let { cam = it; b.camTitle.text = it.name }
        goImmersive()
        connect()
        checkRecording()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) goImmersive()
    }

    override fun onDestroy() {
        super.onDestroy()
        ptzDispatcher.close()
    }
}
