package com.linmjt.whisperofsol.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import com.linmjt.whisperofsol.R
import com.linmjt.whisperofsol.ui.viewmodel.UiError

/**
 * uiErrorMessage — locale-aware error message for [UiError].
 *
 * v3.0.0: rewritten as a @Composable function so it can call
 * [stringResource]. The previous version in HomeViewModel.kt returned
 * hardcoded English strings, which broke when the user switched to
 * Chinese / Japanese in Settings.
 *
 * Call sites (both Composable):
 *   - WalletScreen.kt:169  →  `text = uiErrorMessage(uiState.error!!)`
 *   - SendWhisperScreen.kt:380  →  `text = uiErrorMessage(uiState.error!!)`
 *
 * @return localized error message string for the current app locale
 */
@Composable
@ReadOnlyComposable
fun uiErrorMessage(error: UiError): String = when (error) {
    UiError.ConnectFailed              -> stringResource(R.string.error_connect_failed)
    UiError.InvalidRecipientAddress   -> stringResource(R.string.error_invalid_recipient)
    UiError.InvalidWhisperId          -> stringResource(R.string.error_invalid_whisper_id)
    UiError.SendFailed                -> stringResource(R.string.error_send_failed)
    UiError.WalletNotConnected        -> stringResource(R.string.error_wallet_not_connected)
    UiError.TransactionBuildFailed    -> stringResource(R.string.error_transaction_build_failed)
    UiError.InvalidNonce              -> stringResource(R.string.error_invalid_nonce)
    UiError.NonceSetupFailed          -> stringResource(R.string.error_nonce_setup_failed)
    UiError.RecipientNotSetup         -> stringResource(R.string.error_recipient_not_setup)
    UiError.RecipientEncPubCorrupted  -> stringResource(R.string.error_recipient_enc_pub_corrupted)
    UiError.RecipientInboxFull        -> stringResource(R.string.error_recipient_inbox_full)
}
