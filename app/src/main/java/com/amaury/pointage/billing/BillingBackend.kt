package com.amaury.pointage.billing

import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions

internal object BillingBackend {
    fun uid(): String? = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
    fun call(name: String, payload: Map<String, Any> = emptyMap()): Task<Any?> = try {
        check(uid() != null) { "Connecte ton compte avant de consulter les achats." }
        FirebaseFunctions.getInstance("us-central1").getHttpsCallable(name).call(payload)
            .continueWith { task ->
                if (!task.isSuccessful) throw task.exception ?: IllegalStateException("Vérification indisponible")
                task.result.data
            }
    } catch (error: Exception) { Tasks.forException(error) }
}
