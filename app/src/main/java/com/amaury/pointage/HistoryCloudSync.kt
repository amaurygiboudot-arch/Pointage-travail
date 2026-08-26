package com.amaury.pointage

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Synchronisation bidirectionnelle de l'historique de pointage.
 *
 * Le JSON local reste disponible hors connexion. La fusion utilise un hash de référence
 * issu du dernier sync, modifiedAt et des tombstones de suppression afin d'éviter qu'un
 * appareil ancien écrase silencieusement des changements plus récents d'un autre appareil.
 */
object HistoryCloudSync {
    private const val PREFS = "history_cloud_sync"
    private const val POINTAGE_PREFS = "pointage"
    private const val POINTAGE_KEY = "data"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_INITIALIZED_PREFIX = "initialized_"
    private const val KEY_SYNCED_IDS_PREFIX = "synced_ids_"
    private const val KEY_BASELINE_PREFIX = "baseline_hashes_"
    private const val KEY_TOMBSTONES_PREFIX = "tombstones_"
    private const val KEY_SUPPRESS_NEXT_LOCAL_EVENT = "suppress_next_local_event"
    private const val SYNC_DELAY_MS = 1800L

    private data class RemoteRecord(
        val id: String,
        val item: JSONObject,
        val serverUpdatedAt: Long
    )

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunning = AtomicBoolean(false)
    private var pending: Runnable? = null
    private var pointageListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var initialized = false

    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val syncPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val pointagePrefs = app.getSharedPreferences(POINTAGE_PREFS, Context.MODE_PRIVATE)

            pointageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key != POINTAGE_KEY) return@OnSharedPreferenceChangeListener
                if (syncPrefs.getBoolean(KEY_SUPPRESS_NEXT_LOCAL_EVENT, false)) {
                    syncPrefs.edit().remove(KEY_SUPPRESS_NEXT_LOCAL_EVENT).apply()
                    return@OnSharedPreferenceChangeListener
                }
                onLocalChanged(app)
            }.also(pointagePrefs::registerOnSharedPreferenceChangeListener)

            val auth = FirebaseAuth.getInstance()
            authListener = FirebaseAuth.AuthStateListener { state ->
                if (state.currentUser != null) schedule(app)
            }.also(auth::addAuthStateListener)

            initialized = true
            if (auth.currentUser != null) schedule(app)
        }
    }

    fun onLocalChanged(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val user = FirebaseAuth.getInstance().currentUser
        if (user != null) recordLocalDeletions(app, prefs, user.uid)
        prefs.edit().putBoolean(KEY_DIRTY, true).apply()
        schedule(app)
    }

    private fun recordLocalDeletions(context: Context, prefs: SharedPreferences, uid: String) {
        val syncedIds = prefs.getStringSet(KEY_SYNCED_IDS_PREFIX + uid, emptySet()).orEmpty().toSet()
        if (syncedIds.isEmpty()) return
        val currentIds = idsOf(PointageStore.load(context))
        val tombstones = readLongMap(prefs.getString(KEY_TOMBSTONES_PREFIX + uid, null))
        val now = System.currentTimeMillis()
        (syncedIds - currentIds).forEach { id -> if (!tombstones.containsKey(id)) tombstones[id] = now }
        currentIds.forEach(tombstones::remove)
        prefs.edit().putString(KEY_TOMBSTONES_PREFIX + uid, longMapJson(tombstones)).apply()
    }

    fun schedule(context: Context) {
        if (FirebaseAuth.getInstance().currentUser == null) return
        val app = context.applicationContext
        pending?.let(handler::removeCallbacks)
        val task = Runnable { syncNow(app) }
        pending = task
        handler.postDelayed(task, SYNC_DELAY_MS)
    }

    fun syncNow(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onDone?.invoke(false, "Aucun compte Google connecté")
            return
        }
        if (!syncRunning.compareAndSet(false, true)) {
            onDone?.invoke(true, "Synchronisation déjà en cours")
            return
        }

        val app = context.applicationContext
        Thread {
            val result = runCatching { syncBlocking(app, user.uid) }
            syncRunning.set(false)
            onDone?.let { callback ->
                handler.post { callback(result.isSuccess, result.getOrElse { it.message ?: "Erreur de synchronisation" }) }
            }
        }.start()
    }

    /** Télécharge d'abord, fusionne, puis publie l'état convergé. */
    internal fun syncBlocking(context: Context, uid: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initializedKey = KEY_INITIALIZED_PREFIX + uid
        val syncedIdsKey = KEY_SYNCED_IDS_PREFIX + uid
        val baselineKey = KEY_BASELINE_PREFIX + uid
        val tombstonesKey = KEY_TOMBSTONES_PREFIX + uid

        val localData = PointageStore.load(context)
        val localById = indexItems(localData)
        val baseline = readStringMap(prefs.getString(baselineKey, null))
        val tombstones = readLongMap(prefs.getString(tombstonesKey, null))
        val wasInitialized = prefs.getBoolean(initializedKey, false)
        val previouslySynced = prefs.getStringSet(syncedIdsKey, emptySet()).orEmpty().toSet()

        // Si une suppression s'est produite avant que le listener ne puisse l'enregistrer,
        // on l'infère ici à partir du dernier état synchronisé.
        if (wasInitialized) {
            val now = System.currentTimeMillis()
            (previouslySynced - localById.keys).forEach { id -> if (!tombstones.containsKey(id)) tombstones[id] = now }
        }

        val remote = fetchRemote(uid)
        val merged = linkedMapOf<String, JSONObject>()
        localById.forEach { (id, item) -> merged[id] = JSONObject(item.toString()) }
        val deleteFromCloud = linkedSetOf<String>()

        remote.forEach { (id, record) ->
            val localItem = merged[id]
            if (localItem == null) {
                val deletedAt = tombstones[id]
                if (deletedAt != null) {
                    if (record.serverUpdatedAt > deletedAt) {
                        // Le cloud a changé après la suppression locale : on préserve la version récente.
                        merged[id] = JSONObject(record.item.toString())
                        tombstones.remove(id)
                    } else {
                        deleteFromCloud += id
                    }
                } else {
                    merged[id] = JSONObject(record.item.toString())
                }
                return@forEach
            }

            val localHash = payloadHash(localItem)
            val remoteHash = payloadHash(record.item)
            if (localHash == remoteHash) {
                tombstones.remove(id)
                return@forEach
            }

            val baseHash = baseline[id]
            val localChanged = baseHash == null || localHash != baseHash
            val remoteChanged = baseHash == null || remoteHash != baseHash

            val winner = when {
                localChanged && !remoteChanged -> localItem
                !localChanged && remoteChanged -> record.item
                else -> {
                    val localTime = effectiveModifiedAt(localItem)
                    val remoteTime = effectiveModifiedAt(record.item)
                    if (remoteTime > localTime) record.item else localItem
                }
            }
            merged[id] = JSONObject(winner.toString())
            tombstones.remove(id)
        }

        // Un document absent du cloud mais encore local doit être envoyé. Un tombstone ne
        // peut jamais cohabiter avec un objet local du même ID.
        merged.keys.forEach(tombstones::remove)

        val mergedArray = JSONArray()
        merged.values.sortedBy { it.optLong("entry", Long.MAX_VALUE) }.forEach(mergedArray::put)
        if (!sameHistory(localData, mergedArray)) writeLocalMerged(context, mergedArray)

        if (deleteFromCloud.isNotEmpty()) deleteRemote(uid, deleteFromCloud)
        uploadAll(uid, mergedArray)

        val finalIds = idsOf(mergedArray)
        val newBaseline = linkedMapOf<String, String>()
        for (i in 0 until mergedArray.length()) {
            val item = mergedArray.optJSONObject(i) ?: continue
            newBaseline[cloudId(item)] = payloadHash(item)
        }
        deleteFromCloud.forEach(tombstones::remove)

        prefs.edit()
            .putBoolean(KEY_DIRTY, false)
            .putBoolean(initializedKey, true)
            .putStringSet(syncedIdsKey, finalIds)
            .putString(baselineKey, stringMapJson(newBaseline))
            .putString(tombstonesKey, longMapJson(tombstones))
            .apply()

        return "historique Google à jour"
    }

    private fun fetchRemote(uid: String): Map<String, RemoteRecord> {
        val snapshot = Tasks.await(
            FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("pointages").get()
        )
        val result = linkedMapOf<String, RemoteRecord>()
        snapshot.documents.forEach { doc ->
            val raw = doc.getString("payload").orEmpty()
            if (raw.isBlank()) return@forEach
            val item = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val serverTime = doc.getTimestamp("updatedAt")?.toDate()?.time ?: 0L
            result[doc.id] = RemoteRecord(doc.id, item, serverTime)
        }
        return result
    }

    private fun uploadAll(uid: String, data: JSONArray) {
        if (data.length() == 0) return
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(uid).collection("pointages")
        var batch = db.batch()
        var count = 0
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val id = cloudId(item)
            val payload = hashMapOf<String, Any?>(
                "cloudId" to id,
                "entry" to item.optLong("entry", -1L),
                "arrivalTime" to item.optLong("arrivalTime", -1L),
                "exit" to if (item.isNull("exit")) null else item.optLong("exit", -1L),
                "manual" to item.optBoolean("manual", false),
                "modifiedAt" to effectiveModifiedAt(item),
                "payloadHash" to payloadHash(item),
                "payload" to item.toString(),
                "platform" to "android",
                "updatedAt" to FieldValue.serverTimestamp()
            )
            batch.set(collection.document(id), payload)
            count++
            if (count >= 400) {
                Tasks.await(batch.commit())
                batch = db.batch()
                count = 0
            }
        }
        if (count > 0) Tasks.await(batch.commit())
    }

    private fun deleteRemote(uid: String, ids: Set<String>) {
        if (ids.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(uid).collection("pointages")
        var batch = db.batch()
        var count = 0
        ids.forEach { id ->
            batch.delete(collection.document(id))
            count++
            if (count >= 400) {
                Tasks.await(batch.commit())
                batch = db.batch()
                count = 0
            }
        }
        if (count > 0) Tasks.await(batch.commit())
    }

    private fun writeLocalMerged(context: Context, data: JSONArray) {
        val syncPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        syncPrefs.edit().putBoolean(KEY_SUPPRESS_NEXT_LOCAL_EVENT, true).commit()
        val written = context.getSharedPreferences(POINTAGE_PREFS, Context.MODE_PRIVATE)
            .edit().putString(POINTAGE_KEY, data.toString()).commit()
        if (!written) {
            syncPrefs.edit().remove(KEY_SUPPRESS_NEXT_LOCAL_EVENT).apply()
            error("Impossible d'écrire l'historique restauré")
        }
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
    }

    private fun sameHistory(a: JSONArray, b: JSONArray): Boolean {
        if (a.length() != b.length()) return false
        val aMap = indexItems(a)
        val bMap = indexItems(b)
        if (aMap.keys != bMap.keys) return false
        return aMap.all { (id, item) -> payloadHash(item) == bMap[id]?.let(::payloadHash) }
    }

    private fun indexItems(data: JSONArray): LinkedHashMap<String, JSONObject> {
        val result = linkedMapOf<String, JSONObject>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            result[cloudId(item)] = item
        }
        return result
    }

    private fun idsOf(data: JSONArray): Set<String> = indexItems(data).keys

    /** ID stable construit uniquement à partir des champs qui identifient la session. */
    private fun cloudId(item: JSONObject): String {
        val seed = buildString {
            append(item.optLong("entry", -1L)); append('|')
            append(item.optLong("arrivalTime", -1L)); append('|')
            append(item.optBoolean("manual", false)); append('|')
            append(item.optString("zoneId", "")); append('|')
            append(item.optString("zoneAddress", ""))
        }
        return sha256(seed).take(40)
    }

    private fun payloadHash(item: JSONObject): String = sha256(item.toString())

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** Compatibilité avec les anciennes sessions qui n'avaient pas encore modifiedAt. */
    private fun effectiveModifiedAt(item: JSONObject): Long {
        var best = item.optLong("modifiedAt", 0L)
        best = maxOf(best, item.optLong("arrivalTime", 0L), item.optLong("entry", 0L))
        if (!item.isNull("exit")) best = maxOf(best, item.optLong("exit", 0L))
        val pauses = item.optJSONArray("pauses")
        if (pauses != null) {
            for (i in 0 until pauses.length()) {
                val pause = pauses.optJSONObject(i) ?: continue
                best = maxOf(best, pause.optLong("start", 0L))
                if (!pause.isNull("end")) best = maxOf(best, pause.optLong("end", 0L))
            }
        }
        return best
    }

    private fun readStringMap(raw: String?): MutableMap<String, String> {
        if (raw.isNullOrBlank()) return linkedMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        val result = linkedMapOf<String, String>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            json.optString(key).takeIf { it.isNotBlank() }?.let { result[key] = it }
        }
        return result
    }

    private fun readLongMap(raw: String?): MutableMap<String, Long> {
        if (raw.isNullOrBlank()) return linkedMapOf()
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return linkedMapOf()
        val result = linkedMapOf<String, Long>()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.optLong(key, 0L)
            if (value > 0L) result[key] = value
        }
        return result
    }

    private fun stringMapJson(map: Map<String, String>): String = JSONObject().apply {
        map.forEach { (key, value) -> put(key, value) }
    }.toString()

    private fun longMapJson(map: Map<String, Long>): String = JSONObject().apply {
        map.forEach { (key, value) -> put(key, value) }
    }.toString()
}
