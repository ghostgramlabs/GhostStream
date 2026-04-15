package com.ghoststream.core.media

import android.util.Log
import com.ghostgramlabs.directserve.core.media.BuildConfig

object CompatLogger {
    private const val TAG_PREFIX = "GhostStream/"

    fun debug(group: String, message: String, throwable: Throwable? = null) {
        if (BuildConfig.DEBUG) {
            if (throwable != null) {
                Log.d(TAG_PREFIX + group, message, throwable)
            } else {
                Log.d(TAG_PREFIX + group, message)
            }
        }
    }

    fun info(group: String, message: String) {
        Log.i(TAG_PREFIX + group, message)
    }

    fun warn(group: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.w(TAG_PREFIX + group, message, throwable)
        } else {
            Log.w(TAG_PREFIX + group, message)
        }
    }

    fun error(group: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Log.e(TAG_PREFIX + group, message, throwable)
        } else {
            Log.e(TAG_PREFIX + group, message)
        }
    }
}
