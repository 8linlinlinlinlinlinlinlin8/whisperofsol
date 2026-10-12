package com.linmjt.whisperofsol.crypto

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.subtle.X25519
import com.linmjt.whisperofsol.util.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.SecureRandom

/**
 * v1.6.0/v1.6.1: Hybrid X25519 keypair store.
 *
 * Primary path: derive from wallet signature on a deterministic message —
 * same wallet always yields the same X25519 keypair, so cross-device recovery
 * works (Device 2 re-signs the same message, gets the same XS).
 *
 * Fallback: if wallet refuses to sign (user cancel, SMS Wallet ANR, etc.),
 * fall back to SecureRandom-generated XS stored locally. The user can retry
 * later to migrate to the deterministic version.
 *
 * Both paths use the same EncryptedSharedPreferences storage layout
 * (key = "xs_v1", value = base64(32-byte XS)) — fallback writes still produce
 * a valid keypair, just not deterministic across devices.
 *
 * v1.6.1: also caches the wallet signature (key = "sig_v1") so we can
 * re-register enc_pub on chain via `register_whisper_id`. Stored as
 * 64-byte signature base64. Cleared together with XS.
 *
 * v1.8.15: ANR fix. ALL prefs + crypto work is now wrapped in
 * `withContext(Dispatchers.IO)` and exposed as `suspend` functions.
 *
 * Background: EncryptedSharedPreferences.create() blocks the calling thread
 * for 1-3 seconds on first launch (master key unwrap + file decrypt). Calling
 * `prefs.getString()` then triggers the lazy load. Previously, our
 * `MainActivity.LaunchedEffect { viewModel.ensureWhisperKey() }` ran on Main
 * at app start, hit `whisperKeyStore.getCached()` -> `prefs.getString()` ->
 * ANR on slow Seeker / Android 16 phones.
 *
 * Fix:
 *  - [preload] must be called from `Application.onCreate()` BEFORE any
 *    [WhisperKeyStore] instance is constructed. We use `runBlocking` there
 *    so the EncryptedSharedPreferences master key + file load finish before
 *    any UI shows. By the time MainActivity runs, [prefsOrNull] is set, and
 *    every WhisperKeyStore method can be called from any dispatcher.
 *  - All public read/write/derive functions are `suspend` + dispatch to IO,
 *    so any future caller on Main is safe by construction.
 */
class WhisperKeyStore(@Suppress("UNUSED_PARAMETER") context: Context) {

