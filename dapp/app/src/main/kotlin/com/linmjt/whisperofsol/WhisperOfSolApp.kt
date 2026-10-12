package com.linmjt.whisperofsol

import android.app.Application
import com.linmjt.whisperofsol.crypto.WhisperKeyStore
import com.linmjt.whisperofsol.util.Logger
import kotlinx.coroutines.runBlocking

/**
 * v1.8.15: Custom Application class.
 *
 * Sole purpose right now: preload [WhisperKeyStore] at app start so the
 * EncryptedSharedPreferences master key unwrap + file decryption happens
 * BEFORE any UI runs. Without this, the first call to
 * `whisperKeyStore.getCached()` from `MainActivity.LaunchedEffect` would
 * block Main for 1-3 seconds on slow Seekers -- ANR territory.
 *
 * We use `runBlocking` deliberately here. Application.onCreate runs before
 * any Activity / Window / View; there is no UI to jank. Blocking the splash
 * screen for ~1 second on cold start is far less visible than ANRing
 * after the user taps the "Set up Whisper ID" button.
 *
 * If [WhisperKeyStore.preload] ever grows beyond EncryptedSharedPreferences
 * init (e.g. network setup, file copy), this should switch to a real
 * background coroutine + readiness flag.
 */
class WhisperOfSolApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.d("WhisperOfSolApp", "onCreate: preloading WhisperKeyStore prefs (blocking, ~1s on cold start)")
        runBlocking { WhisperKeyStore.preload(this@WhisperOfSolApp) }
        Logger.d("WhisperOfSolApp", "onCreate: preload complete")
    }
}
