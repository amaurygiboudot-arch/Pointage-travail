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
 * Le JSON local reste la source utilisée hors connexion. Quand il change, la copie
 * Firestore du compte connecté est mise à jour. Quand le cloud contient une version
 * différente d'une même session, elle est aussi réellement réinjectée localement.
 */
object HistoryCloudSync {
    private const val PREFS = "history_cloud_sync"
    private const val POINTAGE_PREFS = "pointage"
    private const val POINTAGE_KEY = "data"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_INITIALIZED_PREFIX = "initialized_"
    private const val KEY_SYNCED_IDS_PREFIX = "synced_ids_"
    private const val KEY_SUPPRESS_NEXT_LOCAL_EVENT = "suppress_next_local_event"
    private const val SYNC_DELAY_MS = 1800L

    private val handler = Handler(Looper.getMainLooper())
    private val syncRunning = AtomicBoolean(false)
    private var pending: Runnable? = null
    private var pointageListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var initialized = false

    /** À appeler une seule fois au démarrage du processus. */
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DIRTY, true).apply()
        schedule(context)
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
            onDone?.let { cb ->
                handler.post { cb(result.isSuccess, result.getOrElse { it.message ?: "Erreur de synchronisation" }) }
            }
        }.start()
    }

    /** Exécute une synchronisation complète. À appeler uniquement hors du thread UI. */
    internal fun syncBlocking(context: Context, uid: String): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val initializedKey = KEY_INITIALIZED_PREFIX + uid
        val syncedIdsKey = KEY_SYNCED_IDS_PREFIX + uid
        val local = PointageStore.load(context)
        val localIds = idsOf(local)
        val dirty = prefs.getBoolean(KEY_DIRTY, false)
        val wasInitialized = prefs.getBoolean(initializedKey, false)
        val previouslySynced = prefs.getStringSet(syncedIdsKey, emptySet()).orEmpty().toSet()
        val mustUploadFirst = dirty || (!wasInitialized && local.length() > 0)

        if (mustUploadFirst) {
            uploadAll(uid, local)
            // Une disparition par rapport au dernier état connu est une vraie suppression locale.
            // Les nouveaux documents créés par un autre appareil ne figurent pas dans previouslySynced
            // et ne sont donc jamais supprimés ici.
            if (wasInitialized && previouslySynced.isNotEmpty()) {
                deleteRemoved(uid, previouslySynced - localIds)
            }
        }

        val remoteIds = downloadAndMerge(context, uid)
        prefs.edit()
            .putBoolean(KEY_DIRTY, false)
            .putBoolean(initializedKey, true)
            .putStringSet(syncedIdsKey, remoteIds)
            .apply()
        return "historique Google à jour"
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

    private fun deleteRemoved(uid: String, removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(uid).collection("pointages")
        var batch = db.batch()
        var count = 0
        removedIds.forEach { id ->
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

    /** Retourne l'ensemble réel des IDs présents dans le cloud après fusion. */
    private fun downloadAndMerge(context: Context, uid: String): Set<String> {
        val snapshot = Tasks.await(
            FirebaseFirestore.getInstance()
                .collection("users").document(uid).collection("pointages").get()
        )
        if (snapshot.isEmpty) return emptySet()

        val local = PointageStore.load(context)
        val localIndex = HashMap<String, Int>()
        for (i in 0 until local.length()) {
            local.optJSONObject(i)?.let { localIndex[cloudId(it)] = i }
        }

        val remoteIds = linkedSetOf<String>()
        var changed = false
        for (doc in snapshot.documents) {
            remoteIds += doc.id
            val raw = doc.getString("payload").orEmpty()
            if (raw.isBlank()) continue
            val remoteItem = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            val index = localIndex[doc.id]
            if (index == null) {
                localIndex[doc.id] = local.length()
                local.put(remoteItem)
                changed = true
            } else {
                val localItem = local.optJSONObject(index)
                if (localItem == null || localItem.toString() != remoteItem.toString()) {
                    local.put(index, remoteItem)
                    changed = true
                }
            }
        }

        if (changed) {
            sortByEntry(local)
            val syncPrefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            syncPrefs.edit().putBoolean(KEY_SUPPRESS_NEXT_LOCAL_EVENT, true).commit()
            val written = context.getSharedPreferences(POINTAGE_PREFS, Context.MODE_PRIVATE)
                .edit().putString(POINTAGE_KEY, local.toString()).commit()
            if (!written) {
                syncPrefs.edit().remove(KEY_SUPPRESS_NEXT_LOCAL_EVENT).apply()
                error("Impossible d'écrire l'historique restauré")
            }
            PointageWidgetProvider.updateAll(context)
            QuickActionsWidgetProvider.updateAll(context)
        }
        return remoteIds
    }

    private fun idsOf(data: JSONArray): Set<String> {
        val ids = linkedSetOf<String>()
        for (i in 0 until data.length()) data.optJSONObject(i)?.let { ids += cloudId(it) }
        return ids
    }

    /** ID stable construit uniquement à partir des champs qui identifient la session. */
    private fun cloudId(item: JSONObject): String {
        val seed = buildString {
            append(item.optLong("entry", -1L)); append('|')
            append(item.optLong("arrivalTime", -1L)); append('|')
            append(item.optBoolean("manual", false)); append('|')
            append(item.optString("zoneId", "")); append('|')
            append(item.optString("zoneAddress", ""))
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }.take(40)
    }

    private fun sortByEntry(data: JSONArray) {
        val items = ArrayList<JSONObject>(data.length())
        for (i in 0 until data.length()) data.optJSONObject(i)?.let(items::add)
        items.sortBy { it.optLong("entry", Long.MAX_VALUE) }
        while (data.length() > 0) data.remove(data.length() - 1)
        items.forEach(data::put)
    }
}
