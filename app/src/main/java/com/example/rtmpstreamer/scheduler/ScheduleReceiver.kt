package com.example.rtmpstreamer.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.example.rtmpstreamer.MainActivity

class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_AUTO_START, true)
        }
        context.startActivity(launchIntent)
    }
}
