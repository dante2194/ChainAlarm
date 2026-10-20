package app.chainalarm

import android.content.Context
import android.content.Intent
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

data class Profile(
    val id: String = UUID.randomUUID().toString(),
    var name: String = "Profile",
    val chains: MutableList<Chain> = mutableListOf()
) {
    fun totalAlarms(): Int {
        var n = 0
        for (c in chains) n += c.count
        return n
    }
}

/** pinDay = 0 for a one-time alarm, otherwise a Calendar.DAY_OF_WEEK value. */
data class Slot(val hour: Int, val minute: Int, val label: String, val pinDay: Int)

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
            out.add(Slot(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE), label, pin))
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

object Store {
    private const val PREFS = "chainalarm"

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
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("draft", null)
        if (raw == null) return Chain()
        return try {
            chainFromJson(JSONObject(raw))
        } catch (e: Exception) {
            Chain()
        }
    }

    fun saveDraft(ctx: Context, c: Chain) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("draft", chainToJson(c).toString()).apply()
    }

    fun loadProfiles(ctx: Context): MutableList<Profile> {
        val out = ArrayList<Profile>()
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("profiles", null)
        if (raw == null) return out
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val p = Profile(o.getString("id"), o.getString("name"))
                val ca = o.getJSONArray("chains")
                for (j in 0 until ca.length()) p.chains.add(chainFromJson(ca.getJSONObject(j)))
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
            arr.put(JSONObject().put("id", p.id).put("name", p.name).put("chains", ca))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString("profiles", arr.toString()).apply()
    }
}
