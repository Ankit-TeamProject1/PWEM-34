package com.teamproject1.dailyexpensetracker.feature.onboarding

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {

    fun savePin(pin: String) {
        pinManager.setPin(pin)
    }

    fun setBiometricEnabled(enabled: Boolean) {
        pinManager.setBiometricEnabled(enabled)
    }
}

/**
 * Step 2 of onboarding — MANDATORY, no skip. Enter PIN -> confirm PIN ->
 * optional (skippable) biometric enrollment prompt, then proceeds to
 * CreateFirstBook per the locked onboarding sequence.
 */
@Composable
fun PinSetupScreen(
    onComplete: () -> Unit,
    viewModel: PinSetupViewModel = hiltViewModel()
) {
    var stage by remember { mutableStateOf(PinSetupStage.ENTER) }
    var firstPin by remember { mutableStateOf("") }
    var currentInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    if (stage == PinSetupStage.BIOMETRIC_PROMPT) {
        BiometricEnrollPrompt(
            onEnable = {
                viewModel.setBiometricEnabled(true)
                onComplete()
            },
            onSkip = {
                viewModel.setBiometricEnabled(false)
                onComplete()
            }
        )
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (stage == PinSetupStage.ENTER) "Set up your PIN" else "Confirm your PIN",
            style = MaterialTheme.typography.headlineMedium
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "You'll use this to unlock the app",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = currentInput,
            onValueChange = { if (it.length <= 4) currentInput = it },
            label = { Text("4-digit PIN") },
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            isError = errorMessage != null
        )

        errorMessage?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = {
                if (currentInput.length != 4) {
                    errorMessage = "PIN must be 4 digits"
                    return@Button
                }
                when (stage) {
                    PinSetupStage.ENTER -> {
                        firstPin = currentInput
                        currentInput = ""
                        errorMessage = null
                        stage = PinSetupStage.CONFIRM
                    }
                    PinSetupStage.CONFIRM -> {
                        if (currentInput == firstPin) {
                            viewModel.savePin(currentInput)
                            stage = PinSetupStage.BIOMETRIC_PROMPT
                        } else {
                            errorMessage = "PINs don't match — try again"
                            currentInput = ""
                            firstPin = ""
                            stage = PinSetupStage.ENTER
                        }
                    }
                    else -> Unit
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (stage == PinSetupStage.ENTER) "Continue" else "Confirm PIN")
        }
    }
}

private enum class PinSetupStage { ENTER, CONFIRM, BIOMETRIC_PROMPT }

@Composable
private fun BiometricEnrollPrompt(onEnable: () -> Unit, onSkip: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Enable biometric unlock?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Use your fingerprint or face instead of typing your PIN every time. You can change this later in Settings.",
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(Modifier.height(24.dp))
        Button(onClick = onEnable, modifier = Modifier.fillMaxWidth()) {
            Text("Enable Biometric Unlock")
        }
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) {
            Text("Skip for now")
        }
    }
}
