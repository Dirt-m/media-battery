package app.mediabattery.warn

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.mediabattery.R
import app.mediabattery.permissions.Permissions
import site.dirt23.battery.core.identity.AppCatalog
import site.dirt23.battery.core.model.Snapshot
import kotlin.math.ceil
import kotlin.math.max

/**
 * The time left, shown for a moment when a tracked app opens. The extension's arrival pill,
 * drawn the one way another app's screen can carry it: a small untouchable overlay window
 * in the bottom right, gone again in a few seconds. Green dot while comfortable, amber when
 * low.
 *
 * It rides the same "display over other apps" grant that lets the service raise the cover,
 * so it costs no new permission; without the grant it never appears.
 *
 * One pill per arrival. Entering an app makes it the candidate, and the first snapshot that
 * names it answers the arrival: draining means a toast, covered means the cover already
 * tells the story. Either way the entry is consumed, so a pass bought or a window closing
 * later does not toast an app the user has been in all along. An app no snapshot will ever
 * name (off, untracked) never toasts.
 */
class ArrivalPill(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val fade = Runnable { fadeOut() }

    // Main thread only, which the handler guarantees.
    private var candidate: String? = null
    private var consumed = true
    private var view: View? = null

    /** Who is in front, straight off the detectors. May arrive on any thread. */
    fun onForeground(pkg: String?) {
        val id = pkg?.let { AppCatalog.idFor(it) }
        handler.post {
            if (id == candidate) return@post
            candidate = id
            consumed = id == null
        }
    }

    /** One snapshot, on the warner's terms: [covered] while the block screen is up. */
    fun onSnapshot(snapshot: Snapshot, covered: Boolean, inUse: Boolean) {
        handler.post {
            // The cover replaces the pill and tells the arrival story itself.
            if (covered) hideNow()
            val id = candidate ?: return@post
            if (consumed) return@post
            if (covered) {
                consumed = true
                return@post
            }
            if (snapshot.drainingId != id) return@post
            consumed = true
            if (!inUse || !snapshot.showTimeLeft) return@post
            val minutes = max(1.0, ceil(max(0.0, snapshot.charge) / 60.0)).toInt()
            val cap = if (snapshot.effCapacity > 0) snapshot.effCapacity else snapshot.capacity
            val low = cap > 0 && snapshot.charge / cap <= LOW_FRACTION
            show(minutes, low)
        }
    }

    /** The service is going away; its window goes with it. */
    fun clear() {
        handler.post { hideNow() }
    }

    private fun show(minutes: Int, low: Boolean) {
        if (!Permissions.canDrawOverlays(context)) return
        hideNow()
        val wm = context.getSystemService(WindowManager::class.java) ?: return
        val density = context.resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()

        val pill = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(BG)
                cornerRadius = dp(12).toFloat()
                setStroke(max(1, dp(1)), LINE)
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            alpha = 0f
        }
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (low) AMBER else GREEN)
            }
        }
        pill.addView(dot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(9) })
        pill.addView(
            TextView(context).apply {
                text = context.getString(R.string.pill_time_left, minutes)
                setTextColor(INK)
                textSize = 13f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
        )

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            x = dp(16)
            y = dp(16)
        }
        if (runCatching { wm.addView(pill, params) }.isFailure) return
        view = pill
        pill.animate().alpha(1f).setDuration(FADE_MS).start()
        handler.postDelayed(fade, SHOW_MS)
    }

    private fun fadeOut() {
        val v = view ?: return
        v.animate()
            .alpha(0f)
            .setDuration(FADE_MS)
            .withEndAction { if (view === v) hideNow() }
            .start()
    }

    private fun hideNow() {
        handler.removeCallbacks(fade)
        val v = view ?: return
        view = null
        runCatching { context.getSystemService(WindowManager::class.java)?.removeView(v) }
    }

    private companion object {
        /** The extension's timings: quarter second fade, up for two and a half. */
        const val FADE_MS = 250L
        const val SHOW_MS = 2_500L

        /** The gauge's low line: amber at a fifth of the cap that holds right now. */
        const val LOW_FRACTION = 0.2

        // Same bytes as the extension's showPill and SbColors.
        const val BG = 0xFF1B2027.toInt()
        const val LINE = 0xFF2B323B.toInt()
        const val INK = 0xFFE8EAED.toInt()
        const val GREEN = 0xFF36C06F.toInt()
        const val AMBER = 0xFFF0A93B.toInt()
    }
}
