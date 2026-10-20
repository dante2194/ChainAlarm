package app.chainalarm

import android.app.Activity
import android.app.AlarmManager
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.AlarmClock
import android.provider.Settings
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private val cBody = Color.parseColor("#111419")
    private val cBar = Color.parseColor("#181B20")
    private val cAnchor = Color.parseColor("#1D1B20")
    private val cSurface = Color.parseColor("#36343B")
    private val cChip = Color.parseColor("#2B2D55")
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
    private val dayShort = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    private val dispOrder = intArrayOf(5, 6, 0, 1, 2, 3, 4) // Sat Sun Mon Tue Wed Thu Fri (bit indexes)

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
    private var runner: AlarmRunner? = null
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
        Auto.rescheduleAll(this) // re-arm automation whenever the app is opened
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

    private fun lpW(weight: Float): LinearLayout.LayoutParams {
        val p = LinearLayout.LayoutParams(0, wrap, weight)
        p.setMargins(dp(3), dp(3), dp(3), dp(3))
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

    private fun stepBtn(label: String, onClick: () -> Unit): TextView {
        val t = tv(label, 18f, cText, true)
        t.gravity = Gravity.CENTER
        t.minWidth = dp(44)
        t.minHeight = dp(40)
        t.background = rounded(cChip, 12)
        t.setOnClickListener { onClick() }
        return t
    }

    private fun stepRow(title: String, value: TextView, minus: () -> Unit, plus: () -> Unit): View {
        val r = hbox()
        r.addView(tv(title, 15f, cText, true), LinearLayout.LayoutParams(0, wrap, 1f))
        r.addView(stepBtn("−", minus))
        value.gravity = Gravity.CENTER
        value.minWidth = dp(40)
        r.addView(value)
        r.addView(stepBtn("+", plus))
        return r
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
            clockPkg = null
            return f(null)
        }
        return false
    }

    private fun onCreateAlarms() {
        if (running) {
            runner?.cancelled = true
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
        progress = 0
        total = slots.size
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        renderRun()
        val r = AlarmRunner(
            this,
            slots,
            { done, all ->
                progress = done
                total = all
                renderRun()
            },
            { ok, all, cancelled ->
                running = false
                runner = null
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                statusMsg = if (cancelled) "Cancelled after $progress of $all"
                else if (ok == all) "✓ $ok alarms created"
                else "Created $ok of $all — check DeskClock access"
                renderRun()
            }
        )
        runner = r
        r.start()
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
                var found: Profile? = null
                for (x in profiles) if (x.name.equals(name, ignoreCase = true)) found = x
                val p: Profile = found ?: Profile(name = name)
                if (found == null) profiles.add(p)
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

    private fun daysLabel(mask: Int): String {
        val sel = ArrayList<Int>()
        for (k in 0..6) if (((mask shr dispOrder[k]) and 1) == 1) sel.add(k)
        if (sel.isEmpty()) return "no days"
        if (sel.size == 7) return "every day"
        if (sel.size >= 3 && sel[sel.size - 1] - sel[0] == sel.size - 1) {
            return dayShort[dispOrder[sel[0]]] + "–" + dayShort[dispOrder[sel[sel.size - 1]]]
        }
        val names = ArrayList<String>()
        for (k in sel) names.add(dayShort[dispOrder[k]])
        return names.joinToString(" ")
    }

    private fun scheduleText(p: Profile): String {
        val parts = ArrayList<String>()
        parts.add(if (p.autoOn) "Auto ${fmtTime(p.sendHour, p.sendMinute)}" else "Manual")
        parts.add(daysLabel(p.days))
        if (p.offWeeks > 0) parts.add("${p.onWeeks} wk on / ${p.offWeeks} off")
        return parts.joinToString(" · ")
    }

    private fun autoStatus(): String {
        val trig = Store.lastTrigger(this)
        val run = Store.lastRunAt(this)
        val f = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
        if (trig == 0L && run == 0L) return "No automatic run yet"
        if (trig > run) {
            return "⚠ Triggered ${f.format(Date(trig))} but nothing opened. " +
                "Allow “Display over other apps” and background pop-ups (Automation setup)."
        }
        return "Last run ${f.format(Date(run))}: ${Store.lastRunText(this)}"
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
            box.addView(tv(autoStatus(), 12f, cSub, false))
            box.addView(pill("＋  Save current chain…", false) { askSave { fill() } }, lpMatch(10))
            box.addView(pill("Automation setup", false) { showAutoSetup() }, lpMatch(8))
            if (profiles.isEmpty()) {
                box.addView(
                    tv("No profiles yet. A profile can hold several chains and runs them all with one tap.", 14f, cSub, false),
                    lpMatch(12)
                )
            }
            for (p in profiles) {
                val c = vbox()
                c.setPadding(dp(14), dp(12), dp(14), dp(12))
                c.background = rounded(cSurface, 16)

                c.addView(tv(p.name, 17f, cText, true))
                val n = p.totalAlarms()
                c.addView(
                    tv("$n alarm${if (n == 1) "" else "s"} · ${p.chains.size} chain${if (p.chains.size == 1) "" else "s"}", 13f, cBlue, false),
                    lpMatch(2)
                )
                c.addView(tv(scheduleText(p), 13f, cSub, false), lpMatch(2))

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
                    c.addView(line)
                }
                if (p.chains.isNotEmpty()) {
                    c.addView(tv("Tap a chain to load it on the main screen", 11f, cOutline, false), lpMatch(0))
                }

                val row = hbox()
                row.addView(smallBtn("Run") {
                    dlg.dismiss()
                    runProfile(p)
                }, lpW(1f))
                row.addView(smallBtn("Edit") {
                    showProfileEditor(p) { fill() }
                }, lpW(1f))
                row.addView(smallBtn("✕") {
                    Auto.cancel(this, p.id)
                    profiles.removeAll { it.id == p.id }
                    Store.saveProfiles(this, profiles)
                    fill()
                }, lpW(1f))
                c.addView(row, lpMatch(10))
                box.addView(c, lpMatch(12))
            }
        }
        fill()
        dlg.show()
    }

    private fun previewText(w: Profile): String {
        val f = SimpleDateFormat("EEE d", Locale.getDefault())
        val c = Calendar.getInstance()
        c.set(Calendar.HOUR_OF_DAY, 12)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val out = ArrayList<String>()
        for (d in 0 until 42) {
            if (Schedule.isActive(w, c.timeInMillis)) out.add(f.format(c.time))
            c.add(Calendar.DAY_OF_MONTH, 1)
            if (out.size >= 10) break
        }
        return if (out.isEmpty()) "No active days" else "Next ring days: " + out.joinToString(" · ")
    }

    private fun showProfileEditor(p: Profile, onSaved: () -> Unit) {
        val w = p.copy(chains = p.chains.map { it.copy() }.toMutableList())
        if (w.anchorWeek == 0L) w.anchorWeek = Schedule.weekStart(System.currentTimeMillis(), w.weekStartDow)

        val box = vbox()
        box.setPadding(dp(20), dp(8), dp(20), dp(0))
        var refresh: () -> Unit = {}

        val nameEt = EditText(this)
        nameEt.setText(w.name)
        nameEt.hint = "Profile name"
        nameEt.setSingleLine(true)
        box.addView(nameEt, lpMatch(0))

        // ring days
        box.addView(tv("Alarms ring on", 14f, cBlue, true), lpMatch(16))
        val dayChips = ArrayList<TextView>()
        val dayRow = hbox()
        for (k in 0..6) {
            val bit = dispOrder[k]
            val c = chipView(dayShort[bit]) {
                w.days = w.days xor (1 shl bit)
                refresh()
            }
            c.textSize = 12f
            c.setPadding(dp(4), 0, dp(4), 0)
            dayChips.add(c)
            val lp = LinearLayout.LayoutParams(0, wrap, 1f)
            lp.rightMargin = dp(3)
            dayRow.addView(c, lp)
        }
        box.addView(dayRow, lpMatch(8))

        // week cycle
        box.addView(tv("Week cycle", 14f, cBlue, true), lpMatch(18))
        val onTv = tv("", 18f, cText, true)
        val offTv = tv("", 18f, cText, true)
        box.addView(stepRow("Weeks on", onTv, {
            if (w.onWeeks > 1) w.onWeeks--
            refresh()
        }, {
            if (w.onWeeks < 8) w.onWeeks++
            refresh()
        }), lpMatch(6))
        box.addView(stepRow("Weeks off", offTv, {
            if (w.offWeeks > 0) w.offWeeks--
            refresh()
        }, {
            if (w.offWeeks < 8) w.offWeeks++
            refresh()
        }), lpMatch(6))

        val cycleBox = vbox()
        cycleBox.addView(tv("A week starts on", 13f, cSub, false), lpMatch(10))
        val wsDows = intArrayOf(Calendar.SATURDAY, Calendar.SUNDAY, Calendar.MONDAY)
        val wsNames = arrayOf("Sat", "Sun", "Mon")
        val wsChips = ArrayList<TextView>()
        val wsRow = hbox()
        for (k in 0..2) {
            val c = chipView(wsNames[k]) {
                w.weekStartDow = wsDows[k]
                w.anchorWeek = Schedule.weekStart(w.anchorWeek, w.weekStartDow)
                refresh()
            }
            wsChips.add(c)
            val lp = LinearLayout.LayoutParams(wrap, wrap)
            lp.rightMargin = dp(8)
            wsRow.addView(c, lp)
        }
        cycleBox.addView(wsRow, lpMatch(6))
        val anchorTv = tv("", 13f, cSub, false)
        cycleBox.addView(anchorTv, lpMatch(12))
        val anchorRow = hbox()
        anchorRow.addView(smallBtn("This week is “on”") {
            w.anchorWeek = Schedule.weekStart(System.currentTimeMillis(), w.weekStartDow)
            refresh()
        }, lpW(1f))
        anchorRow.addView(smallBtn("Next week is “on”") {
            w.anchorWeek = Schedule.weekStart(Schedule.addWeeks(System.currentTimeMillis(), 1), w.weekStartDow)
            refresh()
        }, lpW(1f))
        cycleBox.addView(anchorRow, lpMatch(6))
        box.addView(cycleBox)

        // automation
        box.addView(tv("Automation", 14f, cBlue, true), lpMatch(18))
        val autoRow = hbox()
        val autoChip = chipView("") {
            w.autoOn = !w.autoOn
            refresh()
        }
        val lpA = LinearLayout.LayoutParams(wrap, wrap)
        lpA.rightMargin = dp(10)
        autoRow.addView(autoChip, lpA)
        val timeBtn = smallBtn("") {
            TimePickerDialog(this, { _, h, m ->
                w.sendHour = h
                w.sendMinute = m
                refresh()
            }, w.sendHour, w.sendMinute, DateFormat.is24HourFormat(this)).show()
        }
        autoRow.addView(timeBtn)
        box.addView(autoRow, lpMatch(8))
        val autoNote = tv(
            "Creates the alarms by itself at that time, for the next 24 h, only on active days. " +
                "Needs the setup in “Automation setup”.",
            12f, cSub, false
        )
        box.addView(autoNote, lpMatch(6))

        val preview = tv("", 12f, cBlue, false)
        box.addView(preview, lpMatch(14))

        // chains
        box.addView(tv("Chains", 14f, cBlue, true), lpMatch(18))
        val chainBox = vbox()
        box.addView(chainBox, lpMatch(4))
        box.addView(
            tv("Add chains from the main screen with “Save current chain”.", 11f, cOutline, false),
            lpMatch(4)
        )

        fun fillChains() {
            chainBox.removeAllViews()
            if (w.chains.isEmpty()) chainBox.addView(tv("No chains", 13f, cSub, false))
            for (ch in w.chains) {
                val r = hbox()
                r.addView(
                    tv("${fmtTime(ch.hour, ch.minute)} · ×${ch.count} / ${ch.interval}m · ${posNames[ch.position]}", 13f, cText, false),
                    LinearLayout.LayoutParams(0, wrap, 1f)
                )
                val x = tv("✕", 16f, cWarn, true)
                x.setPadding(dp(14), dp(8), dp(4), dp(8))
                x.setOnClickListener {
                    w.chains.removeAll { it === ch }
                    fillChains()
                }
                r.addView(x)
                chainBox.addView(r)
            }
        }
        fillChains()

        refresh = {
            for (k in 0..6) styleChip(dayChips[k], ((w.days shr dispOrder[k]) and 1) == 1)
            onTv.text = w.onWeeks.toString()
            offTv.text = w.offWeeks.toString()
            cycleBox.visibility = if (w.offWeeks > 0) View.VISIBLE else View.GONE
            for (k in 0..2) styleChip(wsChips[k], w.weekStartDow == wsDows[k])
            anchorTv.text = "First “on” week starts " +
                SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(w.anchorWeek))
            styleChip(autoChip, w.autoOn)
            autoChip.text = if (w.autoOn) "Auto-send ON" else "Auto-send OFF"
            timeBtn.text = "at " + fmtTime(w.sendHour, w.sendMinute)
            timeBtn.visibility = if (w.autoOn) View.VISIBLE else View.GONE
            autoNote.visibility = if (w.autoOn) View.VISIBLE else View.GONE
            preview.text = previewText(w)
        }
        refresh()

        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Edit profile")
            .setView(sv)
            .setPositiveButton("Save") { _, _ ->
                p.name = nameEt.text.toString().trim().ifEmpty { "Profile" }
                p.days = w.days
                p.onWeeks = w.onWeeks
                p.offWeeks = w.offWeeks
                p.weekStartDow = w.weekStartDow
                p.anchorWeek = w.anchorWeek
                p.autoOn = w.autoOn
                p.sendHour = w.sendHour
                p.sendMinute = w.sendMinute
                p.chains.clear()
                p.chains.addAll(w.chains)
                Store.saveProfiles(this, profiles)
                Auto.rescheduleAll(this)
                if (p.autoOn && !Settings.canDrawOverlays(this)) {
                    toast("Open “Automation setup” and allow Display over other apps")
                }
                onSaved()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ------------------------------------------------------- automation setup

    private fun openSafely(i: Intent) {
        try {
            startActivity(i)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            } catch (e2: Exception) {
                toast("Open Settings → Apps → Chain Alarm")
            }
        }
    }

    private fun exactOk(): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun setupRow(box: LinearLayout, title: String, ok: Boolean?, note: String, actionLabel: String?, action: (() -> Unit)?) {
        val mark = when (ok) {
            true -> "✓  "
            false -> "✗  "
            null -> "•  "
        }
        box.addView(tv(mark + title, 15f, if (ok == false) cWarn else cText, true), lpMatch(16))
        box.addView(tv(note, 12f, cSub, false), lpMatch(2))
        if (actionLabel != null && action != null) {
            box.addView(smallBtn(actionLabel) { action() }, lpMatch(6))
        }
    }

    private fun showAutoSetup() {
        val box = vbox()
        box.setPadding(dp(20), dp(4), dp(20), dp(0))

        setupRow(
            box, "Display over other apps", Settings.canDrawOverlays(this),
            "The key permission. It lets the app open itself while the phone is idle so it can create the alarms.",
            "Open setting"
        ) {
            openSafely(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val exact = exactOk()
        setupRow(
            box, "Exact alarms", exact,
            "Makes the 23:00 trigger fire on time.",
            if (!exact && Build.VERSION.SDK_INT >= 31) "Open setting" else null
        ) {
            if (Build.VERSION.SDK_INT >= 31) {
                openSafely(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            }
        }

        setupRow(
            box, "Battery: no restrictions", batteryOk(),
            "Also set Settings → Apps → Chain Alarm → Battery saver → No restrictions.",
            "Open setting"
        ) {
            openSafely(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }

        setupRow(
            box, "Autostart (Xiaomi)", null,
            "Switch it on so the schedule survives a reboot or the app being killed. Can't be detected, check it by eye.",
            "Open Autostart"
        ) {
            val i = Intent()
            i.component = ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
            openSafely(i)
        }

        setupRow(
            box, "Pop-ups in background (Xiaomi)", null,
            "Permissions → Other permissions → “Display pop-up windows while running in the background” → Allow. Also allow “Show on lock screen”.",
            "Open permissions"
        ) {
            val i = Intent("miui.intent.action.APP_PERM_EDITOR")
            i.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            i.putExtra("extra_pkgname", packageName)
            openSafely(i)
        }

        box.addView(tv("Tip: lock the app in Recents (pull the card down or use the lock icon) so HyperOS doesn't kill it.", 12f, cSub, false), lpMatch(16))
        box.addView(tv(autoStatus(), 12f, cBlue, false), lpMatch(14))
        box.addView(pill("Test background launch (20 s)", true) {
            Auto.scheduleTest(this)
            toast("Lock your phone now. A test fires in 20 s.")
        }, lpMatch(12))

        val sv = ScrollView(this)
        sv.addView(box)
        AlertDialog.Builder(this)
            .setTitle("Automation setup")
            .setView(sv)
            .setPositiveButton("Close", null)
            .show()
    }
}
