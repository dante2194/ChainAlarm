package app.chainalarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager

class AutoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Auto.ACTION_RUN -> {
                Auto.rescheduleAll(context) // arm tomorrow's run first
                Store.markTriggered(context)
                val run = Intent(context, RunActivity::class.java)
                run.putExtra("id", intent.getStringExtra("id"))
                launchRun(context, run)
            }
            Auto.ACTION_TEST -> {
                Store.markTriggered(context)
                val run = Intent(context, RunActivity::class.java)
                run.putExtra("test", true)
                launchRun(context, run)
            }
            else -> Auto.rescheduleAll(context) // boot, app update, clock / timezone change
        }
    }

    /**
     * Opens RunActivity from the background. With "Display over other apps" granted, Android
     * allows it; a 1px invisible overlay is shown first because newer Android versions require
     * a visible overlay window for that exemption.
     */
    private fun launchRun(ctx: Context, run: Intent) {
        run.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        var mgrOut: WindowManager? = null
        var viewOut: View? = null
        try {
            if (Settings.canDrawOverlays(ctx)) {
                val mgr = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                val view = View(ctx)
                val lp = WindowManager.LayoutParams(
                    1, 1,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT
                )
                mgr.addView(view, lp)
                mgrOut = mgr
                viewOut = view
            }
        } catch (e: Exception) {
            mgrOut = null
            viewOut = null
        }
        try {
            ctx.startActivity(run)
        } catch (e: Exception) {
            // blocked: MainActivity will show a warning because lastTrigger > lastRunAt
        }
        val m = mgrOut
        val v = viewOut
        if (m != null && v != null) {
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    m.removeView(v)
                } catch (e: Exception) {
                }
            }, 10_000L)
        }
    }
}
