package com.cwbridge.android

import android.content.Context

/**
 * One-time post-install notice.
 *
 * Some capabilities depend on the Android version or on Shizuku, and we would
 * rather say so once than leave a button that silently fails. The flag is
 * written the first time the notice is shown and never reset, so this is shown
 * exactly once after install.
 */
object FirstRun {

    private const val PREFS = "cwbridge_first_run"
    private const val KEY_NOTICE_SHOWN = "compatibility_notice_shown"

    /** @return true the first time only; marks the notice as shown. */
    fun consumeCompatibilityNotice(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTICE_SHOWN, false)) return false
        prefs.edit().putBoolean(KEY_NOTICE_SHOWN, true).apply()
        return true
    }
}