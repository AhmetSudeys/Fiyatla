package com.ahmetsudeys.rotauygulama.ui.util

import android.os.SystemClock
import android.view.View
import com.ahmetsudeys.rotauygulama.R

/**
 * Prevents accidental multi-taps from triggering the click handler multiple times.
 * Default interval is tuned for navigation / dialog actions.
 */
fun View.setOnSingleClickListener(intervalMs: Long = 600L, onClick: (View) -> Unit) {
    setOnClickListener { v ->
        val now = SystemClock.elapsedRealtime()
        val last = (v.getTag(R.id.tag_last_click_ms) as? Long) ?: 0L
        if (now - last < intervalMs) return@setOnClickListener
        v.setTag(R.id.tag_last_click_ms, now)
        onClick(v)
    }
}



