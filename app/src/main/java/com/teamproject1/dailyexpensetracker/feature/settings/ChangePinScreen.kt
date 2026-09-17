package com.teamproject1.dailyexpensetracker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton

@HiltViewModel
class ChangePinViewModel @Inject constructor(
    private val pinManager: PinManager
) : ViewModel() {
    fun verifyCurrentPin(pin: String): Boolean = pinManager.verifyPin(pin)
    fun setNewPin(pin: String) = pinManager.setPin(pin)
}

/**
 * Closes a real gap — only "set a PIN" during first-time onboarding
 * existed before; there was no way to change it afterward. Requires the
 * current PIN, then a new PIN entered twice to guard against typos, before
 * calling the same setPin() used at onboarding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePinScreen(onBack: () -> Unit, viewModel: ChangePinViewModel = hiltViewModel()) {
    var currentPin by remember { mutableStateOf("") }
    var newPin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var successMessage by remember { mutableStateOf<String?>(null) }

    fun submit() {
        errorMessage = null
        successMessage = null
        when {
            !viewModel.verifyCurrentPin(currentPin) -> errorMessage = "Current PIN is incorrect."
            newPin.length != 4 -> errorMessage = "New PIN must be 4 digits."
            newPin != confirmPin -> errorMessage = "New PIN entries don't match."
            newPin == currentPin -> errorMessage = "New PIN must be different from your current one."
            else -> {
                viewModel.setNewPin(newPin)
                successMessage = "PIN changed successfully."
                currentPin = ""; newPin = ""; confirmPin = ""
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Change PIN") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = currentPin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) currentPin = it },
                label = { Text("Current PIN") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = newPin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) newPin = it },
                label = { Text("New PIN") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = confirmPin,
                onValueChange = { if (it.length <= 4 && it.all { c -> c.isDigit() }) confirmPin = it },
                label = { Text("Confirm New PIN") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )

            errorMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            successMessage?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = { submit() },
                enabled = currentPin.length == 4 && newPin.length == 4 && confirmPin.length == 4,
                modifier = Modifier.fillMaxWidth()
            ) { Text("Change PIN") }
        }
    }
}
