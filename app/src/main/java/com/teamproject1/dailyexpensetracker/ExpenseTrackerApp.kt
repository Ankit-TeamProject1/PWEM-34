package com.teamproject1.dailyexpensetracker

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.work.Configuration
import com.teamproject1.dailyexpensetracker.core.session.SessionManager
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class ExpenseTrackerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var sessionManager: SessionManager

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()

        // This is the actual auto-lock enforcement point that was missing —
        // SessionManager tracked activity timestamps, but nothing called
        // back into it when the app backgrounded/foregrounded, so the
        // session stayed "unlocked" indefinitely as long as the process
        // wasn't fully killed by Android. ProcessLifecycleOwner observes
        // the WHOLE APP's foreground/background state (not a single
        // Activity's), which is what we want — backgrounding via home
        // button, app switcher, or another app coming forward all count.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                sessionManager.markBackgrounded()
            }
            override fun onStart(owner: LifecycleOwner) {
                // Grace period is now a real Settings dropdown value, not
                // the old hardcoded constant — read the current stored
                // value each time rather than capturing it once.
                owner.lifecycleScope.launch {
                    sessionManager.evaluateForegroundLock(sessionManager.getAutoLockGraceMillis())
                }
            }
        })
    }
}
