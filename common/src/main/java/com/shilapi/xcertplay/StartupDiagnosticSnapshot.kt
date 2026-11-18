package com.shilapi.xcertplay

import android.content.Context

/** Only the latest received boot event and launch result; no Intent extras or device identifiers. */
internal object StartupDiagnosticSnapshot {
    private const val PREFS = "diplay_startup_diagnostics"

    fun received(context: Context, launchEnabled: Boolean) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putLong("receivedAt", System.currentTimeMillis())
                .putBoolean("launchEnabled", launchEnabled)
                .putString("launchResult", if (launchEnabled) "pending" else "disabled")
                .remove("failureClass")
                .putString("serviceResult", "not-attempted")
                .remove("serviceFailureClass")
                .putLong("activitySeenAt", 0L)
                .putInt("retryCount", 0)
                .remove("retry1At").remove("retry1Result")
                .remove("retry2At").remove("retry2Result")
                .apply()
        }
    }

    fun launchResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("launchResult", if (failure == null) "startActivity-returned" else "failed")
                .putString("failureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?")).apply()
        }
    }

    /** Records whether the boot-started foreground service could be started. */
    fun serviceResult(context: Context, failure: RuntimeException? = null) {
        runCatching {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("serviceResult", if (failure == null) "service-started" else "failed")
                .putString("serviceFailureClass", failure?.javaClass?.simpleName?.take(80)
                    ?.replace(Regex("[^A-Za-z0-9_$]"), "?") ?: "none").apply()
        }
    }

    /** Records the first resume of DiPlayActivity since the last boot; later resumes change nothing. */
    fun markActivitySeen(context: Context) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getLong("activitySeenAt", 0L) == 0L) {
                prefs.edit().putLong("activitySeenAt", System.currentTimeMillis()).apply()
            }
        }
    }

    /** Records one delayed relaunch attempt (1-based) and its outcome. */
    fun retryAttempt(context: Context, attempt: Int, failure: RuntimeException? = null) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val result = if (failure == null) "startActivity-returned"
                else "failed:" + (failure.javaClass.simpleName.take(80)
                    .replace(Regex("[^A-Za-z0-9_$]"), "?"))
            prefs.edit()
                .putInt("retryCount", maxOf(prefs.getInt("retryCount", 0), attempt))
                .putLong("retry${attempt}At", System.currentTimeMillis())
                .putString("retry${attempt}Result", result).apply()
        }
    }

    /** Epoch ms of the first DiPlayActivity resume since the last boot, or 0 when none happened. */
    fun activitySeenAt(context: Context): Long = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("activitySeenAt", 0L)
    }.getOrDefault(0L)

    fun retryCount(context: Context): Int = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("retryCount", 0)
    }.getOrDefault(0)

    fun report(context: Context): String = runCatching {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val receivedAt = prefs.getLong("receivedAt", 0)
        if (receivedAt == 0L) {
            "Boot diagnostics received=false; no boot delivery has been recorded by this version."
        } else {
            val activitySeenAt = prefs.getLong("activitySeenAt", 0L)
            val count = prefs.getInt("retryCount", 0)
            val retries = (1..count).joinToString(";") { attempt ->
                "$attempt@${prefs.getLong("retry${attempt}At", 0L)}=" +
                    "${prefs.getString("retry${attempt}Result", "unknown")}"
            }.ifEmpty { "none" }
            "Boot diagnostics received=true receivedAtEpochMs=$receivedAt " +
                "launchEnabledAtBoot=${prefs.getBoolean("launchEnabled", false)} " +
                "launchResult=${prefs.getString("launchResult", "unknown")} " +
                "failureClass=${prefs.getString("failureClass", "none") ?: "none"} " +
                "serviceResult=${prefs.getString("serviceResult", "not-attempted")} " +
                "serviceFailureClass=${prefs.getString("serviceFailureClass", "none") ?: "none"} " +
                "activitySeenAtMs=$activitySeenAt activityShown=${activitySeenAt != 0L} retries=$retries; " +
                "startActivity-returned does not prove the OEM displayed the app — " +
                "only activitySeenAt, close after receivedAtEpochMs, proves the boot launch showed it."
        }
    }.getOrDefault("Boot diagnostics unavailable.")
}
