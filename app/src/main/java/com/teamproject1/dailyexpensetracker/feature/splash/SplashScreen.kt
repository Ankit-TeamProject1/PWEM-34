package com.teamproject1.dailyexpensetracker.feature.splash

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.database.dao.BookDao
import com.teamproject1.dailyexpensetracker.core.security.PinManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class SplashDestination {
    object PinSetup : SplashDestination()
    object CreateFirstBook : SplashDestination()
    object Auth : SplashDestination()
    object Loading : SplashDestination()
}

@HiltViewModel
class SplashViewModel @Inject constructor(
    private val pinManager: PinManager,
    private val bookDao: BookDao
) : ViewModel() {

    var destination: SplashDestination = SplashDestination.Loading
        private set

    fun determineDestination(onReady: (SplashDestination) -> Unit) {
        viewModelScope.launch {
            val result = when {
                !pinManager.isPinSet() -> SplashDestination.PinSetup
                bookDao.getActiveBookCount().first() == 0 -> SplashDestination.CreateFirstBook
                else -> SplashDestination.Auth
            }
            destination = result
            onReady(result)
        }
    }
}

/**
 * Theme is resolved by the caller's MaterialTheme wrapper before this even
 * composes, so there's no light->dark flash. This screen's only job is to
 * silently route: PIN not set -> PinSetup, PIN set but zero books -> CreateFirstBook,
 * otherwise -> Auth (normal unlock flow for a returning user).
 */
@Composable
fun SplashScreen(
    onNavigate: (SplashDestination) -> Unit,
    viewModel: SplashViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) {
        viewModel.determineDestination { destination -> onNavigate(destination) }
    }

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "Daily Expense Tracker",
            style = MaterialTheme.typography.headlineMedium
        )
    }
}
