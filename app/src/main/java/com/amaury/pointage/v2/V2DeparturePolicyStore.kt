package com.amaury.pointage.v2

import android.content.Context

/** Explicit opt-in API. No UI activation is implied. Account scope is supplied by the runtime. */
object V2DeparturePolicyStore {
    private fun key(employerId: String) = "enabled:" + java.net.URLEncoder.encode(employerId, "UTF-8")
    fun enabled(context: Context, employerId: String): Boolean = V2RuntimeAccountScope.withScope {
        if (employerId.isBlank() || !V2RuntimeAccountScope.available()) false else runCatching {
            val prefs = V2RuntimeAccountScope.preferences(context, "departure_policy_v2")
            // A failed commit can mutate the preferences RAM cache: reconfirm durability.
            prefs.all[key(employerId)] == true && prefs.edit().putBoolean(key(employerId), true).commit()
        }.getOrDefault(false)
    }
    fun setEnabled(context: Context, employerId: String, enabled: Boolean): Boolean = V2RuntimeAccountScope.withScope {
        if (employerId.isBlank() || !V2RuntimeAccountScope.available()) false else
            V2RuntimeAccountScope.preferences(context, "departure_policy_v2").edit()
                .putBoolean(key(employerId), enabled).commit()
    }
}
