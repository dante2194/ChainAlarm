package app.chainalarm

import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity() {

    private val cBody = Color.parseColor("#111419")
    private val cBar = Color.parseColor("#181B20")
    private val cAnchor = Color.parseColor("#1D1B20")
    private val cSurface = Color.parseColor("#36343B")
    private val cSummary = Color.parseColor("#232A36")
    private val cText = Color.parseColor("#E6E1E5")
    private val cSub = Color.parseColor("#CAC4D0")
    private val cBlue = Color.parseColor("#A8C7FA")
    private val cNavy = Color.parseColor("#062E6F")
    private val cGreen = Color.parseColor("#0F5225")
    private val cOutline = Color.parseColor("#938F99")
    private val cWarn = Color.parseColor("#FFB4AB")
    private val match = ViewGroup.LayoutParams.MATCH_PARENT
    private val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
    private val posNames = arrayOf("Start at", "End in", "Both")

    private lateinit var chain: Chain
    private lateinit var profiles: MutableList<Profile>

    private lateinit var tvTime: TextView
    private lateinit var tvLeft: TextView
    private lateinit var tvPosNote: TextView
    private lateinit var chips: List<TextView>
    private lateinit var tvCount: TextView
    private lateinit var tvInterval: TextView
    private lateinit var slCount: ChainSlider
    private lateinit var slInterval: ChainSlider
    private lateinit var etLabel: EditText
    private lateinit var tvSummary: TextView
    private lateinit var tvHint: TextView
    private lateinit var tvWarn: TextView
    private lateinit var btnCreate: TextView

    private var statusMsg: String? = null
    private var clockPkg: String? = "com.android.deskclock"
    private var running = false
    private var cancelled = false
    private var progress = 0
    private var total = 0
    private val handler = Handler(Looper.getMainLooper())

    private val ticker = object : Runnable {
        override fun run() {
            renderTime()
            handler.postDelayed(this, 15_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        chain = Store.loadDraft(this)
        profiles = Store.loadProfiles(this)
        setContentView(buildUi())
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    // ---------------------------------------------------------------- helpers

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: Int, radius: Int): GradientDrawable {
        val g = GradientDrawable()
        g.setColor(color)
        g.cornerRadius = dp(radius).toFloat()
        return g
    }

    private fun tv(s: String, size: Float, color: Int, medium: Boolean): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        if (medium) t.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        return t
    }

    private fun vbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.VERTICAL
        return l
    }

    private fun hbox(): LinearLayout {
        val l = LinearLayout(this)
        l.orientation = LinearLayout.HORIZONTAL
        l.gravity = Gravity.CENTER_VERTICAL
        return l
    }

    private fun lpMatch(top: Int): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(match, wrap)
        p.topMargin = dp(top)
        return p
    }

    private fun lpCard(): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(match, wrap)
        p.bottomMargin = dp(16)
        return p
    }

    private fun cardBox(color: Int): LinearLayout {
        val c = vbox()
        c.background = rounded(color, 28)
        c.setPadding(dp(16), dp(20), dp(16), dp(20))
        return c
    }

    private fun chipView(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 14f, cText, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), 0, dp(16), 0)
        t.minHeight = dp(32)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun styleChip(t: TextView, selected: Boolean) {
        val g = GradientDrawable()
        g.cornerRadius = dp(10).toFloat()
        if (selected) {
            g.setColor(cGreen)
            t.setTextColor(Color.WHITE)
        } else {
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(1), cOutline)
            t.setTextColor(cText)
        }
        t.background = g
    }

    private fun pill(label: String, filled: Boolean, onClick: () -> Unit): TextView {
        val t = tv(label, 14f, if (filled) cNavy else cBlue, true)
        t.gravity = Gravity.CENTER
        t.minHeight = dp(40)
        val g = GradientDrawable()
        g.cornerRadius = dp(20).toFloat()
        if (filled) {
            g.setColor(cBlue)
        } else {
            g.setColor(Color.TRANSPARENT)
            g.setStroke(dp(1), cOutline)
        }
        t.background = g
        t.setOnClickListener { onClick() }
        return t
    }

    private fun smallBtn(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 14f, cNavy, true)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(16), dp(8), dp(16), dp(8))
        t.background = rounded(cBlue, 18)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun fmtTime(h: Int, m: Int): String {
        if (DateFormat.is24HourFormat(this)) return String.format(Locale.US, "%d:%02d", h, m)
        val h12 = if (h % 12 == 0) 12 else h % 12
        return String.format(Locale.US, "%d:%02d %s", h12, m, if (h < 12) "AM" else "PM")
    }

    private fun fmtDur(secs: Int): String {
        val h = secs / 3600
        val m = (secs % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m ${secs % 60}s"
    }

    // --------------------------------------------------------------------- UI

    private fun buildUi(): View {
        val rootCol = vbox()
        rootCol.setBackgroundColor(cBody)

        // top bar
        val bar = hbox()
        bar.setBackgroundColor(cBar)
        bar.setPadding(dp(16), 0, dp(8), 0)
        bar.addView(tv("Chain Alarm", 24f, cText, false), LinearLayout.LayoutParams(0, wrap, 1f))
        val timerIc = ImageView(this)
        timerIc.setImageResource(R.drawable.ic_timer)
        timerIc.setPadding(dp(10), dp(10), dp(10), dp(10))
        timerIc.setOnClickListener { openTimers() }
        bar.addView(timerIc, LinearLayout.LayoutParams(dp(48), dp(48)))
        rootCol.addView(bar, LinearLayout.LayoutParams(match, dp(64)))

        val content = vbox()
        content.setPadding(dp(16), dp(12), dp(16), dp(24))
        val sv = ScrollView(this)
        sv.isVerticalScrollBarEnabled = false
        sv.addView(content)
        rootCol.addView(sv, LinearLayout.LayoutParams(match, 0, 1f))

        // ---- anchor time
        val anchor = hbox()
        anchor.background = rounded(cAnchor, 28)
        anchor.setPadding(dp(16), dp(24), dp(16), dp(24))
        val clockIc = ImageView(this)
        clockIc.setImageResource(R.drawable.ic_clock)
        anchor.addView(clockIc, LinearLayout.LayoutParams(dp(56), dp(28)))
        val aCol = vbox()
        aCol.addView(tv("Anchor time", 14f, cText, true))
        tvTime = tv("", 32f, cText, true)
        aCol.addView(tvTime, lpMatch(2))
        tvLeft = tv("", 18f, cBlue, true)
        aCol.addView(tvLeft, lpMatch(2))
        aCol.addView(tv("Uses the next occurrence of this time", 14f, cSub, false), lpMatch(4))
        val aLp = LinearLayout.LayoutParams(0, wrap, 1f)
        aLp.leftMargin = dp(12)
        anchor.addView(aCol, aLp)
        anchor.setOnClickListener { pickAnchor() }
        content.addView(anchor, lpCard())

        // ---- chain position
        val pos = cardBox(cSurface)
        pos.addView(tv("Chain position", 16f, cText, true))
        val chipRow = hbox()
        chips = posNames.mapIndexed { i, n ->
            chipView(n) {
                chain.position = i
                changed()
            }
        }
        for (c in chips) {
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.rightMargin = dp(8)
            chipRow.addView(c, lp)
        }
        pos.addView(chipRow, lpMatch(16))
        tvPosNote = tv("", 14f, cSub, false)
        pos.addView(tvPosNote, lpMatch(12))
        content.addView(pos, lpCard())

        // ---- count / interval / name
        val cc = cardBox(cSurface)

        val rowC = hbox()
        rowC.addView(tv("Count", 16f, cText, true), LinearLayout.LayoutParams(0, wrap, 1f))
        tvCount = tv("", 24f, cText, true)
        tvCount.setPadding(dp(16), dp(4), 0, dp(4))
        tvCount.setOnClickListener {
            askNumber("Number of alarms", 1, MAX_COUNT, chain.count) {
                chain.count = it
                changed()
            }
        }
        rowC.addView(tvCount)
        cc.addView(rowC)

        slCount = ChainSlider(this)
        slCount.min = 1
        slCount.max = MAX_COUNT
        slCount.dots = 20
        slCount.onChange = { v ->
            chain.count = v
            changed()
        }
        cc.addView(slCount, lpMatch(4))

        val rowI = hbox()
        rowI.addView(tv("Interval", 16f, cText, true), LinearLayout.LayoutParams(0, wrap, 1f))
        tvInterval = tv("", 24f, cText, true)
        tvInterval.setPadding(dp(16), dp(4), 0, dp(4))
        tvInterval.setOnClickListener {
            askNumber("Minutes between alarms", 1, MAX_INTERVAL_TYPED, chain.interval) {
                chain.interval = it
                changed()
            }
        }
        rowI.addView(tvInterval)
        cc.addView(rowI, lpMatch(4))

        slInterval = ChainSlider(this)
        slInterval.min = 1
        slInterval.max = MAX_INTERVAL_SLIDER
        slInterval.dots = 30
        slInterval.onChange = { v ->
            chain.interval = v
            changed()
        }
        cc.addView(slInterval, lpMatch(4))

        etLabel = EditText(this)
        etLabel.hint = "Optional alarm name"
        etLabel.setHintTextColor(cSub)
        etLabel.setTextColor(cText)
        etLabel.textSize = 16f
        etLabel.setSingleLine(true)
        etLabel.setPadding(dp(16), dp(16), dp(16), dp(16))
        val fieldBg = GradientDrawable()
        fieldBg.cornerRadius = dp(8).toFloat()
        fieldBg.setColor(Color.TRANSPARENT)
        fieldBg.setStroke(dp(1), cOutline)
        etLabel.background = fieldBg
        etLabel.setText(chain.label)
        etLabel.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                chain.label = s?.toString()?.trim() ?: ""
                Store.saveDraft(this@MainActivity, chain)
            }
        })
        cc.addView(etLabel, lpMatch(16))
        content.addView(cc, lpCard())

        // ---- summary + actions
        val sum = cardBox(cSummary)
        tvSummary = tv("", 20f, cText, true)
        sum.addView(tvSummary)
        tvHint = tv("", 14f, cSub, false)
        sum.addView(tvHint, lpMatch(4))
        tvWarn = tv("", 13f, cWarn, false)
        sum.addView(tvWarn, lpMatch(8))
        btnCreate = pill("Create alarms in DeskClock", true) { onCreateAlarms() }
        sum.addView(btnCreate, lpMatch(16))
        sum.addView(pill("Set first-chain timer", true) { onSetTimer() }, lpMatch(16))
        sum.addView(pill("Profiles", false) { showProfiles() }, lpMatch(16))
        content.addView(sum, lpCard())

        return rootCol
    }

    // -------------------------------------------------------------- rendering

    private fun changed() {
        statusMsg = null
        Store.saveDraft(this, chain)
        render()
    }

    private fun renderTime() {
        tvTime.text = fmtTime(chain.hour, chain.minute)
        val now = System.currentTimeMillis()
        val anchor = Planner.nextOccurrence(chain.hour, chain.minute, now)
        val mins = ((anchor - now + 59_999L) / 60_000L).toInt()
        tvLeft.text = String.format(Locale.US, "%d:%02d left", mins / 60, mins % 60)
    }

    private fun render() {
        renderTime()
        tvPosNote.text = when (chain.position) {
            0 -> "The chosen time is the first alarm."
            1 -> "The chosen time is the last alarm."
            else -> "The chosen time is the middle of the chain."
        }
        for (i in chips.indices) styleChip(chips[i], i == chain.position)

        tvCount.text = chain.count.toString()
        slCount.value = chain.count.coerceIn(slCount.min, slCount.max)
        slCount.invalidate()
        tvInterval.text = "${chain.interval}m"
        slInterval.value = chain.interval.coerceIn(slInterval.min, slInterval.max)
        slInterval.invalidate()

        tvSummary.text = "${chain.count} alarm${if (chain.count == 1) "" else "s"} + 1 timer"
        val pinned = Planner.pinnedCount(chain, System.currentTimeMillis())
        if (pinned > 0) {
            tvWarn.visibility = View.VISIBLE
            tvWarn.text = "$pinned alarm${if (pinned == 1) "" else "s"} fall more than 24 h ahead. " +
                "DeskClock can only set those as weekly repeats."
        } else {
            tvWarn.visibility = View.GONE
        }
        renderRun()
    }

    private fun renderRun() {
        if (running) {
            btnCreate.text = "Creating $progress / $total · tap to cancel"
            tvHint.text = "Keep the app open until it finishes"
        } else {
            btnCreate.text = "Create alarms in DeskClock"
            tvHint.text = statusMsg ?: "Actions open Android DeskClock"
        }
    }

    // ---------------------------------------------------------------- actions

    private fun pickAnchor() {
        TimePickerDialog(this, { _, h, m ->
            chain.hour = h
            chain.minute = m
            changed()
        }, chain.hour, chain.minute, DateFormat.is24HourFormat(this)).show()
    }

    private fun askNumber(title: String, min: Int, max: Int, cur: Int, done: (Int) -> Unit) {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER
        et.filters = arrayOf<InputFilter>(InputFilter.LengthFilter(3))
        et.setText(cur.toString())
        et.setSelection(et.text.length)
        et.textSize = 28f
        et.gravity = Gravity.CENTER
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et, FrameLayout.LayoutParams(match, wrap))
        AlertDialog.Builder(this)
            .setTitle("$title ($min–$max)")
            .setView(box)
            .setPositiveButton("OK") { _, _ ->
                done((et.text.toString().toIntOrNull() ?: cur).coerceIn(min, max))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun openTimers() {
        try {
            startActivity(Intent(AlarmClock.ACTION_SHOW_TIMERS))
        } catch (e: Exception) {
            toast("No clock app found")
        }
    }

    private fun withFallback(f: (String?) -> Boolean): Boolean {
        if (f(clockPkg)) return true
        if (clockPkg != null) {
            clockPkg = null // DeskClock not reachable: let Android choose any clock app
            return f(null)
        }
        return false
    }

    private fun onCreateAlarms() {
        if (running) {
            cancelled = true
            return
        }
        val label = chain.label.ifBlank { "Chain alarm" }
        startRun(Planner.slots(chain, label, System.currentTimeMillis()))
    }

    private fun onSetTimer() {
        val now = System.currentTimeMillis()
        val secs = ((Planner.firstAlarm(chain, now) - now) / 1000L).toInt().coerceIn(1, 86400)
        val label = chain.label.ifBlank { "First alarm" }
        val ok = withFallback { pkg -> Planner.fireTimer(this, secs, label, pkg) }
        statusMsg = if (ok) "Timer set for ${fmtDur(secs)}" else "Couldn't reach DeskClock"
        render()
    }

    private fun startRun(slots: List<Slot>) {
        if (slots.isEmpty()) return
        running = true
        cancelled = false
        progress = 0
        total = slots.size
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderRun()
        var ok = 0
        val step = object : Runnable {
            override fun run() {
                if (cancelled || progress >= total) {
                    running = false
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    statusMsg = if (cancelled) "Cancelled after $progress of $total"
                    else if (ok == total) "✓ $ok alarms created"
                    else "Created $ok of $total — check DeskClock access"
                    renderRun()
                    return
                }
                var n = 0
                while (n < BATCH && progress < total && !cancelled) {
                    val s = slots[progress]
                    if (withFallback { pkg -> Planner.fireAlarm(this@MainActivity, s, pkg) }) ok++
                    progress++
                    n++
                }
                renderRun()
                handler.postDelayed(this, BATCH_DELAY_MS)
            }
        }
        handler.post(step)
    }

    // --------------------------------------------------------------- profiles

    private fun runProfile(p: Profile) {
        if (running) {
            toast("Already creating alarms")
            return
        }
        val now = System.currentTimeMillis()
        val all = ArrayList<Slot>()
        for (ch in p.chains) all.addAll(Planner.slots(ch, ch.label.ifBlank { p.name }, now))
        if (all.isEmpty()) {
            toast("Profile is empty")
            return
        }
        statusMsg = null
        startRun(all)
    }

    private fun askSave(after: () -> Unit) {
        val et = EditText(this)
        et.hint = "Profile name"
        et.setSingleLine(true)
        et.setText(if (profiles.isEmpty()) "School Day" else "")
        val box = FrameLayout(this)
        box.setPadding(dp(24), dp(8), dp(24), 0)
        box.addView(et, FrameLayout.LayoutParams(match, wrap))
        AlertDialog.Builder(this)
            .setTitle("Save chain to profile")
            .setMessage("Use an existing name to add this chain to that profile.")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = et.text.toString().trim().ifEmpty { "Profile" }
                var p: Profile? = null
                for (x in profiles) if (x.name.equals(name, ignoreCase = true)) p = x
                if (p == null) {
                    p = Profile(name = name)
                    profiles.add(p)
                }
                p.chains.add(chain.copy())
                Store.saveProfiles(this, profiles)
                toast("Saved to ${p.name}")
                after()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun loadChain(c: Chain) {
        chain = c.copy()
        etLabel.setText(chain.label)
        changed()
    }

    private fun showProfiles() {
        val box = vbox()
        box.setPadding(dp(16), dp(8), dp(16), dp(0))
        val sv = ScrollView(this)
        sv.addView(box)
        val dlg = AlertDialog.Builder(this)
            .setTitle("Profiles")
            .setView(sv)
            .setNegativeButton("Close", null)
            .create()

        fun fill() {
            box.removeAllViews()
            box.addView(pill("＋  Save current chain…", false) { askSave { fill() } }, lpMatch(4))
            if (profiles.isEmpty()) {
                box.addView(tv("No profiles yet. A profile can hold several chains and runs them all with one tap.", 14f, cSub, false), lpMatch(12))
            }
            for (p in profiles) {
                val c = vbox()
                c.setPadding(dp(14), dp(12), dp(14), dp(12))
                c.background = rounded(cSurface, 16)

                val head = hbox()
                val info = vbox()
                info.addView(tv(p.name, 17f, cText, true))
                val n = p.totalAlarms()
                info.addView(
                    tv("$n alarm${if (n == 1) "" else "s"} · ${p.chains.size} chain${if (p.chains.size == 1) "" else "s"}", 13f, cBlue, false)
                )
                head.addView(info, LinearLayout.LayoutParams(0, wrap, 1f))
                head.addView(smallBtn("Run") {
                    dlg.dismiss()
                    runProfile(p)
                })
                val del = tv("✕", 18f, cWarn, true)
                del.setPadding(dp(14), dp(6), dp(6), dp(6))
                del.setOnClickListener {
                    profiles.removeAll { it.id == p.id }
                    Store.saveProfiles(this, profiles)
                    fill()
                }
                head.addView(del)
                c.addView(head)

                for (ch in p.chains) {
                    val line = tv(
                        "${fmtTime(ch.hour, ch.minute)} · ×${ch.count} / ${ch.interval}m · ${posNames[ch.position]}",
                        13f, cSub, false
                    )
                    line.setPadding(0, dp(6), 0, dp(6))
                    line.setOnClickListener {
                        dlg.dismiss()
                        loadChain(ch)
                    }
                    line.setOnLongClickListener {
                        p.chains.removeAll { it === ch }
                        Store.saveProfiles(this, profiles)
                        toast("Chain removed")
                        fill()
                        true
                    }
                    c.addView(line)
                }
                if (p.chains.isNotEmpty()) {
                    c.addView(tv("Tap a chain to load it · long-press to remove", 11f, cOutline, false), lpMatch(2))
                }
                box.addView(c, lpMatch(12))
            }
        }
        fill()
        dlg.show()
    }
}
