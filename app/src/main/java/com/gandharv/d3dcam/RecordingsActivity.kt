package com.gandharv.d3dcam

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/** Lists the recordings on the camera's SD card, grouped by day. */
class RecordingsActivity : AppCompatActivity() {

    private lateinit var store: CameraStore
    private lateinit var cam: Camera
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar
    private lateinit var days: ChipGroup
    private lateinit var list: RecyclerView
    private var all: List<Recording> = emptyList()
    private var shown: List<Recording> = emptyList()
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val dayFmtIn = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val dayFmtOut = SimpleDateFormat("EEE d MMM", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = CameraStore(this)
        cam = store.get(intent.getStringExtra("camId")) ?: run { finish(); return }
        val c = this
        val root = Ui.column(c, 16)
        root.fitsSystemWindows = true
        root.addView(TextView(c).apply {
            text = "Recordings · ${cam.name}"; textSize = 22f; setTypeface(typeface, Typeface.BOLD)
        })
        status = Ui.note(c, "Reading the SD card…")
        root.addView(status)
        progress = ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true }
        root.addView(Ui.full(progress))
        days = ChipGroup(c).apply { isSingleSelection = true; isSingleLine = true }
        root.addView(HorizontalScrollView(c).apply {
            isHorizontalScrollBarEnabled = false
            addView(days)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        list = RecyclerView(c).apply {
            layoutManager = LinearLayoutManager(c)
            adapter = Adapter()
        }
        root.addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(Ui.note(c, "Tap to play · long-press to save to your phone"))
        setContentView(root)
        load()
    }

    private fun load() {
        lifecycleScope.launch {
            val cfg = withContext(Dispatchers.IO) { CameraConfig.open(cam.ip, cam.user, cam.pass) }
            val sd = withContext(Dispatchers.IO) { cfg.sdInfo() }
            val browser = SdBrowser(cam.user, cam.pass)
            val root = withContext(Dispatchers.IO) { browser.findRoot(cfg.sdRoots) }
            if (root == null) {
                progress.visibility = View.GONE
                status.text = "${sd.describe()}\n\nCouldn't open the SD card folder on the camera. " +
                        "Make sure a card is inserted and recording is turned on in Camera settings."
                return@launch
            }
            all = withContext(Dispatchers.IO) {
                browser.list(root) { folder -> lifecycleScope.launch(Dispatchers.Main) { status.text = "Reading $folder…" } }
            }
            progress.visibility = View.GONE
            if (all.isEmpty()) {
                status.text = "${sd.describe()}\n\nNo recordings yet. Turn on continuous recording in Camera settings."
                return@launch
            }
            status.text = "${sd.describe()} · ${all.size} recordings"
            showDays()
        }
    }

    private fun showDays() {
        days.removeAllViews()
        val keys = all.map { it.dayKey }.distinct()
        keys.forEachIndexed { i, k ->
            val label = runCatching { dayFmtOut.format(dayFmtIn.parse(k)!!) }.getOrDefault(k)
            val chip = Chip(this).apply {
                text = "$label (${all.count { it.dayKey == k }})"
                isCheckable = true
                id = View.generateViewId()
                setOnClickListener { showDay(k) }
            }
            days.addView(chip)
            if (i == 0) { chip.isChecked = true; showDay(k) }
        }
    }

    private fun showDay(key: String) {
        shown = all.filter { it.dayKey == key }
        list.adapter?.notifyDataSetChanged()
        list.scrollToPosition(0)
    }

    private fun play(index: Int) {
        // Play the day oldest → newest so "next" moves forward in time.
        val ordered = shown.reversed()
        val pos = ordered.size - 1 - index
        startActivity(Intent(this, PlaybackActivity::class.java)
            .putExtra("camId", cam.id)
            .putStringArrayListExtra("urls", ArrayList(ordered.map { it.url }))
            .putStringArrayListExtra("titles", ArrayList(ordered.map { title(it) }))
            .putExtra("index", pos))
    }

    private fun title(r: Recording): String {
        val s = r.start?.let { timeFmt.format(it) }
        val e = r.end?.let { timeFmt.format(it) }
        return when {
            s != null && e != null -> "$s – $e"
            s != null -> s
            else -> r.name
        }
    }

    private fun saveClip(r: Recording) {
        val dlg = MaterialAlertDialogBuilder(this)
            .setTitle("Saving to phone")
            .setMessage("Downloading ${r.name}…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    ClipSaver.save(this@RecordingsActivity, r.url, cam.user, cam.pass) { pct ->
                        lifecycleScope.launch(Dispatchers.Main) { dlg.setMessage("Downloading ${r.name}… $pct%") }
                    }
                }
            }
            dlg.dismiss()
            Toast.makeText(this@RecordingsActivity,
                res.fold({ "Saved to $it" }, { "Couldn't save: ${it.message}" }), Toast.LENGTH_LONG).show()
        }
    }

    private inner class Holder(val v: LinearLayout, val title: TextView, val sub: TextView) : RecyclerView.ViewHolder(v)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val c = parent.context
            val pad = Ui.dp(c, 12)
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                isClickable = true
                isFocusable = true
                val tv = android.util.TypedValue()
                c.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            val t = TextView(c).apply { textSize = 16f; setTypeface(typeface, Typeface.BOLD) }
            val s = TextView(c).apply { alpha = 0.7f; textSize = 12f; gravity = Gravity.START }
            row.addView(t); row.addView(s)
            return Holder(row, t, s)
        }

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(h: Holder, position: Int) {
            val r = shown[position]
            h.title.text = (if (r.alarm) "🔔 " else "▶ ") + title(r)
            h.sub.text = (if (r.alarm) "Alarm recording · " else "Continuous · ") + r.name
            h.v.setOnClickListener { play(h.bindingAdapterPosition) }
            h.v.setOnLongClickListener {
                MaterialAlertDialogBuilder(this@RecordingsActivity)
                    .setTitle(title(r))
                    .setItems(arrayOf("Play", "Save to phone (MP4)")) { _, which ->
                        if (which == 0) play(h.bindingAdapterPosition) else saveClip(r)
                    }.show()
                true
            }
        }
    }
}
