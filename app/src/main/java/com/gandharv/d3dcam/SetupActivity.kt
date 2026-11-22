package com.gandharv.d3dcam

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Camera settings: info, Wi‑Fi, password, picture, night vision, quality, time, restart. */
class SetupActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("cam", MODE_PRIVATE) }
    private lateinit var ip: String
    private lateinit var user: String
    private lateinit var pass: String
    private var cfg: CameraConfig? = null

    private lateinit var status: TextView
    private lateinit var infoText: TextView
    private lateinit var log: TextView
    private lateinit var controls: LinearLayout
    private lateinit var bright: Slider
    private lateinit var contrast: Slider
    private lateinit var saturation: Slider
    private lateinit var flipSw: MaterialSwitch
    private lateinit var mirrorSw: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ip = intent.getStringExtra("ip") ?: ""
        user = intent.getStringExtra("user") ?: "admin"
        pass = intent.getStringExtra("pass") ?: "admin"
        buildUi()
        load()
    }

    private fun buildUi() {
        val c = this
        val root = Ui.column(c, 20)
        root.fitsSystemWindows = true

        root.addView(TextView(c).apply { text = "Camera settings"; textSize = 24f; setTypeface(typeface, android.graphics.Typeface.BOLD) })
        status = Ui.note(c, "Connecting to $ip…")
        root.addView(status)

        controls = Ui.column(c)
        controls.alpha = 0.4f
        root.addView(controls)

        // Device info
        controls.addView(Ui.header(c, "Device"))
        infoText = Ui.note(c, "")
        controls.addView(infoText)
        controls.addView(Ui.row(c,
            Ui.button(c, "Sync time with phone", outlined = true) { act { it.syncTime() } },
            Ui.button(c, "Restart camera", outlined = true) { confirmReboot() }
        ))

        // Wi‑Fi
        controls.addView(Ui.header(c, "Wi‑Fi"))
        controls.addView(Ui.note(c, "Move the camera to a different Wi‑Fi network or update the Wi‑Fi password."))
        controls.addView(Ui.full(Ui.button(c, "Change camera Wi‑Fi", outlined = true) {
            startActivity(Intent(c, WifiSetupActivity::class.java)
                .putExtra("ip", ip).putExtra("user", user).putExtra("pass", pass))
        }))

        // Password
        controls.addView(Ui.header(c, "Login password"))
        controls.addView(Ui.note(c, "Every camera of this model ships with admin / admin. Set your own password."))
        val (l1, newPass) = Ui.field(c, "New password", password = true)
        val (l2, newPass2) = Ui.field(c, "Repeat new password", password = true)
        controls.addView(Ui.full(l1)); controls.addView(Ui.full(l2))
        controls.addView(Ui.full(Ui.button(c, "Change password") {
            val a = newPass.text.toString()
            val b = newPass2.text.toString()
            when {
                a.length < 6 -> toast("Use at least 6 characters")
                a != b -> toast("The two passwords don't match")
                a.any { it in "&=?#%'\" " } -> toast("Avoid spaces and & = ? # % ' \" — many cameras can't store them")
                else -> act { cam ->
                    val o = cam.changePassword(a)
                    if (o.ok) {
                        pass = a
                        prefs.edit().putString("pass", a).apply()
                    }
                    o
                }
            }
        }))

        // Picture
        controls.addView(Ui.header(c, "Picture"))
        bright = slider(); contrast = slider(); saturation = slider()
        controls.addView(Ui.note(c, "Brightness")); controls.addView(bright)
        controls.addView(Ui.note(c, "Contrast")); controls.addView(contrast)
        controls.addView(Ui.note(c, "Colour")); controls.addView(saturation)
        controls.addView(Ui.full(Ui.button(c, "Apply picture settings") {
            val b = bright.value.toInt(); val ct = contrast.value.toInt(); val s = saturation.value.toInt()
            act { it.setPicture(b, ct, s) }
        }))
        flipSw = MaterialSwitch(c).apply { text = "Flip upside down (ceiling mount)" }
        mirrorSw = MaterialSwitch(c).apply { text = "Mirror left–right" }
        controls.addView(Ui.full(flipSw, 10)); controls.addView(Ui.full(mirrorSw))
        controls.addView(Ui.full(Ui.button(c, "Apply flip / mirror", outlined = true) {
            val f = flipSw.isChecked; val m = mirrorSw.isChecked
            act { it.setFlip(f, m) }
        }))

        // Night vision
        controls.addView(Ui.header(c, "Night vision"))
        controls.addView(Ui.row(c,
            Ui.button(c, "Auto", outlined = true) { act { it.setNightVision("auto") } },
            Ui.button(c, "Always on", outlined = true) { act { it.setNightVision("on") } },
            Ui.button(c, "Off", outlined = true) { act { it.setNightVision("off") } }
        ))

        // Quality
        controls.addView(Ui.header(c, "Video quality"))
        controls.addView(Ui.note(c, "Lower quality uses less Wi‑Fi and SD card space."))
        controls.addView(Ui.row(c,
            Ui.button(c, "High", outlined = true) { act { it.setQuality("high") } },
            Ui.button(c, "Medium", outlined = true) { act { it.setQuality("medium") } },
            Ui.button(c, "Low", outlined = true) { act { it.setQuality("low") } }
        ))

        root.addView(Ui.header(c, "Activity"))
        log = Ui.note(c, "")
        root.addView(log)

        setContentView(ScrollView(c).apply { addView(root); isFillViewport = true })
    }

    private fun slider() = Slider(this).apply {
        valueFrom = 0f; valueTo = 100f; stepSize = 1f; value = 50f
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    private fun addLog(s: String) {
        log.text = if (log.text.isNullOrEmpty()) s else "$s\n${log.text}"
    }

    private fun setEnabledAll(enabled: Boolean) {
        controls.alpha = if (enabled) 1f else 0.4f
        fun walk(v: View) {
            v.isEnabled = enabled
            if (v is android.view.ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(controls)
    }

    private fun load() {
        setEnabledAll(false)
        lifecycleScope.launch {
            val cam = withContext(Dispatchers.IO) { CameraConfig.open(ip, user, pass) }
            cfg = cam
            val info = withContext(Dispatchers.IO) { cam.info() }
            infoText.text = info.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            if (cam.protocols.isEmpty()) {
                status.text = "Connected to $ip, but the camera didn't answer any settings command. " +
                        "Check the username/password. Some D3D firmware only allows settings through the D3DCam app."
                setEnabledAll(true)
                return@launch
            }
            status.text = "Connected · ${cam.protocols.joinToString()}"
            val pic = withContext(Dispatchers.IO) { cam.getPicture() }
            pic.brightness?.let { bright.value = it.coerceIn(0, 100).toFloat() }
            pic.contrast?.let { contrast.value = it.coerceIn(0, 100).toFloat() }
            pic.saturation?.let { saturation.value = it.coerceIn(0, 100).toFloat() }
            pic.flip?.let { flipSw.isChecked = it }
            pic.mirror?.let { mirrorSw.isChecked = it }
            setEnabledAll(true)
        }
    }

    private fun act(block: (CameraConfig) -> Outcome) {
        val cam = cfg ?: return toast("Still connecting…")
        setEnabledAll(false)
        lifecycleScope.launch {
            val o = withContext(Dispatchers.IO) {
                try { block(cam) } catch (e: Exception) { Outcome(false, "Error: ${e.message}") }
            }
            addLog((if (o.ok) "✓ " else "✗ ") + o.message)
            toast(o.message)
            setEnabledAll(true)
        }
    }

    private fun confirmReboot() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Restart camera?")
            .setMessage("Video will stop for about a minute.")
            .setPositiveButton("Restart") { _, _ -> act { it.reboot() } }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
