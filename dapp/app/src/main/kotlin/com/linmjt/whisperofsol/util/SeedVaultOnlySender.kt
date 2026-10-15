package com.linmjt.whisperofsol.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.app.ActivityOptionsCompat
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import java.lang.reflect.Field

/**
 * SeedVaultOnlySender — v3.0.21
 *
 * Wraps an [ActivityResultSender] so every MWA intent it dispatches is
 * restricted to Seed Vault (`com.solanamobile.wallet`) via [Intent.setPackage].
 * This prevents Android's system chooser from appearing when the user has
 * other MWA-compat wallets installed (Phantom, Solflare, Backpack, Mock MWA,
 * Jupiter, etc.).
 *
 * ## Why reflection
 * MWA 2.2.0's [ActivityResultSender] is a Kotlin `final class`. The library
 * exposes no public API to customize the intent it dispatches — the standard
 * contract is "any installed MWA wallet can answer". We bypass the contract
 * by replacing the internal `ActivityResultLauncher<Intent>` field with a
 * [WrappedLauncher] that calls `setPackage` before delegating.
 *
 * ## Failure semantics (v3.0.21, per user spec)
 * Both failure modes **throw** — no silent fallback to system chooser:
 *   - [SeedVaultNotInstalledException] — device has no Seed Vault (non-Seeker
 *     Android, or someone removed it via root). UI should show a clear error.
 *   - [ReflectionFailedException] — MWA library internals changed in a way
 *     that breaks our reflection (e.g. private field renamed). UI should
 *     show a clear error and we should file a bug.
 *
 * If `wrap()` succeeds, all subsequent MWA operations through this sender
 * (connect / signIn / signAndSendTransactions) bypass the chooser.
 */
object SeedVaultOnlySender {
    private const val TAG = "SeedVaultOnlySender"
    private const val SEED_VAULT_PACKAGE = "com.solanamobile.wallet"
    private const val LAUNCHER_FIELD = "activityResultLauncher"
    private const val ROOT_ACTIVITY_FIELD = "rootActivity"

    /**
     * Restrict the given sender to Seed Vault. Throws on precondition failure
     * (see class doc).
     *
     * @param sender the [ActivityResultSender] the MWA library will use
     * @throws SeedVaultNotInstalledException if Seed Vault is not installed
     * @throws ReflectionFailedException if reflection cannot wrap the launcher
     */
    @JvmStatic
    fun wrap(sender: ActivityResultSender) {
        // 1) Pre-check: Seed Vault must be installed.
        val context = extractContext(sender)
        if (!isSeedVaultInstalled(context)) {
            throw SeedVaultNotInstalledException()
        }

        // 2) Reflection-wrap: replace internal launcher with one that calls
        //    setPackage("com.solanamobile.wallet") on every intent.
        try {
            val launcherField: Field =
                ActivityResultSender::class.java.getDeclaredField(LAUNCHER_FIELD)
            launcherField.isAccessible = true
            val existing = launcherField.get(sender)
            if (existing is WrappedLauncher) {
                // Idempotent: already wrapped (e.g. retry on same sender).
                return
            }
            val originalLauncher = existing as ActivityResultLauncher<Intent>
            launcherField.set(sender, WrappedLauncher(originalLauncher))
        } catch (e: NoSuchFieldException) {
            throw ReflectionFailedException(
                "MWA library field '$LAUNCHER_FIELD' not found — " +
                "library version may have changed. Required: MWA clientlib 2.2.0.",
                e
            )
        } catch (e: IllegalAccessException) {
            throw ReflectionFailedException(
                "MWA library field '$LAUNCHER_FIELD' is not accessible. " +
                "Module may need open modifier or --add-opens.",
                e
            )
        } catch (e: Exception) {
            throw ReflectionFailedException(
                "Unexpected error wrapping ActivityResultSender: ${e.message}",
                e
            )
        }
    }

    private fun isSeedVaultInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(SEED_VAULT_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            Log.w(TAG, "isSeedVaultInstalled check failed: ${e.message}")
            false
        }
    }

    private fun extractContext(sender: ActivityResultSender): Context {
        // ActivityResultSender stores rootActivity as a private final field.
        // We don't expose it through any public API; reflection is the only way
        // to get the ComponentActivity that was passed to the constructor.
        val field: Field = ActivityResultSender::class.java.getDeclaredField(ROOT_ACTIVITY_FIELD)
        field.isAccessible = true
        return field.get(sender) as ComponentActivity
    }

    /** Thrown when the device has no Seed Vault installed. */
    class SeedVaultNotInstalledException : Exception(
        "此 app 只支援 Seeker 內建 Seed Vault。請在 Seeker 設備上使用,或檢查 Seed Vault 是否被卸載。"
    )

    /** Thrown when the MWA library's internals changed in a way that breaks reflection. */
    class ReflectionFailedException(message: String, cause: Throwable? = null) :
        Exception(message, cause)

    /**
     * Wraps the original [ActivityResultLauncher] so that any Intent launched
     * through it has `setPackage("com.solanamobile.wallet")` applied first.
     * This restricts the system to dispatching the intent to Seed Vault only.
     */
    private class WrappedLauncher(
        private val delegate: ActivityResultLauncher<Intent>,
    ) : ActivityResultLauncher<Intent>() {

        // open method — override to ensure setPackage even when caller uses
        // the no-options overload. Parent's default calls launch(input, null),
        // which would dispatch to our other override — but overriding both
        // is unambiguous and avoids relying on that chain.
        override fun launch(input: Intent) {
            input.setPackage(SEED_VAULT_PACKAGE)
            delegate.launch(input)
        }

        // abstract method — must override. options is nullable per parent
        // signature `launch(input: I, options: ActivityOptionsCompat?)`.
        override fun launch(input: Intent, options: ActivityOptionsCompat?) {
            input.setPackage(SEED_VAULT_PACKAGE)
            if (options != null) {
                delegate.launch(input, options)
            } else {
                delegate.launch(input)
            }
        }

        override fun unregister() {
            delegate.unregister()
        }

        // contract is declared as `abstract val` in parent, so we override
        // it as a property, not a function.
        override val contract: ActivityResultContract<Intent, *>
            get() = delegate.contract
    }
}
