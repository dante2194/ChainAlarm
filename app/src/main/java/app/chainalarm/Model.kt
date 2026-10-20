package app.chainalarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

const val MAX_COUNT = 700
const val MAX_INTERVAL_SLIDER = 60
const val MAX_INTERVAL_TYPED = 720

// Alarm creation speed: BATCH intents fired back-to-back, then a pause.
// Raise BATCH for speed, lower it (or raise the delay) if DeskClock drops alarms.
const val BATCH = 5
const val BATCH_DELAY_MS = 100L

/** position: 0 = start at, 1 = end in, 2 = both (anchor in the middle) */
data class Chain(
    var hour: Int = 7,
    var minute: Int = 0,
    var position: Int = 0,
    var count: Int = 3,
    var interval: Int = 5,
    var label: String = ""
)

/**
 * days        : ring days, bit0 = Mon ... bit6 = Sun
 * onWeeks/off : cycle, e.g. 1 on + 1 off = every other week (offWeeks 0 = every week)
 * weekStartDow: Calendar.SATURDAY / SUNDAY / MONDAY, where a "week" begins
 * anchorWeek  : start (millis) of the first "on" week
 * autoOn      : create the alarms by themselves every day at sendHour:sendMinute
 */
data class Profile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Profile",
    val chains: MutableList<Chain> = mutableListOf(),
    var days: Int = 127,
    var onWeeks: Int = 1,
    var offWeeks: Int = 0,
    var weekStartDow: Int = Calendar.SATURDAY,
    var anchorWeek: Long = 0L,
    var autoOn: Boolean = false,
    var sendHour: Int = 23,
    var sendMinute: Int = 0
) {
    fun totalAlarms(): Int {
        var n = 0
        for (c in chains) n += c.count
        return n
    }
}

/** pinDay = 0 for a one-time alarm, otherwise a Calendar.DAY_OF_WEEK value. */
data class Slot(val hour: Int, val minute: Int, val label: String, val pinDay: Int, val atMillis: Long)

object Planner {
    private const val MIN = 60_000L
    private const val DAY = 86_400_000L

    fun nextOccurrence(hour: Int, minute: Int, now: Long): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = now
        c.set(Calendar.HOUR_OF_DAY, hour)
        c.set(Calendar.MINUTE, minute)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= now) c.add(Calendar.DAY_OF_MONTH, 1)
        return c.timeInMillis
    }

    private fun offsetSteps(ch: Chain): Int = when (ch.position) {
        0 -> 0
        1 -> -(ch.count - 1)
        else -> -((ch.count - 1) / 2)
    }

    /** Moment of the first alarm: always in the future, at most 24 h away. */
    fun firstAlarm(ch: Chain, now: Long): Long {
        val anchor = nextOccurrence(ch.hour, ch.minute, now)
        var first = anchor + offsetSteps(ch).toLong() * ch.interval * MIN
        while (first <= now) first += DAY
        return first
    }

    fun slots(ch: Chain, label: String, now: Long): List<Slot> {
        val first = firstAlarm(ch, now)
        val out = ArrayList<Slot>(ch.count)
        val c = Calendar.getInstance()
        for (i in 0 until ch.count) {
            val t = first + i.toLong() * ch.interval * MIN
            c.timeInMillis = t
            val pin = if (t - now >= DAY) c.get(Calendar.DAY_OF_WEEK) else 0
            out.add(Slot(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), label, pin, t))
        }
        return out
    }

    /** How many alarms land more than 24 h ahead (DeskClock can only repeat those weekly). */
    fun pinnedCount(ch: Chain, now: Long): Int {
        val first = firstAlarm(ch, now)
        var n = 0
        for (i in 0 until ch.count) {
            if (first + i.toLong() * ch.interval * MIN - now >= DAY) n++
        }
        return n
    }

    fun fireAlarm(ctx: Context, s: Slot, pkg: String?): Boolean {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, s.hour)
        i.putExtra(AlarmClock.EXTRA_MINUTES, s.minute)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, s.label)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (s.pinDay != 0) {
            val days = ArrayList<Int>()
            days.add(s.pinDay)
            i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, days)
        }
        if (pkg != null) i.setPackage(pkg)
        return launch(ctx, i)
    }

    fun fireTimer(ctx: Context, seconds: Int, label: String, pkg: String?): Boolean {
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, seconds)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, label)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        if (pkg != null) i.setPackage(pkg)
        return launch(ctx, i)
    }

    private fun launch(ctx: Context, i: Intent): Boolean {
        return try {
            ctx.startActivity(i)
            true
        } catch (e: Exception) {
            false
        }
    }
}

