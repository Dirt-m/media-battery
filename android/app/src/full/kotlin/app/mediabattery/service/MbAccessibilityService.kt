package app.mediabattery.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import app.mediabattery.data.AppPrefs
import app.mediabattery.detect.AccessibilityForegroundDetector

/**
 * Two jobs. It hands every window change to the detector, which decides what counts, and on
 * connect it starts [BatteryService] if it is not already running: an accessibility service
 * is bound early and kept bound, so it is the part of the app still alive after Android
 * stops the foreground service.
 *
 * Everything it can do is in this file. No window content, no text, nothing stored.
 */
class MbAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityForegroundDetector.live()?.onConnected(this)
        // Same gate the boot receiver uses: no foreground notification before setup.
        if (AppPrefs(this).onboarded) BatteryService.start(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        AccessibilityForegroundDetector.live()?.onWindow(
            event.packageName?.toString(),
            event.className?.toString(),
        )
    }

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityForegroundDetector.live()?.onDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityForegroundDetector.live()?.onDisconnected()
        super.onDestroy()
    }
}
