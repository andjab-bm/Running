package dev.wellbeing.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.wellbeing.phone.record.RunController

/** Debug builds only: stands in for the watch by feeding one heart-rate value to the session. */
class DebugHeartRateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bpm = intent.getIntExtra("bpm", 0)
        if (bpm in 30..230) RunController.onHeartRate(System.currentTimeMillis(), bpm)
    }
}
