package org.nextcolor.dashcamviewer

import android.app.Application
import org.nextcolor.dashcamviewer.android.CameraNetworkController
import org.nextcolor.dashcamviewer.android.PrivatePreferences

class DashcamApplication : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, CameraNetworkController(this), PrivatePreferences(this))
    }
}
