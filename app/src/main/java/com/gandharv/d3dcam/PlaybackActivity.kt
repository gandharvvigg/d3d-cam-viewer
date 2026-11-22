package com.gandharv.d3dcam

import android.os.Bundle
import android.util.Base64
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView

/** Full-screen playback of SD card recordings, with seek bar, speed and next/previous clip. */
@OptIn(UnstableApi::class)
class PlaybackActivity : AppCompatActivity() {

    private var player: ExoPlayer? = null
    private lateinit var view: PlayerView
    private lateinit var title: TextView
    private var resumeIndex = 0
    private var resumePos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cam = CameraStore(this).get(intent.getStringExtra("camId")) ?: run { finish(); return }
        val urls = intent.getStringArrayListExtra("urls") ?: arrayListOf()
        val titles = intent.getStringArrayListExtra("titles") ?: arrayListOf()
        resumeIndex = savedInstanceState?.getInt("idx") ?: intent.getIntExtra("index", 0)
        resumePos = savedInstanceState?.getLong("pos") ?: 0L

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = FrameLayout(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        view = PlayerView(this).apply {
            setShowNextButton(true)
            setShowPreviousButton(true)
            setShowFastForwardButton(true)
            setShowRewindButton(true)
            controllerShowTimeoutMs = 3500
        }
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        title = TextView(this).apply {
            setTextColor(0xFFFFFFFF.toInt()); textSize = 14f
            setBackgroundColor(0x88000000.toInt())
            val p = Ui.dp(this@PlaybackActivity, 8); setPadding(p, p / 2, p, p / 2)
        }
        root.addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.START).apply { setMargins(Ui.dp(this@PlaybackActivity, 16), Ui.dp(this@PlaybackActivity, 16), 0, 0) })
        setContentView(root)
        view.setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { v -> title.visibility = v })

        val auth = "Basic " + Base64.encodeToString("${cam.user}:${cam.pass}".toByteArray(), Base64.NO_WRAP)
        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(15000)
            .setDefaultRequestProperties(mapOf("Authorization" to auth))
        val sourceFactory = ProgressiveMediaSource.Factory(http, HxvsExtractor.factory)

        val p = ExoPlayer.Builder(this)
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(30_000)
            .build()
        player = p
        view.player = p
        p.setMediaSources(urls.mapIndexed { i, u ->
            sourceFactory.createMediaSource(
                MediaItem.Builder().setUri(u)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.getOrNull(i) ?: u.substringAfterLast('/')).build())
                    .build()
            )
        }, resumeIndex.coerceIn(0, (urls.size - 1).coerceAtLeast(0)), resumePos)
        p.addListener(object : Player.Listener {
            override fun onMediaMetadataChanged(m: MediaMetadata) {
                title.text = "${cam.name} · ${m.title ?: ""}"
            }
            override fun onPlayerError(error: PlaybackException) {
                title.text = "Couldn't play this recording (${error.errorCodeName}). Skipping…"
                title.visibility = android.view.View.VISIBLE
                if (p.hasNextMediaItem()) { p.seekToNextMediaItem(); p.prepare() }
            }
        })
        p.playWhenReady = true
        p.prepare()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        player?.let { outState.putInt("idx", it.currentMediaItemIndex); outState.putLong("pos", it.currentPosition) }
    }

    override fun onStop() {
        super.onStop()
        player?.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release()
        player = null
    }
}
