package app.mediabattery

import android.app.Application

/** Holds the app graph. */
class MediaBatteryApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
