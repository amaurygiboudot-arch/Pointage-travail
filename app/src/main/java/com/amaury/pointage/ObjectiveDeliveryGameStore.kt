package com.amaury.pointage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import org.json.JSONArray
import org.json.JSONObject

enum class ObjectiveSyncStatus(val label: String) {
    DEVICE("Enregistré sur l'appareil"),
    PENDING("Synchronisation en attente"),
    ONLINE("Sauvegardé en ligne")
}

object ObjectiveDeliveryGameStore {
    private const val PREFS = "objective_delivery_game"
    private const val KEY_ACTIVE_TYPE = "active_company_type"
    private const val KEY_SYNC_STATUS = "sync_status"
    private const val CAMPAIGN_PREFIX = "campaign_"

    fun loadActive(context: Context): ObjectiveDeliveryState? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val typeId = prefs.getString(KEY_ACTIVE_TYPE, null) ?: return null
        return load(context, ObjectiveCompanyType.fromId(typeId))
    }

    fun load(context: Context, type: ObjectiveCompanyType): ObjectiveDeliveryState? {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(CAMPAIGN_PREFIX + type.id, null)
            ?: return null
        return runCatching { decode(JSONObject(raw)) }.getOrNull()
    }

    fun setActive(context: Context, type: ObjectiveCompanyType) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, type.id)
            .apply()
    }

    fun save(context: Context, state: ObjectiveDeliveryState) {
        saveLocal(context, state, ObjectiveSyncStatus.PENDING)
        syncToCloud(context.applicationContext, state)
    }

    fun saveDeviceOnly(context: Context, state: ObjectiveDeliveryState) {
        saveLocal(context, state, ObjectiveSyncStatus.DEVICE)
    }

    fun syncStatus(context: Context): ObjectiveSyncStatus {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_SYNC_STATUS, ObjectiveSyncStatus.DEVICE.name)
        return runCatching { ObjectiveSyncStatus.valueOf(raw ?: "") }
            .getOrDefault(ObjectiveSyncStatus.DEVICE)
    }

    fun restoreLatestFromCloud(context: Context, onComplete: (ObjectiveDeliveryState?) -> Unit) {
        ensureFirebaseUser(
            onReady = { uid ->
                FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(uid)
                    .collection("objective_delivery")
                    .orderBy("updatedAt", Query.Direction.DESCENDING)
                    .limit(1)
                    .get()
                    .addOnSuccessListener { result ->
                        val payload = result.documents.firstOrNull()?.getString("payload")
                        val state = payload?.let { runCatching { decode(JSONObject(it)) }.getOrNull() }
                        if (state != null) {
                            saveLocal(context, state, ObjectiveSyncStatus.ONLINE)
                        }
                        onComplete(state)
                    }
                    .addOnFailureListener { onComplete(null) }
            },
            onUnavailable = { onComplete(null) }
        )
    }

    private fun saveLocal(context: Context, state: ObjectiveDeliveryState, status: ObjectiveSyncStatus) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE_TYPE, state.companyType.id)
            .putString(CAMPAIGN_PREFIX + state.companyType.id, encode(state).toString())
            .putString(KEY_SYNC_STATUS, status.name)
            .apply()
    }

    private fun syncToCloud(context: Context, state: ObjectiveDeliveryState) {
        ensureFirebaseUser(
            onReady = { uid ->
                val ref = FirebaseFirestore.getInstance()
                    .collection("users")
                    .document(uid)
                    .collection("objective_delivery")
                    .document(state.campaignId)

                FirebaseFirestore.getInstance().runTransaction { tx ->
                    val remote = tx.get(ref)
                    val remoteRevision = remote.getLong("revision") ?: 0L
                    check(remoteRevision <= state.revision.toLong()) {
                        "Une sauvegarde cloud plus récente existe."
                    }
                    tx.set(
                        ref,
                        mapOf(
                            "schemaVersion" to state.schemaVersion,
                            "campaignId" to state.campaignId,
                            "companyType" to state.companyType.id,
                            "revision" to state.revision,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "payload" to encode(state).toString()
                        )
                    )
                }.addOnSuccessListener {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SYNC_STATUS, ObjectiveSyncStatus.ONLINE.name)
                        .apply()
                }.addOnFailureListener {
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putString(KEY_SYNC_STATUS, ObjectiveSyncStatus.PENDING.name)
                        .apply()
                }
            },
            onUnavailable = {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SYNC_STATUS, ObjectiveSyncStatus.PENDING.name)
                    .apply()
            }
        )
    }

    private fun ensureFirebaseUser(onReady: (String) -> Unit, onUnavailable: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        auth.currentUser?.uid?.let {
            onReady(it)
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { result ->
                result.user?.uid?.let(onReady) ?: onUnavailable()
            }
            .addOnFailureListener { onUnavailable() }
    }

    internal fun encode(state: ObjectiveDeliveryState): JSONObject = JSONObject().apply {
        put("schemaVersion", state.schemaVersion)
        put("campaignId", state.campaignId)
        put("companyType", state.companyType.id)
        put("seed", state.seed)
        put("currentChapter", state.currentChapter)
        put("unlockedChapter", state.unlockedChapter)
        put("step", state.step)
        put("clientTrust", state.clientTrust)
        put("needCompleteness", state.needCompleteness)
        put("quotedPrice", state.quotedPrice)
        put("quotedDelayDays", state.quotedDelayDays)
        put("marginAmount", state.marginAmount)
        put("outcome", state.outcome.name)
        put("revision", state.revision)
        put("history", JSONArray(state.history))
    }

    internal fun decode(json: JSONObject): ObjectiveDeliveryState {
        val historyJson = json.optJSONArray("history") ?: JSONArray()
        val history = buildList {
            for (index in 0 until historyJson.length()) {
                historyJson.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }
        return ObjectiveDeliveryState(
            schemaVersion = json.optInt("schemaVersion", ObjectiveDeliveryGameEngine.SCHEMA_VERSION),
            campaignId = json.getString("campaignId"),
            companyType = ObjectiveCompanyType.fromId(json.getString("companyType")),
            seed = json.optLong("seed", 0L),
            currentChapter = json.optInt("currentChapter", 1),
            unlockedChapter = json.optInt("unlockedChapter", 1),
            step = json.optInt("step", 0),
            clientTrust = json.optInt("clientTrust", 50).coerceIn(0, 100),
            needCompleteness = json.optInt("needCompleteness", 0).coerceIn(0, 100),
            quotedPrice = json.optInt("quotedPrice", 0),
            quotedDelayDays = json.optInt("quotedDelayDays", 0),
            marginAmount = json.optInt("marginAmount", 0),
            outcome = runCatching {
                ObjectiveOutcome.valueOf(json.optString("outcome", ObjectiveOutcome.IN_PROGRESS.name))
            }.getOrDefault(ObjectiveOutcome.IN_PROGRESS),
            revision = json.optInt("revision", 1).coerceAtLeast(1),
            history = history
        )
    }
}
