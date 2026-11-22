package com.gandharv.d3dcam

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gandharv.d3dcam.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var b: ActivityMainBinding
    private lateinit var store: CameraStore
    private var scanJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CameraStore(this)

        // Jump straight into the last camera, full screen.
        if (savedInstanceState == null && !intent.getBooleanExtra("noAutoStart", false) &&
            !intent.getBooleanExtra("autoscan", false) && store.autoStart
        ) {
            store.get(store.lastId)?.let { openViewer(it) }
        }

        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Ui.applySystemBarPadding(b.root)

        b.scanBtn.setOnClickListener { scan() }
        b.setupBtn.setOnClickListener { startActivity(Intent(this, WifiSetupActivity::class.java)) }
        b.manualToggle.setOnClickListener {
            val show = b.manualBox.visibility != View.VISIBLE
            b.manualBox.visibility = if (show) View.VISIBLE else View.GONE
            b.manualToggle.text = if (show) "Add by IP address ▴" else "Add by IP address ▾"
        }
        b.addBtn.setOnClickListener { addManual() }

        b.autoStartSw.isChecked = store.autoStart
        b.autoStartSw.setOnCheckedChangeListener { _, v -> store.autoStart = v }
        b.syncTimeSw.isChecked = store.syncTimeOnStart
        b.syncTimeSw.setOnCheckedChangeListener { _, v -> store.syncTimeOnStart = v }
        b.autoPipSw.isChecked = store.autoPip
        b.autoPipSw.setOnCheckedChangeListener { _, v -> store.autoPip = v }
        b.versionText.text = "Version ${BuildConfig.VERSION_NAME}"

        if (intent.getBooleanExtra("autoscan", false) || (savedInstanceState == null && store.all().isEmpty())) scan()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("autoscan", false)) scan()
    }

    override fun onResume() {
        super.onResume()
        showCameras()
    }

    // ---------------- saved cameras ----------------

    private fun showCameras() {
        val list = store.all()
        b.cameras.removeAllViews()
        b.emptyText.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
        for (cam in list) b.cameras.addView(cameraCard(cam))
    }

    private fun cameraCard(cam: Camera): View {
        val c = this
        val card = MaterialCardView(c).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = Ui.dp(c, 10) }
            radius = Ui.dp(c, 14).toFloat()
            isClickable = true
            setOnClickListener { openViewer(cam) }
            setOnLongClickListener { cameraMenu(cam); true }
        }
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF202020.toInt())
        }
        row.addView(thumb, LinearLayout.LayoutParams(Ui.dp(c, 128), Ui.dp(c, 72)))
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(c, 12), 0, Ui.dp(c, 4), 0)
        }
        texts.addView(TextView(c).apply { text = cam.name; textSize = 17f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        val sub = TextView(c).apply { text = cam.ip; alpha = 0.7f }
        texts.addView(sub)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(MaterialButton(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "⋮"; minWidth = 0; setPadding(0, 0, 0, 0)
            setOnClickListener { cameraMenu(cam) }
        }, LinearLayout.LayoutParams(Ui.dp(c, 48), Ui.dp(c, 48)).apply { marginEnd = Ui.dp(c, 8) })
        card.addView(row)

        // Load a live thumbnail in the background.
        lifecycleScope.launch {
            val bmp: Bitmap? = withContext(Dispatchers.IO) {
                runCatching {
                    val open = Net.HTTP_PORTS.filter { Net.portOpen(cam.ip, it, 800) }
                    val url = Net.findSnapshot(cam.ip, open, cam.user, cam.pass) ?: return@runCatching null
                    Net.get(url, cam.user, cam.pass, 3000)?.body?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                }.getOrNull()
            }
            if (bmp != null) thumb.setImageBitmap(bmp)
            sub.text = cam.ip + if (bmp == null) " · offline?" else " · online"
        }
        return card
    }

    private fun cameraMenu(cam: Camera) {
        MaterialAlertDialogBuilder(this)
            .setTitle(cam.name)
            .setItems(arrayOf("View", "Recordings", "Camera settings", "Rename", "Remove from app")) { _, i ->
                when (i) {
                    0 -> openViewer(cam)
                    1 -> startActivity(Intent(this, RecordingsActivity::class.java).putExtra("camId", cam.id))
                    2 -> startActivity(Intent(this, SetupActivity::class.java).putExtra("camId", cam.id))
                    3 -> rename(cam)
                    4 -> MaterialAlertDialogBuilder(this)
                        .setMessage("Remove ${cam.name} from this app? The camera itself isn't changed.")
                        .setPositiveButton("Remove") { _, _ -> store.remove(cam.id); showCameras() }
                        .setNegativeButton("Cancel", null).show()
                }
            }.show()
    }

    private fun rename(cam: Camera) {
        val (layout, edit) = Ui.field(this, "Name", value = cam.name)
        val box = Ui.column(this, 20).apply { addView(layout) }
        MaterialAlertDialogBuilder(this)
            .setTitle("Rename camera")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val n = edit.text.toString().trim()
                if (n.isNotEmpty()) { store.update(cam.id) { it.name = n }; showCameras() }
            }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openViewer(cam: Camera) {
        store.lastId = cam.id
        startActivity(Intent(this, ViewerActivity::class.java).putExtra("camId", cam.id))
    }

    // ---------------- adding cameras ----------------

    private fun addCamera(ip: String, user: String, pass: String, name: String?, customUrl: String = ""): Camera {
        val existing = store.findByIp(ip)
        val cam = existing?.apply {
            this.user = user; this.pass = pass
            if (customUrl.isNotEmpty()) this.customUrl = customUrl
        } ?: Camera(name = name ?: store.nextName(), ip = ip, user = user, pass = pass, customUrl = customUrl)
        store.upsert(cam)
        return cam
    }

    private fun addManual() {
        val ip = b.ipInput.text.toString().trim()
        val url = b.urlInput.text.toString().trim()
        val host = if (ip.isEmpty() && url.isNotEmpty()) android.net.Uri.parse(url).host ?: "" else ip
        if (host.isEmpty()) {
            Toast.makeText(this, "Type the camera's IP address", Toast.LENGTH_SHORT).show()
            return
        }
        val cam = addCamera(host, user(b.userInput), b.passInput.text.toString(), null, url)
        openViewer(cam)
    }

    private fun user(e: TextInputEditText) = e.text.toString().trim().ifEmpty { "admin" }

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
        val user = user(b.userInput)
        val pass = b.passInput.text.toString()
        scanJob = lifecycleScope.launch {
            try {
                val found = Scanner.scan(this@MainActivity, user, pass) { done, total ->
                    b.scanProgress.max = total
                    b.scanProgress.progress = done
                    b.scanStatus.text = "Scanning your Wi‑Fi… $done / $total"
                }
                showResults(found, user, pass)
            } catch (e: Exception) {
                b.scanStatus.text = "Scan failed: ${e.message}"
            } finally {
                b.scanProgress.visibility = View.GONE
                b.scanBtn.isEnabled = true
            }
        }
    }

    private fun showResults(found: List<Found>, user: String, pass: String) {
        b.results.removeAllViews()
        val saved = store.all().map { it.ip }.toSet()
        val cams = found.count { it.likelyCamera }
        b.scanStatus.text = when {
            found.isEmpty() -> "No devices answered. Check the camera is powered and on this Wi‑Fi."
            cams == 0 -> "Found ${found.size} devices, none look like a camera. Tap one to try it anyway."
            cams == 1 -> "Found a camera. Tap it to add it."
            else -> "Found $cams possible cameras. Tap one to add it."
        }
        for (f in found) {
            val tags = buildList {
                if (f.ip in saved) add("already added")
                if (f.snapshotUrl != null) add("picture ✓")
                if (554 in f.ports || 10554 in f.ports) add("RTSP video")
                if (f.onvif) add("ONVIF")
            }
            val title = when {
                f.model != null -> "📷  ${f.ip}  — ${f.model}"
                f.likelyCamera -> "📷  ${f.ip}  — likely camera"
                else -> f.ip
            }
            val sub = if (tags.isNotEmpty()) tags.joinToString(" · ") else "Open ports: ${f.ports.joinToString()}"
            b.results.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = "$title\n$sub"
                isAllCaps = false
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .apply { topMargin = 8 }
                setOnClickListener {
                    val cam = addCamera(f.ip, user, pass, f.model?.takeIf { it != "IP camera" })
                    showCameras()
                    openViewer(cam)
                }
            })
        }
    }
}