    // v1.8.15: Read from the preloaded singleton. If preload wasn't called
    // (e.g. someone instantiates WhisperKeyStore from a unit test without
    // Application.onCreate), we fall back to a synchronous load here so the
    // class still works -- but the FIRST such call blocks the calling
    // thread. Production always goes through preload().
    private val prefs: SharedPreferences = prefsOrNull ?: run {
        // Synchronous fallback (only triggered if preload was skipped).
        // Logs a warning so we notice if it ever happens.
        Logger.w(TAG, "prefs not preloaded -- synchronous fallback (will block caller)")
        val ctx = context.applicationContext
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val p = EncryptedSharedPreferences.create(
            ctx,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefsOrNull = p
        p
    }

    /**
     * Load the cached X25519 keypair, or derive one from a wallet signature.
     *
     * v1.8.15: now suspend + IO-dispatch. Caller no longer needs to wrap.
     * Includes the [X25519.publicFromPrivate] EC scalar multiplication,
     * which can take 50-200 ms on slow phones -- MUST NOT run on Main.
     *
     * When the cache is empty, returns null quickly (the caller is expected
     * to provide a wallet signature via [deriveAndCache]).
     */
    suspend fun getCached(): WhisperKey? = withContext(Dispatchers.IO) {
        val cached = prefs.getString(KEY_XS, null) ?: return@withContext null
        val sigCached = prefs.getString(KEY_SIG, null)
        try {
            val xs = Base64.decode(cached, Base64.NO_WRAP)
            if (xs.size != 32) {
                Logger.w(TAG, "cached xs invalid size=${xs.size}, regenerating")
                null
            } else {
                val xp = X25519.publicFromPrivate(xs)
                val sig = sigCached?.let {
                    try {
                        val decoded = Base64.decode(it, Base64.NO_WRAP)
                        if (decoded.size == 64) decoded else null
                    } catch (_: Throwable) { null }
                }
                Logger.d(TAG, "loaded cached xs (xp=${xp.toBase64Short()}) sigCached=${sig != null}")
                WhisperKey(xs = xs, xp = xp, signature = sig)
            }
        } catch (e: Throwable) {
            Logger.e(TAG, "failed to load cached xs", e)
            null
        }
    }

    /**
     * Derive a deterministic X25519 keypair from a wallet signature, then
     * cache it (plus the signature itself, for on-chain register_whisper_id)
     * in EncryptedSharedPreferences. Same wallet + same signature always
     * produces the same X25519 keypair.
     *
     * v1.8.15: suspend + IO-dispatch. SHA-512 + [X25519.publicFromPrivate] +
     * prefs write are all off Main.
     *
     * @param signature 64-byte Ed25519 signature on the canonical
     *   `WhisperOfSol encryption key v1 | <wallet_pubkey>` message.
     * @return derived keypair, or null if signature length is wrong.
     */
    suspend fun deriveAndCache(signature: ByteArray): WhisperKey? = withContext(Dispatchers.IO) {
        if (signature.size != 64) {
            Logger.e(TAG, "deriveAndCache: signature must be 64 bytes, got ${signature.size}")
            return@withContext null
        }
        try {
            // v1.4.x-style derivation: SHA-512(signature)[:32], then clamp
            val hash = java.security.MessageDigest.getInstance("SHA-512")
                .digest(signature)
            val xs = ByteArray(32)
            System.arraycopy(hash, 0, xs, 0, 32)
            // RFC 7748 §5 clamping (X25519):
            xs[0] = (xs[0].toInt() and 0xf8).toByte()
            xs[31] = (xs[31].toInt() and 0x7f).toByte()
            xs[31] = (xs[31].toInt() or 0x40).toByte()
            val xp = X25519.publicFromPrivate(xs)

            prefs.edit()
                .putString(KEY_XS, Base64.encodeToString(xs, Base64.NO_WRAP))
                .putString(KEY_SIG, Base64.encodeToString(signature, Base64.NO_WRAP))
                .apply()
            Logger.d(TAG, "derived xs from wallet signature (xp=${xp.toBase64Short()})")
            WhisperKey(xs = xs, xp = xp, signature = signature)
        } catch (e: Throwable) {
            Logger.e(TAG, "deriveAndCache failed", e)
            null
        }
    }

    /**
     * Fallback path: generate a fresh random X25519 keypair with SecureRandom
     * and persist it. Used when wallet signature is unavailable. Not
     * deterministic -- Device 2 will get a different keypair, breaking
     * cross-device recovery for the fallback path.
     *
     * v1.8.15: suspend + IO-dispatch.
     */
    suspend fun loadOrGenerate(): WhisperKey = withContext(Dispatchers.IO) {
        val cached = prefs.getString(KEY_XS, null)
        val sigCached = prefs.getString(KEY_SIG, null)
        if (cached != null) {
            try {
                val xs = Base64.decode(cached, Base64.NO_WRAP)
                if (xs.size == 32) {
                    val xp = X25519.publicFromPrivate(xs)
                    val sig = sigCached?.let {
                        try {
                            val d = Base64.decode(it, Base64.NO_WRAP)
                            if (d.size == 64) d else null
                        } catch (_: Throwable) { null }
                    }
                    Logger.d(TAG, "loaded cached xs (xp=${xp.toBase64Short()}) sigCached=${sig != null}")
                    return@withContext WhisperKey(xs = xs, xp = xp, signature = sig)
                }
                Logger.w(TAG, "cached xs invalid size, regenerating")
                generateAndStore()
            } catch (e: Throwable) {
                Logger.e(TAG, "failed to load cached xs, regenerating", e)
                generateAndStore()
            }
        } else {
            generateAndStore()
        }
    }

    private suspend fun generateAndStore(): WhisperKey = withContext(Dispatchers.IO) {
        val xs = ByteArray(32)
        SecureRandom().nextBytes(xs)
        val xp = X25519.publicFromPrivate(xs)

        try {
            prefs.edit()
                .putString(KEY_XS, Base64.encodeToString(xs, Base64.NO_WRAP))
                .remove(KEY_SIG)  // no signature for SecureRandom-generated keys
                .apply()
            Logger.d(TAG, "generated new xs (xp=${xp.toBase64Short()})")
        } catch (e: Throwable) {
            Logger.e(TAG, "failed to cache new xs", e)
        }
        WhisperKey(xs = xs, xp = xp)
    }

    /**
     * Clear the cached X25519 secret (and any cached signature). Used when
     * the user disconnects their wallet or wants to reset.
     *
     * v1.8.15: suspend + IO-dispatch.
     */
    suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_XS)
            .remove(KEY_SIG)
            .apply()
        Logger.d(TAG, "cleared cached xs + sig")
    }

    /**
     * The canonical signing message used to derive X25519. Bound to wallet
     * pubkey + domain + version. Must never change once shipped -- changing
     * it would invalidate every existing Whisper ID.
     *
     * Cheap pure-string operation, kept sync. Safe on Main.
     */
    fun signingMessage(walletPubkey: String): ByteArray =
        ("WhisperOfSol encryption key v1 | $walletPubkey").toByteArray(Charsets.UTF_8)

    /**
     * v1.8.5: Was the cached WhisperKey successfully registered on-chain
     * (via register_whisper_id PDA init_if_needed)? Returns false if no
     * cached key OR if the cached key was never registered on-chain (e.g.,
     * v1.8.3 SecureRandom fallback path).
     *
     * v1.8.15: suspend + IO-dispatch.
     */
    suspend fun isRegisteredOnChain(): Boolean = withContext(Dispatchers.IO) {
        prefs.getBoolean(KEY_REGISTERED_ON_CHAIN, false)
    }

    /**
     * v1.8.5: Mark the cached WhisperKey as registered on-chain. Called by
     * HomeViewModel when registerWhisperIdOnChain tx confirms.
     *
     * v1.8.15: suspend + IO-dispatch. Single boolean write is fast, but the
     * Tink encryption still happens on the caller thread -- keep it off Main.
     */
    suspend fun setRegisteredOnChain(registered: Boolean) = withContext(Dispatchers.IO) {
        prefs.edit().putBoolean(KEY_REGISTERED_ON_CHAIN, registered).apply()
        Logger.d(TAG, "setRegisteredOnChain($registered)")
    }

    /**
     * v1.8.5: clear all persisted setup state (used on Disconnect).
     *
     * v1.8.15: suspend + IO-dispatch.
     */
    suspend fun clearRegisteredOnChain() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_REGISTERED_ON_CHAIN).apply()
    }

    companion object {
        private const val TAG = "WhisperKeyStore"
        private const val PREFS_NAME = "whisper_key_store"
        private const val KEY_XS = "xs_v1"
        /** v1.6.1: wallet signature used for XS derivation. Needed for
         *  `register_whisper_id` on-chain proof-of-possession. */
        private const val KEY_SIG = "sig_v1"
        // v1.8.5: persistence flag for "this XS has been registered on-chain
        // via register_whisper_id". If false, the cached XS is unusable for
        // cross-device recovery (no PDA exists) and the dApp should force
        // re-setup instead of trusting the local key.
        private const val KEY_REGISTERED_ON_CHAIN = "ron_chain_v1"

        private fun ByteArray.toBase64Short(): String =
            Base64.encodeToString(this, Base64.NO_WRAP).take(12) + "..."

        /**
         * v1.8.15: shared, pre-loaded EncryptedSharedPreferences. Set by
         * [preload] at app start, read by every [WhisperKeyStore] instance.
         *
         * @Volatile because [preload] runs on a background thread in
         * `Application.onCreate()`, and [WhisperKeyStore] instances may be
         * constructed from any thread later.
         */
        @Volatile
        private var prefsOrNull: SharedPreferences? = null

        /**
         * v1.8.15: Pre-load EncryptedSharedPreferences synchronously at app
         * start. MUST be called from `Application.onCreate()` BEFORE any
         * [WhisperKeyStore] instance is constructed.
         *
         * Why synchronous: [EncryptedSharedPreferences.create] does master
         * key unwrap + file decryption on the calling thread. On slow
         * Seekers, that's 1-3 seconds -- enough to ANR the app if done on
         * Main during user interaction. Doing it here at app start blocks
         * the splash screen briefly but the user sees no UI yet, so no
         * ANR.
         *
         * Subsequent [WhisperKeyStore] instances just read the cached
         * [prefsOrNull] (no I/O, no crypto). Their methods are still suspend
         * + IO-dispatch because per-call encryption (decrypt value,
         * encrypt on write) still costs ~10-50 ms.
         */
        suspend fun preload(context: Context) = withContext(Dispatchers.IO) {
            if (prefsOrNull != null) return@withContext
            val ctx = context.applicationContext
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            prefsOrNull = EncryptedSharedPreferences.create(
                ctx,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
            Logger.d(TAG, "preload: EncryptedSharedPreferences ready")
        }
    }
}

/**
 * X25519 keypair for WhisperOfSol E2E encryption.
 *
 * @property xs 32-byte X25519 private scalar (encrypted at rest)
 * @property xp 32-byte X25519 public key (shared as Whisper ID)
 * @property signature 64-byte Ed25519 signature used to derive [xs] (if
 *   deterministic-derive path). Null if SecureRandom fallback was used.
 */
data class WhisperKey(
    val xs: ByteArray,
    val xp: ByteArray,
    val signature: ByteArray? = null,
) {
    /** Whisper ID -- base64 of xp, safe to share with senders. */
    fun whisperIdBase64(): String =
        Base64.encodeToString(xp, Base64.NO_WRAP)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WhisperKey) return false
        return xs.contentEquals(other.xs) && xp.contentEquals(other.xp)
    }

    override fun hashCode(): Int = xs.contentHashCode() * 31 + xp.contentHashCode()
}
