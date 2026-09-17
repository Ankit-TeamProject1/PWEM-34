package com.teamproject1.dailyexpensetracker.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * Lets the NavHost react when the session gets locked mid-use (grace period
 * expired while backgrounded) — this is what actually surfaces the Auth
 * screen to the user, rather than the lock state just sitting unused in
 * SessionManager with nothing watching it.
 */
@HiltViewModel
class SessionLockViewModel @Inject constructor(
    session: SessionManager
) : ViewModel() {
    val isUnlocked = session.isUnlocked
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
}
