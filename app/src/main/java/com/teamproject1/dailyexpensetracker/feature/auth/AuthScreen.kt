package com.teamproject1.dailyexpensetracker.feature.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val session: SessionManager
) : ViewModel() {

    fun isBiometricEnabled(): Boolean = pinManager.isBiometricEnabled()

    fun verifyPin(pin: String): Boolean = pinManager.verifyPin(pin)

    /** Unlocking never changes which book was active — auth and book
     *  selection are deliberately independent, per the locked design. */
    fun onUnlockSuccess() {
        session.unlock()
    }
}

/**
 * Shown over whatever screen/modal was open when the grace period expired —
 * on success, the caller returns to that exact screen rather than resetting
 * navigation, per the locked "auth is a gate, not a navigation reset" rule.
 * Biometric auto-triggers on entry if enrolled; falls back to PIN on
 * failure/cancel, per the locked design.
 */
@Composable
fun AuthScreen(
    onUnlocked: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel()
) {
    var pinInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val shakeOffset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val activity = context as? FragmentActivity

    fun triggerBiometric() {
        val act = activity ?: return
        BiometricAuthHelper.showPrompt(
            activity = act,
            onSuccess = {
                viewModel.onUnlockSuccess()
                onUnlocked()
            },
            onFailureOrCancel = { /* fall back silently to PIN pad, already showing */ }
        )
    }

    LaunchedEffect(Unit) {
        if (viewModel.isBiometricEnabled()) {
            triggerBiometric()
        }
    }

    fun submitPin() {
        if (viewModel.verifyPin(pinInput)) {
            viewModel.onUnlockSuccess()
            onUnlocked()
        } else {
            errorMessage = "Incorrect PIN"
            pinInput = ""
            scope.launch {
                shakeOffset.animateTo(20f, tween(50))
                shakeOffset.animateTo(-20f, tween(50))
                shakeOffset.animateTo(0f, tween(50))
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enter your PIN", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))

        // Visual PIN dots
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.offset(x = shakeOffset.value.dp)
        ) {
            repeat(4) { index ->
                val filled = index < pinInput.length
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .background(
                            if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            CircleShape
                        )
                )
            }
        }

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(32.dp))

        NumericKeypad(
            onDigit = { digit ->
                if (pinInput.length < 4) {
                    pinInput += digit
                    errorMessage = null
                    if (pinInput.length == 4) submitPin()
                }
            },
            onBackspace = { if (pinInput.isNotEmpty()) pinInput = pinInput.dropLast(1) }
        )

        if (viewModel.isBiometricEnabled()) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = { triggerBiometric() }) {
                Text("Use Biometric Instead")
            }
        }
    }
}

@Composable
private fun NumericKeypad(onDigit: (String) -> Unit, onBackspace: () -> Unit) {
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("", "0", "⌫")
    )
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    if (key.isEmpty()) {
                        Spacer(Modifier.size(64.dp))
                    } else {
                        FilledTonalButton(
                            onClick = { if (key == "⌫") onBackspace() else onDigit(key) },
                            modifier = Modifier.size(64.dp),
                            shape = CircleShape
                        ) {
                            Text(key, style = MaterialTheme.typography.titleLarge)
                        }
                    }
                }
            }
        }
    }
}
