package com.gandharv.d3dcam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts a camera on Wi‑Fi.
 *  - New / reset camera: Smart Link (phone broadcasts the Wi‑Fi details, no hotspot needed),
 *    LAN cable, or the camera's own hotspot.
 *  - Camera already reachable (ip extra given): send the new Wi‑Fi details directly.
 */
class WifiSetupActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cam", MODE_PRIVATE) }
    private var ip: String? = null
    private var cfg: CameraConfig? = null
    private var bound = false
    private var smartJob: Job? = null

    private lateinit var status: TextView
    private lateinit var userEdit: TextInputEditText
    private lateinit var passEdit: TextInputEditText
    private lateinit var ssidEdit: TextInputEditText
    private lateinit var keyEdit: TextInputEditText
    private var sendBtn: MaterialButton? = null
    private var findNetBtn: MaterialButton? = null
    private var smartBtn: MaterialButton? = null
    private var smartStatus: TextView? = null
    private var smartProgress: ProgressBar? = null
    private lateinit var doneBox: LinearLayout
    private lateinit var doneText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ip = intent.getStringExtra("ip")?.takeIf { it.isNotBlank() }
        buildUi()
        if (ip != null) connectTo(ip!!)
    }

    private fun buildUi() {
        val c = this
        val root = Ui.column(c, 20)
        root.fitsSystemWindows = true
        val existing = ip != null

        root.addView(TextView(c).apply {
            text = if (existing) "Change camera Wi‑Fi" else "Set up a new camera"
            textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        // Wi‑Fi details (shared by every method)
        root.addView(Ui.header(c, "Your home Wi‑Fi"))
        root.addView(Ui.note(c, "These cameras only work on 2.4 GHz Wi‑Fi, not 5 GHz."))
        val (ls, s) = Ui.field(c, "Wi‑Fi name")
        val (lk, k) = Ui.field(c, "Wi‑Fi password", password = true)
        ssidEdit = s; keyEdit = k
        s.setText(prefs.getString("home_ssid", ""))
        root.addView(Ui.full(ls)); root.addView(Ui.full(lk))

        val (lu, u) = Ui.field(c, "Camera username", value = prefs.getString("user", "admin") ?: "admin")
        val (lp, p) = Ui.field(c, "Camera password", password = true, value = prefs.getString("pass", "admin") ?: "admin")
        userEdit = u; passEdit = p

        if (!existing) {
            // ---- Option 1: Smart Link ----
            root.addView(Ui.header(c, "Option 1 · Smart Link (recommended)"))
            root.addView(Ui.note(c,
                "1. Connect this phone to your home Wi‑Fi (the one entered above).\n" +
                "2. Reset the camera: hold RESET for 30 seconds, then wait about 1 minute until it has restarted.\n" +
                "3. Keep the phone near the camera and tap Start. Leave this screen open — it can take up to 2 minutes."))
            smartBtn = Ui.button(c, "Start Smart Link") { toggleSmartLink() }
            root.addView(Ui.full(smartBtn!!))
            smartProgress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 120; visibility = View.GONE
            }
            root.addView(Ui.full(smartProgress!!))
            smartStatus = Ui.note(c, "")
            root.addView(smartStatus)

            // ---- Option 2: LAN cable ----
            root.addView(Ui.header(c, "Option 2 · LAN cable"))
            root.addView(Ui.note(c,
                "Plug the camera into your router with a network cable. Go back, tap Scan Wi‑Fi for cameras, " +
                "open the camera, then Camera settings → Change camera Wi‑Fi. After that you can unplug the cable."))

            // ---- Option 3: hotspot ----
            root.addView(Ui.header(c, "Option 3 · Camera hotspot"))
            root.addView(Ui.note(c,
                "Only if your camera creates its own Wi‑Fi hotspot after a reset. Join that hotspot, then tap the button below."))
            root.addView(Ui.full(Ui.button(c, "Open Wi‑Fi settings", outlined = true) {
                val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                startActivity(i)
            }))
            root.addView(Ui.full(Ui.button(c, "I'm on the camera's hotspot — find it", outlined = true) { findOnHotspot() }))
        } else {
            root.addView(Ui.note(c, "The camera will restart and join the new network. Your phone should be on that network afterwards to view it."))
        }

        status = Ui.note(c, if (existing) "Connecting to $ip…" else "")
        root.addView(status)

        // Direct send (existing camera, hotspot or cable)
        val direct = Ui.column(c)
        direct.addView(Ui.full(lu)); direct.addView(Ui.full(lp))
        findNetBtn = Ui.button(c, "Pick from networks the camera can see", outlined = true) { pickNetwork() }
        sendBtn = Ui.button(c, "Send Wi‑Fi details to camera") { send() }
        direct.addView(Ui.full(findNetBtn!!)); direct.addView(Ui.full(sendBtn!!))
        findNetBtn!!.isEnabled = false
        sendBtn!!.isEnabled = false
        root.addView(direct)

        doneBox = Ui.column(c).apply { visibility = View.GONE }
        doneText = Ui.note(c, "")
        doneBox.addView(Ui.header(c, "Almost done"))
        doneBox.addView(doneText)
        doneBox.addView(Ui.full(Ui.button(c, "Find my camera on home Wi‑Fi") { goFind() }))
        root.addView(doneBox)

        setContentView(ScrollView(c).apply { addView(root) })
    }

    private fun user() = userEdit.text.toString().trim().ifEmpty { "admin" }
    private fun pass() = passEdit.text.toString()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    private fun goFind() {
        stopSmartLink()
        unbindNet()
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("autoscan", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    // ---------------- Smart Link ----------------

    private fun toggleSmartLink() {
        if (smartJob?.isActive == true) {
            stopSmartLink()
            smartStatus?.text = "Stopped."
            return
        }
        val ssid = ssidEdit.text.toString().trim()
        val key = keyEdit.text.toString()
        HiSmartLink.validate(ssid, key)?.let { return toast(it) }
        val net = Scanner.wifiNetwork(this)
        if (net == null) {
            toast("Connect this phone to your home Wi‑Fi first")
            return
        }
        prefs.edit().putString("home_ssid", ssid).apply()

        smartBtn?.text = "Stop"
        smartProgress?.visibility = View.VISIBLE
        smartProgress?.progress = 0
        smartStatus?.text = "Sending Wi‑Fi details to the camera…"
        val started = System.currentTimeMillis()
        val totalMs = 120_000L

        smartJob = lifecycleScope.launch {
            // Watch the network for the camera while sending.
            val watcher = launch {
                val before = runCatching { Scanner.scan(this@WifiSetupActivity, user(), pass()) { _, _ -> } }
                    .getOrDefault(emptyList()).filter { it.likelyCamera }.map { it.ip }.toSet()
                while (true) {
                    delay(12_000)
                    val now = runCatching { Scanner.scan(this@WifiSetupActivity, user(), pass()) { _, _ -> } }
                        .getOrDefault(emptyList())
                    val cam = now.firstOrNull { it.likelyCamera && it.ip !in before }
                    if (cam != null) {
                        onCameraJoined(cam)
                        return@launch
                    }
                }
            }
            try {
                HiSmartLink.send(this@WifiSetupActivity, net, ssid, key, totalMs) { _ ->
                    val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
                    smartProgress?.progress = secs
                    smartStatus?.text = "Sending Wi‑Fi details… ${secs}s\nThe camera usually beeps or speaks when it receives them."
                }
            } finally {
                if (watcher.isActive) {
                    // Keep watching a little longer — the camera needs time to join after receiving.
                    smartStatus?.text = "Finished sending. Waiting for the camera to join your Wi‑Fi…"
                    kotlinx.coroutines.withTimeoutOrNull(45_000) { watcher.join() }
                    if (watcher.isActive) {
                        watcher.cancel()
                        smartStatus?.text = "The camera hasn't appeared yet. Check the Wi‑Fi password and that it's 2.4 GHz, " +
                                "reset the camera and try again — or tap Find my camera in case it joined late."
                        doneText.text = "If the camera beeped or spoke, it may have joined. Tap below to look for it."
                        doneBox.visibility = View.VISIBLE
                    }
                }
                smartBtn?.text = "Start Smart Link"
                smartProgress?.visibility = View.GONE
            }
        }
    }

    private fun onCameraJoined(cam: Found) {
        prefs.edit().putString("ip", cam.ip).apply()
        stopSmartLink()
        smartStatus?.text = "✓ Camera joined your Wi‑Fi at ${cam.ip}" + (cam.model?.let { " ($it)" } ?: "") + "."
        MaterialAlertDialogBuilder(this)
            .setTitle("Camera connected")
            .setMessage("Your camera is now on \"${ssidEdit.text}\" at ${cam.ip}.")
            .setPositiveButton("View camera") { _, _ ->
                startActivity(Intent(this, ViewerActivity::class.java)
                    .putExtra("ip", cam.ip).putExtra("user", user()).putExtra("pass", pass()))
                finish()
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun stopSmartLink() {
        smartJob?.cancel()
        smartJob = null
        smartBtn?.text = "Start Smart Link"
        smartProgress?.visibility = View.GONE
    }

    // ---------------- Hotspot / direct ----------------

    private fun findOnHotspot() {
        if (!Scanner.onWifi(this)) {
            status.text = "This phone isn't on Wi‑Fi yet. Join the camera's hotspot first."
            return
        }
        bound = Scanner.bindToWifi(this)
        status.text = "Looking for the camera on this hotspot…"
        lifecycleScope.launch {
            val gw = Scanner.gateway(this@WifiSetupActivity)
            val atGateway = gw != null && withContext(Dispatchers.IO) {
                Net.ALL_PORTS.any { Net.portOpen(gw, it, 1000) }
            }
            val camIp = if (atGateway) gw else try {
                Scanner.findBestCamera(this@WifiSetupActivity, user(), pass())?.ip
            } catch (e: Exception) { null }
            if (camIp == null) {
                status.text = "No camera found on this Wi‑Fi. Make sure you joined the camera's own hotspot."
                return@launch
            }
            connectTo(camIp)
        }
    }

    private fun connectTo(camIp: String) {
        status.text = "Connecting to camera at $camIp…"
        lifecycleScope.launch {
            val cam = withContext(Dispatchers.IO) { CameraConfig.open(camIp, user(), pass()) }
            cfg = cam
            ip = camIp
            status.text = if (cam.protocols.isEmpty())
                "Found a device at $camIp, but it didn't accept settings. Check the camera username/password and try again."
            else "Camera ready at $camIp (${cam.protocols.joinToString()})."
            findNetBtn?.isEnabled = true
            sendBtn?.isEnabled = true
        }
    }

    private fun pickNetwork() {
        val cam = cfg ?: return
        status.text = "Asking the camera which Wi‑Fi networks it can see…"
        findNetBtn?.isEnabled = false
        lifecycleScope.launch {
            val nets = withContext(Dispatchers.IO) { runCatching { cam.scanWifi() }.getOrDefault(emptyList()) }
            findNetBtn?.isEnabled = true
            if (nets.isEmpty()) {
                status.text = "The camera didn't return a network list. Type your Wi‑Fi name instead."
                return@launch
            }
            status.text = "Camera can see ${nets.size} networks."
            MaterialAlertDialogBuilder(this@WifiSetupActivity)
                .setTitle("Choose your Wi‑Fi")
                .setItems(nets.toTypedArray()) { _, i -> ssidEdit.setText(nets[i]) }
                .show()
        }
    }

    private fun send() {
        val ssid = ssidEdit.text.toString().trim()
        val key = keyEdit.text.toString()
        if (ssid.isEmpty()) return toast("Enter your Wi‑Fi name")
        val camIp = ip ?: return toast("Find the camera first")
        sendBtn?.isEnabled = false
        status.text = "Sending Wi‑Fi details…"
        lifecycleScope.launch {
            val cam = withContext(Dispatchers.IO) {
                cfg?.takeIf { it.user == user() && it.pass == pass() } ?: CameraConfig.open(camIp, user(), pass())
            }
            cfg = cam
            val o = withContext(Dispatchers.IO) { cam.setWifi(ssid, key) }
            if (!o.ok) {
                status.text = o.message
                sendBtn?.isEnabled = true
                return@launch
            }
            prefs.edit().putString("user", user()).putString("pass", pass()).putString("home_ssid", ssid).apply()
            status.text = "Saved. Restarting the camera…"
            withContext(Dispatchers.IO) { cam.rebootQuietly() }
            delay(500)
            unbindNet()
            doneText.text = "The camera is restarting and will join \"$ssid\" in about a minute. " +
                    "If it's plugged in with a cable, you can unplug it now.\n\n" +
                    "Make sure this phone is on \"$ssid\", then tap Find my camera."
            doneBox.visibility = View.VISIBLE
        }
    }

    private fun unbindNet() {
        if (bound) {
            Scanner.unbind(this)
            bound = false
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        smartJob?.cancel()
        unbindNet()
    }
}
