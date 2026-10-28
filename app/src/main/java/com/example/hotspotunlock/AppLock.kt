package com.example.hotspotunlock

import android.content.SharedPreferences

/**
 * Wrong-try lockout for the app lock (PIN and fingerprint share ONE counter).
 *
 *  - 5 wrong tries                       -> locked for 10 seconds (first time)
 *  - after that, every further 5 wrong   -> locked for 30 seconds, every time
 *  - a correct PIN / fingerprint         -> everything resets
 *
 * The state lives in SharedPreferences, so closing and reopening the app does not reset it.
 * Change the three constants below to tune the policy.
 */
class AppLock(private val prefs: SharedPreferences) {

    companion object {
        const val MAX_TRIES = 5                 // wrong tries before each lockout
        const val FIRST_LOCK_MS = 10_000L       // first lockout
        const val NEXT_LOCK_MS = 30_000L        // every lockout after the first

        private const val K_FAILS = "lock_fails"
        private const val K_LEVEL = "lock_level"
        private const val K_START = "lock_start"
        private const val K_UNTIL = "lock_until"
    }

    /** Milliseconds left in the current lockout, or 0 when not locked. */
    @Synchronized
    fun remainingMs(): Long {
        val until = prefs.getLong(K_UNTIL, 0L)
        if (until == 0L) return 0L
        val start = prefs.getLong(K_START, 0L)
        val now = System.currentTimeMillis()
        val duration = until - start
        // Clock set backwards to cheat the timer: make the person wait the full time again.
        val remaining = if (now < start) duration else until - now
        return if (remaining > 0) remaining else 0L
    }

    fun isLocked(): Boolean = remainingMs() > 0

    fun triesLeft(): Int = (MAX_TRIES - prefs.getInt(K_FAILS, 0)).coerceAtLeast(0)

    /**
     * Call after one wrong PIN or one wrong finger.
     * Returns the lockout length in ms if this try started a lockout, otherwise 0.
     * [force] = the phone itself reported "too many tries", so lock now.
     */
    @Synchronized
    fun registerFailure(force: Boolean = false): Long {
        if (isLocked()) return remainingMs()
        val fails = prefs.getInt(K_FAILS, 0) + 1
        if (fails >= MAX_TRIES || force) {
            val level = prefs.getInt(K_LEVEL, 0)
            val duration = if (level == 0) FIRST_LOCK_MS else NEXT_LOCK_MS
            val now = System.currentTimeMillis()
            prefs.edit()
                .putInt(K_FAILS, 0)
                .putInt(K_LEVEL, level + 1)
                .putLong(K_START, now)
                .putLong(K_UNTIL, now + duration)
                .apply()
            return duration
        }
        prefs.edit().putInt(K_FAILS, fails).apply()
        return 0L
    }

    /** Call after a correct PIN or fingerprint. */
    @Synchronized
    fun registerSuccess() {
        prefs.edit()
            .putInt(K_FAILS, 0)
            .putInt(K_LEVEL, 0)
            .putLong(K_START, 0L)
            .putLong(K_UNTIL, 0L)
            .apply()
    }
}
