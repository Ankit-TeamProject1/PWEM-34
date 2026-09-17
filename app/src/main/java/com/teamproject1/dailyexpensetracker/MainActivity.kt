package com.teamproject1.dailyexpensetracker

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity
import com.teamproject1.dailyexpensetracker.core.theme.ThemeMode
import com.teamproject1.dailyexpensetracker.core.theme.ThemePreferenceManager
import com.teamproject1.dailyexpensetracker.ui.theme.DailyExpenseTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * WindowWidthSizeClass drives the adaptive scope we locked:
 *   Compact  -> phones, folded Fold, Ultra in portrait: bottom sheets,
 *               single-column grid, full-screen forms (as originally spec'd).
 *   Medium+  -> unfolded Fold (~673dp): side panels instead of bottom sheets,
 *               list-detail for Analytics/Recurring/Settings, wider tile grid.
 * This same breakpoint logic extends to true tablets in v1.1 with no rework.
 *
 * Extends FragmentActivity (not plain ComponentActivity) because
 * BiometricPrompt requires a FragmentActivity host — FragmentActivity is
 * itself a ComponentActivity subclass, so calculateWindowSizeClass still works.
 */
@AndroidEntryPoint
class MainActivity : FragmentActivity() {

    @Inject lateinit var themePreferenceManager: ThemePreferenceManager

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val windowSizeClass = calculateWindowSizeClass(this)
            val isCompact = windowSizeClass.widthSizeClass == WindowWidthSizeClass.Compact

            // Real Light/Dark/Automatic control — previously this always
            // followed the system setting with no way to override it, even
            // though Settings implied a choice existed.
            val themeMode by themePreferenceManager.themeMode.collectAsState(initial = ThemeMode.AUTOMATIC)
            val systemIsDark = isSystemInDarkTheme()
            val darkTheme = when (themeMode) {
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
                ThemeMode.AUTOMATIC -> systemIsDark
            }

            // Status bar icon color must track the actual rendered theme,
            // not be hardcoded — dark icons only read correctly against a
            // light background; dark mode needs light icons instead.
            LaunchedEffect(darkTheme) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                }
            }

            DailyExpenseTrackerTheme(darkTheme = darkTheme) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // NavHost root goes here — Splash -> Auth -> Book Selector -> Dashboard.
                    // isCompact is threaded down through composition local to every
                    // screen so it can choose bottom-sheet vs side-panel, single vs
                    // two-pane, without each screen re-deriving it independently.
                    ExpenseTrackerNavHost(isCompact = isCompact)
                }
            }
        }
    }
}
