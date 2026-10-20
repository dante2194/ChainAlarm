package app.chainalarm

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/** Invisible-ish activity that wakes the screen and creates the scheduled alarms. */
class RunActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Intent>()
    private var busy = false
    private lateinit var status: TextView

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )
        val root = FrameLayout(this)
        status = TextView(this)
        status.setTextColor(Color.WHITE)
        status.textSize = 15f
        status.gravity = Gravity.CENTER
        val pad = (16 * resources.displayMetrics.density).toInt()
        status.setPadding(pad * 2, pad, pad * 2, pad)
        val bg = GradientDrawable()
        bg.setColor(Color.parseColor("#E6181B20"))
        bg.cornerRadius = 28f * resources.displayMetrics.density
        status.background = bg
        val lp = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT,
            Gravity.CENTER
        )
        root.addView(status, lp)
        setContentView(root)
        enqueue(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        enqueue(intent)
    }

    private fun enqueue(i: Intent) {
        queue.add(i)
        if (!busy) next()
    }

    private fun next() {
        if (queue.isEmpty()) {
            finish()
            return
        }
        busy = true
        process(queue.removeAt(0))
    }

    private fun finishOne() {
        busy = false
        next()
    }

    private fun process(i: Intent) {
        if (i.getBooleanExtra("test", false)) {
            status.text = "✓ Background launch works"
            Store.markRun(this, "Test OK — background launch works")
            handler.postDelayed({ finishOne() }, 2500L)
            return
        }
        val id = i.getStringExtra("id")
        var profile: Profile? = null
        for (p in Store.loadProfiles(this)) if (p.id == id) profile = p
        if (profile == null) {
            finishOne()
            return
        }
        val p: Profile = profile
        val slots = Auto.slotsFor(p, System.currentTimeMillis())
        if (slots.isEmpty()) {
            Store.markRun(this, "${p.name}: nothing due (not an active day)")
            finishOne()
            return
        }
        status.text = "Setting ${slots.size} alarms…"
        AlarmRunner(
            this,
            slots,
            { done, all -> status.text = "Chain Alarm · $done / $all" },
            { ok, all, _ ->
                val msg = if (ok == all) "✓ $ok alarms created" else "created $ok of $all"
                Store.markRun(this, "${p.name}: $msg")
                status.text = "${p.name}: $msg"
                handler.postDelayed({ finishOne() }, 1200L)
            }
        ).start()
    }
}
