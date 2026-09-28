package com.cwbridge.android

import android.content.Context
import com.cwbridge.android.data.UserFileStore

/**
 * One-time post-install notice. Flag lives in settings.json on user storage.
 */
object FirstRun {

    private const val KEY_NOTICE_SHOWN = "compatibility_notice_shown"

    /** @return true the first time only; marks the notice as shown. */
    fun consumeCompatibilityNotice(context: Context): Boolean {
        if (UserFileStore.getBool(context, KEY_NOTICE_SHOWN, false)) return false
        UserFileStore.putBool(context, KEY_NOTICE_SHOWN, true)
        return true
    }
}
