package com.linmjt.whisperofsol.util

import android.util.Log
import com.linmjt.whisperofsol.BuildConfig

/**
 * WsolLog — wrapper for the ad-hoc `WSOL_DEBUG` diagnostic traces that
 * were added while debugging the v3.0.13 Login flow.
 *
 * Behavior:
 *   - DEBUG build: forwards to android.util.Log.e (full message)
 *   - RELEASE build: no-op (entire call elided by BuildConfig.DEBUG)
 *
 * This keeps release builds free of PDA structure / signature / key-derivation
 * traces that we don't want in user-facing logcat. To re-enable in release for
 * ad-hoc diagnosis, set the build flag or temporarily flip BuildConfig.DEBUG.
 *
 * Note: this intentionally does NOT replace the production [Logger] — that one
 * writes real app events. WsolLog is purely for dev/QA traces.
 */
object WsolLog {
    private const val DEFAULT_TAG = "WSOL_DEBUG"

    fun e(msg: String, t: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg)
        }
    }

    fun w(msg: String, t: Throwable? = null, tag: String = DEFAULT_TAG) {
        if (BuildConfig.DEBUG) {
            if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg)
        }
    }
}
