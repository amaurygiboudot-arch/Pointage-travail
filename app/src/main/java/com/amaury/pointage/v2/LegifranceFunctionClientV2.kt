package com.amaury.pointage.v2

import com.google.android.gms.tasks.Task
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.HttpsCallableResult

/** Appelle le relais Firebase déployé en us-central1. L'auth Firebase est jointe par le SDK. */
object LegifranceFunctionClientV2 {
    private val functions by lazy { FirebaseFunctions.getInstance("us-central1") }

    fun request(
        path: String,
        body: Map<String, Any?> = emptyMap()
    ): Task<HttpsCallableResult> {
        val payload = hashMapOf<String, Any?>(
            "path" to path,
            "body" to body
        )
        return functions
            .getHttpsCallable("legifranceRequest")
            .call(payload)
    }
}