/** Which calendar days / weeks a profile is active on. */
object Schedule {
    private const val WEEK = 7L * 86_400_000L

    fun weekStart(t: Long, startDow: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.set(Calendar.HOUR_OF_DAY, 0)
        c.set(Calendar.MINUTE, 0)
        c.set(Calendar.SECOND, 0)
        c.set(Calendar.MILLISECOND, 0)
        val delta = (c.get(Calendar.DAY_OF_WEEK) - startDow + 7) % 7
        c.add(Calendar.DAY_OF_MONTH, -delta)
        return c.timeInMillis
    }

    fun addWeeks(t: Long, n: Int): Long {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        c.add(Calendar.DAY_OF_MONTH, 7 * n)
        return c.timeInMillis
    }

    /** Is an alarm ringing at moment t allowed by this profile's days + week cycle? */
    fun isActive(p: Profile, t: Long): Boolean {
        val c = Calendar.getInstance()
        c.timeInMillis = t
        val bit = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Mon=0 ... Sun=6
        if (((p.days shr bit) and 1) == 0) return false
        val cycle = p.onWeeks + p.offWeeks
        if (p.offWeeks <= 0 || cycle <= 0) return true
        val anchor = weekStart(if (p.anchorWeek == 0L) t else p.anchorWeek, p.weekStartDow)
        val diff = weekStart(t, p.weekStartDow) - anchor
        val idx = Math.round(diff.toDouble() / WEEK.toDouble()).toInt()
        val pos = ((idx % cycle) + cycle) % cycle
        return pos < p.onWeeks
    }
}

/** Fires batches of SET_ALARM intents; used by the main screen and by the background runner. */
class AlarmRunner(
    private val ctx: Context,
    private val slots: List<Slot>,
    private val onProgress: (Int, Int) -> Unit,
    private val onDone: (Int, Int, Boolean) -> Unit
) {
    private val handler = Handler(Looper.getMainLooper())
    private var pkg: String? = "com.android.deskclock"
    private var ok = 0
    private var progress = 0
    var cancelled = false

    private val step = object : Runnable {
        override fun run() {
            val total = slots.size
            if (cancelled || progress >= total) {
                onDone(ok, total, cancelled)
                return
            }
            var n = 0
            while (n < BATCH && progress < total) {
                if (fire(slots[progress])) ok++
                progress++
                n++
            }
            onProgress(progress, total)
            handler.postDelayed(this, BATCH_DELAY_MS)
        }
    }

    fun start() {
        handler.post(step)
    }

    private fun fire(s: Slot): Boolean {
        if (Planner.fireAlarm(ctx, s, pkg)) return true
        if (pkg != null) {
            pkg = null // DeskClock not reachable: let Android pick any clock app
            return Planner.fireAlarm(ctx, s, null)
        }
        return false
    }
}

/** Daily automatic runs, driven by AlarmManager. */
object Auto {
    const val ACTION_RUN = "app.chainalarm.AUTO_RUN"
    const val ACTION_TEST = "app.chainalarm.AUTO_TEST"
    private const val FLAGS = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    private fun am(ctx: Context): AlarmManager =
        ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    private fun pending(ctx: Context, id: String): PendingIntent {
        val i = Intent(ctx, AutoReceiver::class.java)
        i.action = ACTION_RUN
        i.putExtra("id", id)
        return PendingIntent.getBroadcast(ctx, id.hashCode(), i, FLAGS)
    }

