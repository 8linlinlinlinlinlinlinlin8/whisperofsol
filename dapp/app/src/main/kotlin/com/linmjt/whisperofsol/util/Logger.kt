package com.linmjt.whisperofsol.util

import android.content.Context

/**
 * Logger — simple file logger.
 *
 * Debug build (FILE_LOG_FULL=true): logs all levels to file
 * Release build (FILE_LOG_FULL=false): logs only errors
 */
object Logger {
    fun init(context: Context) {
        // V1 stub - real impl in production
    }

    fun d(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
    }

    fun i(tag: String, msg: String) {
        android.util.Log.i(tag, msg)
    }

    fun w(tag: String, msg: String) {
        android.util.Log.w(tag, msg)
    }

    fun e(tag: String, msg: String, t: Throwable? = null) {
        if (t != null) android.util.Log.e(tag, msg, t)
        else android.util.Log.e(tag, msg)
    }
}