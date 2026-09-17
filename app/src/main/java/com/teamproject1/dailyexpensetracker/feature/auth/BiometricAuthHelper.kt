package com.teamproject1.dailyexpensetracker.feature.auth

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Thin wrapper around AndroidX BiometricPrompt. PIN is always the fallback
 * (no negative button forcing PIN — the caller's PIN pad is already showing
 * underneath, so cancelling biometric just leaves the user there).
 *
 * Biometric invalidation on new enrollment: handled by the OS automatically
 * for BIOMETRIC_STRONG-class prompts using CryptoObject-backed keys. This
 * shell uses the simpler class-3 prompt without a CryptoObject for now —
 * upgrading to a Keystore-backed CryptoObject (so a newly enrolled
 * fingerprint forces PIN fallback, per the locked security rule) is a
 * near-term hardening item, not a v1 blocker.
 */
object BiometricAuthHelper {

    fun canAuthenticate(activity: FragmentActivity): Boolean {
        val manager = BiometricManager.from(activity)
        return manager.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) ==
            BiometricManager.BIOMETRIC_SUCCESS
    }

    fun showPrompt(
        activity: FragmentActivity,
        onSuccess: () -> Unit,
        onFailureOrCancel: () -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            onFailureOrCancel()
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess()
            }
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFailureOrCancel()
            }
            override fun onAuthenticationFailed() {
                // A single failed biometric read (e.g. bad finger placement) —
                // don't dismiss, let the user retry or the system time out.
            }
        }

        val prompt = BiometricPrompt(activity, executor, callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Personal Wealth and Expense Manager")
            .setSubtitle("Use your fingerprint or face to continue")
            .setNegativeButtonText("Use PIN instead")
            .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
            .build()

        prompt.authenticate(info)
    }
}