    private fun setAt(ctx: Context, at: Long, pi: PendingIntent) {
        val a = am(ctx)
        try {
            if (Build.VERSION.SDK_INT < 31 || a.canScheduleExactAlarms()) {
                a.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (e: Exception) {
            a.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        for (p in Store.loadProfiles(ctx)) {
            if (p.autoOn) {
                setAt(ctx, Planner.nextOccurrence(p.sendHour, p.sendMinute, now), pending(ctx, p.id))
            } else {
                am(ctx).cancel(pending(ctx, p.id))
            }
        }
    }

    fun cancel(ctx: Context, id: String) {
        am(ctx).cancel(pending(ctx, id))
    }

    fun scheduleTest(ctx: Context) {
        val i = Intent(ctx, AutoReceiver::class.java)
        i.action = ACTION_TEST
        val pi = PendingIntent.getBroadcast(ctx, 7777, i, FLAGS)
        setAt(ctx, System.currentTimeMillis() + 20_000L, pi)
    }

    /** Alarms ringing in the next 24 h that pass the profile's day/week rules. */
    fun slotsFor(p: Profile, now: Long): List<Slot> {
        val out = ArrayList<Slot>()
        for (ch in p.chains) {
            for (s in Planner.slots(ch, ch.label.ifBlank { p.name }, now)) {
                if (s.pinDay == 0 && Schedule.isActive(p, s.atMillis)) out.add(s)
            }
        }
        return out
    }
}

object Store {
    private const val PREFS = "chainalarm"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun chainToJson(c: Chain): JSONObject = JSONObject()
        .put("hour", c.hour)
        .put("minute", c.minute)
        .put("position", c.position)
        .put("count", c.count)
        .put("interval", c.interval)
        .put("label", c.label)

    private fun chainFromJson(o: JSONObject): Chain = Chain(
        o.optInt("hour", 7).coerceIn(0, 23),
        o.optInt("minute", 0).coerceIn(0, 59),
        o.optInt("position", 0).coerceIn(0, 2),
        o.optInt("count", 3).coerceIn(1, MAX_COUNT),
        o.optInt("interval", 5).coerceIn(1, MAX_INTERVAL_TYPED),
        o.optString("label", "")
    )

    fun loadDraft(ctx: Context): Chain {
        val raw = prefs(ctx).getString("draft", null)
        if (raw == null) return Chain()
        return try {
            chainFromJson(JSONObject(raw))
        } catch (e: Exception) {
            Chain()
        }
    }

    fun saveDraft(ctx: Context, c: Chain) {
        prefs(ctx).edit().putString("draft", chainToJson(c).toString()).apply()
    }

    fun loadProfiles(ctx: Context): MutableList<Profile> {
        val out = ArrayList<Profile>()
        val raw = prefs(ctx).getString("profiles", null)
        if (raw == null) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Profile(o.getString("id"), o.getString("name"))
                val ca = o.getJSONArray("chains")
                for (j in 0 until ca.length()) p.chains.add(chainFromJson(ca.getJSONObject(j)))
                p.days = o.optInt("days", 127)
                p.onWeeks = o.optInt("onWeeks", 1).coerceIn(1, 8)
                p.offWeeks = o.optInt("offWeeks", 0).coerceIn(0, 8)
                p.weekStartDow = o.optInt("weekStart", Calendar.SATURDAY)
                p.anchorWeek = o.optLong("anchor", 0L)
                p.autoOn = o.optBoolean("autoOn", false)
                p.sendHour = o.optInt("sendHour", 23).coerceIn(0, 23)
                p.sendMinute = o.optInt("sendMinute", 0).coerceIn(0, 59)
                out.add(p)
            }
        } catch (e: Exception) {
            // ignore corrupted data
        }
        return out
    }

    fun saveProfiles(ctx: Context, list: List<Profile>) {
        val arr = JSONArray()
        for (p in list) {
            val ca = JSONArray()
            for (c in p.chains) ca.put(chainToJson(c))
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.name)
                    .put("chains", ca)
                    .put("days", p.days)
                    .put("onWeeks", p.onWeeks)
                    .put("offWeeks", p.offWeeks)
                    .put("weekStart", p.weekStartDow)
                    .put("anchor", p.anchorWeek)
                    .put("autoOn", p.autoOn)
                    .put("sendHour", p.sendHour)
                    .put("sendMinute", p.sendMinute)
            )
        }
        prefs(ctx).edit().putString("profiles", arr.toString()).apply()
    }

    fun markTriggered(ctx: Context) {
        prefs(ctx).edit().putLong("lastTrigger", System.currentTimeMillis()).apply()
    }

    fun markRun(ctx: Context, text: String) {
        prefs(ctx).edit()
            .putLong("lastRunAt", System.currentTimeMillis())
            .putString("lastRunText", text)
            .apply()
    }

    fun lastTrigger(ctx: Context): Long = prefs(ctx).getLong("lastTrigger", 0L)
    fun lastRunAt(ctx: Context): Long = prefs(ctx).getLong("lastRunAt", 0L)
    fun lastRunText(ctx: Context): String = prefs(ctx).getString("lastRunText", "") ?: ""
}
