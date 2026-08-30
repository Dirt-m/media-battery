package app.mediabattery.detect

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.inputmethod.InputMethodManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Who came to the front, pushed rather than polled.
 *
 * One event type, `TYPE_WINDOW_STATE_CHANGED`, delivered in milliseconds. That is the whole
 * subscription: no window content, no text, no clicks, and the config says so in the form
 * the system shows the user before they turn it on.
 *
 * The filter is set at runtime and holds two kinds of package. The tracked apps, and the
 * launcher plus the system ui. The second half is what lets the fast path report a leave: a
 * service filtered to a package list is never told about a window belonging to an app that
 * is not on it, so without them the user could walk out of a tracked app and this detector
 * would still say they are in it until the poll caught up.
 *
 * An event is accepted only when its class resolves to a real activity. Dialogs, toasts,
 * the keyboard, and the notification shade all arrive as window changes and none of them is
 * the user going somewhere. The answer for a given window is stable, so the last yes and
 * the last no are remembered and most events cost no lookup.
 */
class AccessibilityForegroundDetector private constructor(private val context: Context) :
    FastForegroundDetector {

    private val state = MutableStateFlow(false)
    override val connected: StateFlow<Boolean> = state.asStateFlow()

    @Volatile
    private var listener: ForegroundDetector.Listener? = null

    @Volatile
    private var service: AccessibilityService? = null

    @Volatile
    private var watched: Set<String> = emptySet()

    @Volatile
    private var ignored: Set<String> = emptySet()

    // Touched only from the event thread.
    private var lastActivity: String? = null
    private var lastOther: String? = null

    init {
        ignored = ignoredPackages()
    }

    override fun start(listener: ForegroundDetector.Listener) {
        this.listener = listener
    }

    override fun stop() {
        listener = null
    }

    override fun setTracked(packages: Set<String>) {
        watched = packages
        ignored = ignoredPackages()
        pushFilter()
    }

    /** Android bound the service; it is the live one until it says otherwise. */
    fun onConnected(service: AccessibilityService) {
        this.service = service
        pushFilter()
        state.value = true
    }

    fun onDisconnected() {
        service = null
        state.value = false
    }

    /**
     * A window came up. The event carries an uptime stamp and the engine counts wall clock;
     * the two are one window change apart at most, so report now.
     */
    fun onWindow(packageName: String?, className: String?) {
        val out = listener ?: return
        if (packageName == null || className == null) return
        if (packageName in ignored) return
        if (!isActivity(packageName, className)) return
        out.onForeground(packageName, System.currentTimeMillis())
    }

    private fun pushFilter() {
        val live = service ?: return
        val info = live.serviceInfo ?: return
        val names = LinkedHashSet<String>()
        names.addAll(watched)
        names.addAll(homePackages())
        names.add(SYSTEM_UI)
        info.packageNames = names.toTypedArray()
        runCatching { live.serviceInfo = info }
    }

    private fun isActivity(packageName: String, className: String): Boolean {
        val key = "$packageName/$className"
        if (key == lastActivity) return true
        if (key == lastOther) return false
        val resolved = runCatching {
            context.packageManager.getActivityInfo(ComponentName(packageName, className), 0)
        }.isSuccess
        if (resolved) lastActivity = key else lastOther = key
        return resolved
    }

    /** Our own windows, and every keyboard the user has enabled. */
    private fun ignoredPackages(): Set<String> {
        val out = LinkedHashSet<String>()
        out.add(context.packageName)
        runCatching {
            context.getSystemService(InputMethodManager::class.java)
                ?.enabledInputMethodList
                ?.forEach { out.add(it.packageName) }
        }
        return out
    }

    private fun homePackages(): Set<String> {
        val pm = context.packageManager
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return runCatching {
            pm.queryIntentActivities(home, 0).mapTo(LinkedHashSet()) { it.activityInfo.packageName }
        }.getOrDefault(emptySet())
    }

    companion object {
        private const val SYSTEM_UI = "com.android.systemui"

        @Volatile
        private var instance: AccessibilityForegroundDetector? = null

        /** Built once by the graph. The service finds it here when Android binds it. */
        fun install(context: Context): AccessibilityForegroundDetector =
            instance ?: synchronized(this) {
                instance ?: AccessibilityForegroundDetector(context.applicationContext)
                    .also { instance = it }
            }

        fun live(): AccessibilityForegroundDetector? = instance
    }
}
