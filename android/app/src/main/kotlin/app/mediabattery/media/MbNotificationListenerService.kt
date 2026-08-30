package app.mediabattery.media

import android.service.notification.NotificationListenerService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The key to the media sessions, and nothing else.
 *
 * Android hands `MediaSessionManager.getActiveSessions` only to a notification listener, so
 * the app has to be one to know that a tracked app is playing sound. That is the only
 * reason this class exists. It is never passed a notification: the callbacks that would
 * carry one are not overridden, and adding one would be a visible change to a file that is
 * otherwise just a connected flag.
 *
 * The flag is what the monitor waits on. Access can be granted long after the app started,
 * and the moment Android binds this service is the moment the sessions become readable.
 */
class MbNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        state.value = true
    }

    override fun onListenerDisconnected() {
        state.value = false
    }

    companion object {
        private val state = MutableStateFlow(false)

        /** True while Android has this listener bound, which means the sessions are readable. */
        val connected: StateFlow<Boolean> = state.asStateFlow()
    }
}
