package com.gandharv.d3dcam

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gandharv.d3dcam.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private val prefs by lazy { getSharedPreferences("cam", MODE_PRIVATE) }
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.ipInput.setText(prefs.getString("ip", ""))
        b.userInput.setText(prefs.getString("user", "admin"))
        b.passInput.setText(prefs.getString("pass", "admin"))
        b.urlInput.setText(prefs.getString("customUrl", ""))

        b.scanBtn.setOnClickListener { scan() }
        b.connectBtn.setOnClickListener { openViewer(b.ipInput.text.toString().trim()) }

        if (savedInstanceState == null && prefs.getString("ip", "").isNullOrEmpty()) scan()
    }

    private fun user() = b.userInput.text.toString().trim().ifEmpty { "admin" }
    private fun pass() = b.passInput.text.toString()

    private fun scan() {
        if (scanJob?.isActive == true) return
        if (!Scanner.onWifi(this)) {
            b.scanStatus.text = "Connect this phone to the same Wi‑Fi as the camera, then scan again."
            return
        }
        b.results.removeAllViews()
        b.scanProgress.visibility = View.VISIBLE
        b.scanProgress.progress = 0
        b.scanBtn.isEnabled = false
        b.scanStatus.text = "Scanning your Wi‑Fi…"

        scanJob = lifecycleScope.launch {
            try {
                val found = Scanner.scan(this@MainActivity, user(), pass()) { done, total ->
                    b.scanProgress.max = total
                    b.scanProgress.progress = done
                    b.scanStatus.text = "Scanning your Wi‑Fi… $done / $total"
                }
                showResults(found)
            } catch (e: Exception) {
                b.scanStatus.text = "Scan failed: ${e.message}"
            } finally {
                b.scanProgress.visibility = View.GONE
                b.scanBtn.isEnabled = true
            }
        }
    }

    private fun showResults(found: List<Found>) {
        b.results.removeAllViews()
        val cams = found.count { it.likelyCamera }
        b.scanStatus.text = when {
            found.isEmpty() -> "No devices answered. Check the camera is powered and on this Wi‑Fi."
            cams == 0 -> "Found ${found.size} devices, none look like a camera. Tap one to try it anyway."
            cams == 1 -> "Found your camera. Tap it to view."
            else -> "Found $cams possible cameras. Tap one to view."
        }
        for (f in found) {
            val tags = buildList {
                if (f.snapshotUrl != null) add("picture ✓")
                if (554 in f.ports || 10554 in f.ports) add("RTSP video")
                if (f.onvif) add("ONVIF")
            }
            val title = if (f.likelyCamera) "📷  ${f.ip}  — likely camera" else f.ip
            val sub = if (tags.isNotEmpty()) tags.joinToString(" · ")
            else "Open ports: ${f.ports.joinToString()}"
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "$title\n$sub"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = 8 }
                setOnClickListener {
                    b.ipInput.setText(f.ip)
                    openViewer(f.ip)
                }
            }
            b.results.addView(btn)
        }
    }

    private fun openViewer(ip: String) {
        val customUrl = b.urlInput.text.toString().trim()
        if (ip.isEmpty() && customUrl.isEmpty()) {
            Toast.makeText(this, "Scan first, or type the camera's IP address", Toast.LENGTH_SHORT).show()
            return
        }
        val ipFromUrl = if (ip.isEmpty()) android.net.Uri.parse(customUrl).host ?: "" else ip
        prefs.edit()
            .putString("ip", ipFromUrl)
            .putString("user", user())
            .putString("pass", pass())
            .putString("customUrl", customUrl)
            .apply()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .putExtra("ip", ipFromUrl)
                .putExtra("user", user())
                .putExtra("pass", pass())
                .putExtra("url", customUrl)
        )
    }
}
