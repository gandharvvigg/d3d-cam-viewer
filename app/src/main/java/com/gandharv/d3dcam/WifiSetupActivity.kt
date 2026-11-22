package com.gandharv.d3dcam

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Puts a camera on a Wi‑Fi network.
 *  - New / reset camera: the phone joins the camera's own hotspot, we find the camera there
 *    and send it the home Wi‑Fi name and password.
 *  - Camera already on the network (ip extra given): send the new Wi‑Fi details directly.
 */
class WifiSetupActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cam", MODE_PRIVATE) }
    private var ip: String? = null
    private var cfg: CameraConfig? = null
    private var bound = false

    private lateinit var status: TextView
    private lateinit var userEdit: TextInputEditText
    private lateinit var passEdit: TextInputEditText
    private lateinit var ssidEdit: TextInputEditText
    private lateinit var keyEdit: TextInputEditText
    private lateinit var sendBtn: MaterialButton
    private lateinit var findNetBtn: MaterialButton
    private lateinit var doneBox: View
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

        if (!existing) {
            root.addView(Ui.header(c, "1. Put the camera in setup mode"))
            root.addView(Ui.note(c, "Plug the camera in. If it was used before, hold the RESET button underneath for about 10 seconds " +
                    "until it restarts. After 1–2 minutes it creates its own Wi‑Fi hotspot (often named after the camera ID, e.g. starting with IPC, D3D or FFFF)."))
            root.addView(Ui.header(c, "2. Join the camera's hotspot"))
            root.addView(Ui.note(c, "Connect this phone to that hotspot. If Android says \"No internet\", choose to stay connected."))
            root.addView(Ui.full(Ui.button(c, "Open Wi‑Fi settings", outlined = true) {
                val i = if (Build.VERSION.SDK_INT >= 29) Intent(Settings.Panel.ACTION_WIFI) else Intent(Settings.ACTION_WIFI_SETTINGS)
                startActivity(i)
            }))
            root.addView(Ui.full(Ui.button(c, "I'm connected — find the camera") { findOnHotspot() }))
            root.addView(Ui.header(c, "3. Send your home Wi‑Fi"))
        } else {
            root.addView(Ui.note(c, "The camera will restart and join the new network. Your phone should be on that network afterwards to view it."))
        }

        status = Ui.note(c, if (existing) "Connecting to $ip…" else "Waiting for step 2.")
        root.addView(status)

        val (lu, u) = Ui.field(c, "Camera username", value = prefs.getString("user", "admin") ?: "admin")
        val (lp, p) = Ui.field(c, "Camera password", password = true, value = prefs.getString("pass", "admin") ?: "admin")
        userEdit = u; passEdit = p
        root.addView(Ui.full(lu)); root.addView(Ui.full(lp))

        val (ls, s) = Ui.field(c, "Home Wi‑Fi name (2.4 GHz)")
        val (lk, k) = Ui.field(c, "Home Wi‑Fi password", password = true)
        ssidEdit = s; keyEdit = k
        root.addView(Ui.full(ls, 14)); root.addView(Ui.full(lk))
        root.addView(Ui.note(c, "Most cameras like this only work on 2.4 GHz Wi‑Fi, not 5 GHz."))

        findNetBtn = Ui.button(c, "Pick from networks the camera can see", outlined = true) { pickNetwork() }
        sendBtn = Ui.button(c, "Send Wi‑Fi details to camera") { send() }
        root.addView(Ui.full(findNetBtn)); root.addView(Ui.full(sendBtn))
        findNetBtn.isEnabled = false
        sendBtn.isEnabled = false

        doneBox = Ui.column(c).apply { visibility = View.GONE }
        doneText = Ui.note(c, "")
        (doneBox as android.widget.LinearLayout).apply {
            addView(Ui.header(c, "Almost done"))
            addView(doneText)
            addView(Ui.full(Ui.button(c, "Open Wi‑Fi settings", outlined = true) {
                startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
            }))
            addView(Ui.full(Ui.button(c, "Find my camera on home Wi‑Fi") {
                unbindNet()
                startActivity(Intent(c, MainActivity::class.java)
                    .putExtra("autoscan", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            }))
        }
        root.addView(doneBox)

        setContentView(ScrollView(c).apply { addView(root) })
    }

    private fun user() = userEdit.text.toString().trim().ifEmpty { "admin" }
    private fun pass() = passEdit.text.toString()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

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
                status.text = "No camera found on this Wi‑Fi. Make sure you joined the camera's own hotspot " +
                        "(not your home Wi‑Fi), wait a minute after reset, and try again."
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
            if (cam.protocols.isEmpty()) {
                status.text = "Found a device at $camIp, but it didn't accept Wi‑Fi settings over the network. " +
                        "Check the camera username/password above and tap the button again. " +
                        "If it still fails, this camera only supports setup through the D3DCam app."
            } else {
                status.text = "Camera ready at $camIp (${cam.protocols.joinToString()})."
            }
            findNetBtn.isEnabled = true
            sendBtn.isEnabled = true
        }
    }

    private fun pickNetwork() {
        val cam = cfg ?: return
        status.text = "Asking the camera which Wi‑Fi networks it can see…"
        findNetBtn.isEnabled = false
        lifecycleScope.launch {
            val nets = withContext(Dispatchers.IO) { runCatching { cam.scanWifi() }.getOrDefault(emptyList()) }
            findNetBtn.isEnabled = true
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
        sendBtn.isEnabled = false
        status.text = "Sending Wi‑Fi details…"
        lifecycleScope.launch {
            // Reconnect with the login currently typed, in case it was corrected.
            val cam = withContext(Dispatchers.IO) {
                cfg?.takeIf { it.user == user() && it.pass == pass() } ?: CameraConfig.open(camIp, user(), pass())
            }
            cfg = cam
            val o = withContext(Dispatchers.IO) { cam.setWifi(ssid, key) }
            if (!o.ok) {
                status.text = o.message
                sendBtn.isEnabled = true
                return@launch
            }
            prefs.edit().putString("user", user()).putString("pass", pass()).apply()
            status.text = "Saved. Restarting the camera…"
            withContext(Dispatchers.IO) { cam.rebootQuietly() }
            delay(500)
            unbindNet()
            doneText.text = "The camera is restarting and will join \"$ssid\" in about a minute.\n\n" +
                    "Connect this phone back to \"$ssid\", then tap Find my camera.\n\n" +
                    "If the camera doesn't show up after 2 minutes, the Wi‑Fi password may be wrong — reset the camera and try again."
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
        unbindNet()
    }
}
