package dev.wellbeing.phone

import android.app.Application
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dev.wellbeing.phone.data.SettingsStore
import dev.wellbeing.phone.record.RunController
import dev.wellbeing.phone.ui.AppRoot

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        RunController.init(this)
        dev.wellbeing.phone.watch.WatchLink.init(this)
    }
}

class MainActivity : ComponentActivity() {
    private var runActive = false
    private var keepOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = SettingsStore(applicationContext)
        setContent {
            AppRoot(store) { active, keepScreenOn -> applyRunWindow(active, keepScreenOn) }
        }
    }

    /**
     * During a run the live screen is shown over the phone's lock screen, so a press on the power
     * button is enough to read it, and the screen stays on if the runner asked for it.
     */
    private fun applyRunWindow(active: Boolean, keepScreenOn: Boolean) {
        if (active == runActive && keepScreenOn == keepOn) return
        runActive = active; keepOn = keepScreenOn
        setShowWhenLocked(active)
        if (active && keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}
