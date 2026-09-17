package com.teamproject1.dailyexpensetracker.core.session

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Singleton

private val Context.sessionDataStore by preferencesDataStore(name = "session_prefs")
private val LAST_USED_BOOK_ID = longPreferencesKey("last_used_book_id")
private val AUTO_LOCK_GRACE_MINUTES = androidx.datastore.preferences.core.intPreferencesKey("auto_lock_grace_minutes")

/** Default auto-lock grace period — 5 minutes. Now a real, user-adjustable
 *  Settings dropdown (1/5/15/30 min) per the explicit request — this was
 *  previously hardcoded with no UI to change it at all. */
const val DEFAULT_GRACE_PERIOD_MILLIS = 5 * 60 * 1000L

/**
 * Single source of truth for "which book is active" and "is the app unlocked."
 *
 * Every feature ViewModel collects `activeBookId` via flatMapLatest — never
 * caches it locally. This is what makes book-switching structurally safe:
 * switching books cancels any in-flight query for the old book automatically
 * (flatMapLatest semantics) and starts a fresh one for the new book, so no
 * screen can ever show mixed-book data.
 *
 * Auth state (`isUnlocked`) and `activeBookId` are deliberately independent —
 * locking the app never changes which book was active; only an explicit
 * book-switcher tap does.
 */
class SessionManager(
    private val context: Context
) {
    private val _activeBookId = MutableStateFlow<Long?>(null)
    val activeBookId: StateFlow<Long?> = _activeBookId.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    var lastActivityAt: Long = 0L
        private set

    /** Timestamp of the last time the app went to background — null while
     *  foregrounded. Used to measure elapsed time on the NEXT foreground,
     *  which is what actually determines whether to re-lock. This is the
     *  piece that was missing: SessionManager previously tracked
     *  `lastActivityAt` but nothing ever called back into it on app
     *  resume, so the grace period was never actually enforced during a
     *  live process — only a full process kill (which resets isUnlocked
     *  to false naturally on next launch) appeared to "lock" the app. */
    private var backgroundedAt: Long? = null

    fun setActiveBook(bookId: Long) {
        _activeBookId.value = bookId
    }

    /** Called when a book is archived/deleted while active, or on first launch
     *  with zero books — routes UI back to the Book Selector's empty state
     *  via the same null-activeBookId path in both cases. */
    fun clearActiveBook() {
        _activeBookId.value = null
    }

    fun unlock() {
        _isUnlocked.value = true
        recordActivity()
    }

    fun lock() {
        _isUnlocked.value = false
    }

    fun recordActivity() {
        lastActivityAt = System.currentTimeMillis()
    }

    /** Checked on app foreground to decide whether to show the Auth screen. */
    fun hasGracePeriodExpired(graceTimeoutMillis: Long): Boolean {
        if (lastActivityAt == 0L) return true
        return System.currentTimeMillis() - lastActivityAt > graceTimeoutMillis
    }

    /** Call when the app process moves to the background (e.g. ProcessLifecycleOwner's onStop). */
    fun markBackgrounded() {
        backgroundedAt = System.currentTimeMillis()
    }

    /** Call when the app process returns to the foreground (onStart). If more
     *  than `graceTimeoutMillis` elapsed since backgrounding, the session is
     *  locked — this is the actual enforcement point that was missing. */
    fun evaluateForegroundLock(graceTimeoutMillis: Long) {
        val bgAt = backgroundedAt
        backgroundedAt = null
        if (bgAt != null && System.currentTimeMillis() - bgAt > graceTimeoutMillis) {
            lock()
        }
    }

    suspend fun persistLastUsedBook(bookId: Long) {
        context.sessionDataStore.edit { prefs -> prefs[LAST_USED_BOOK_ID] = bookId }
    }

    suspend fun getLastUsedBookId(): Long? {
        return context.sessionDataStore.data.first()[LAST_USED_BOOK_ID]
    }

    /** Auto-lock grace period, in minutes — exposed as a real Settings
     *  dropdown (1/5/15/30) rather than the previous hardcoded 5-minute
     *  constant with no way to change it. */
    val autoLockGraceMinutes: kotlinx.coroutines.flow.Flow<Int> = context.sessionDataStore.data
        .map { prefs -> prefs[AUTO_LOCK_GRACE_MINUTES] ?: 5 }

    suspend fun setAutoLockGraceMinutes(minutes: Int) {
        context.sessionDataStore.edit { prefs -> prefs[AUTO_LOCK_GRACE_MINUTES] = minutes }
    }

    suspend fun getAutoLockGraceMillis(): Long {
        val minutes = context.sessionDataStore.data.first()[AUTO_LOCK_GRACE_MINUTES] ?: 5
        return minutes * 60 * 1000L
    }
}
