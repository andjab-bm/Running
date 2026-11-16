package dev.wellbeing.wear

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.ambient.AmbientLifecycleObserver
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var ambient by mutableStateOf(false)
    private var permitted by mutableStateOf(false)

    /** Heart-rate permission: body sensors up to Wear OS 5, the granular one from Wear OS 6. */
    private val sensorPermission =
        if (Build.VERSION.SDK_INT >= 36) "android.permission.health.READ_HEART_RATE" else Manifest.permission.BODY_SENSORS

    private val askPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permitted = hasSensorPermission()
    }

    private val ambientCallback = object : AmbientLifecycleObserver.AmbientLifecycleCallback {
        override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
            ambient = true
            // The phone slows its updates while the screen is dimmed.
            WatchSession.hello(interactive = false)
        }

        override fun onExitAmbient() {
            ambient = false
            WatchSession.hello(interactive = true)
        }
    }

    private fun hasSensorPermission() =
        ContextCompat.checkSelfPermission(this, sensorPermission) == PackageManager.PERMISSION_GRANTED

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WatchSession.init(this)
        permitted = hasSensorPermission()
        lifecycle.addObserver(AmbientLifecycleObserver(this, ambientCallback))

        // While the app is on screen it says hello every few seconds: this is how the phone
        // learns the watch has joined, and how the watch notices the phone is out of reach.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (!ambient) WatchSession.hello(interactive = true)
                    delay(HELLO_MS)
                }
            }
        }
        // Heart rate starts as soon as a session exists on the phone, while the app is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                WatchSession.state.collect {
                    if (permitted && WatchSession.sessionActive && !WatchSession.measuring.value) {
                        startForegroundService(Intent(this@MainActivity, HrService::class.java))
                    }
                }
            }
        }
        // A short vibration confirms a command the phone applied; a double one says it did not.
        lifecycleScope.launch {
            WatchSession.feedback.collect { applied ->
                getSystemService(Vibrator::class.java)?.vibrate(
                    if (applied) VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE)
                    else VibrationEffect.createWaveform(longArrayOf(0, 80, 80, 80), -1),
                )
            }
        }

        setContent {
            WatchApp(
                ambient = ambient,
                permitted = permitted,
                onGrant = { askPermission.launch(arrayOf(sensorPermission, Manifest.permission.POST_NOTIFICATIONS)) },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        permitted = hasSensorPermission()
    }

    override fun onStop() {
        super.onStop()
        // Closed without a run being measured: the watch has left, and the phone shows it at once.
        if (!WatchSession.measuring.value) WatchSession.bye()
    }

    companion object {
        private const val HELLO_MS = 3_000L
    }
}
