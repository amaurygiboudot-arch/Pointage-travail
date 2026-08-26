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

/**
 * Sauvegarde/restauration de l'historique brut de pointage dans Firestore,
 * sous le compte Google/Firebase actuellement connecté.
 *
 * Le stockage local reste la source utilisée par l'application afin que le
 * pointage fonctionne sans réseau. Les écritures locales marquent la copie
 * comme "sale" puis une synchronisation est tentée en arrière-plan.
 */
object HistoryCloudSync {
    private const val PREFS = "history_cloud_sync"
    private const val POINTAGE_PREFS = "pointage"
    private const val POINTAGE_KEY = "data"
    private const val KEY_DIRTY = "dirty"
    private const val KEY_INITIALIZED_PREFIX = "initialized_"
    private const val SYNC_DELAY_MS = 1800L
    private val handler = Handler(Looper.getMainLooper())
    private var pending: Runnable? = null
    private var pointageListener: SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var authListener: FirebaseAuth.AuthStateListener? = null
    @Volatile private var syncing = false
    @Volatile private var applyingRemote = false
    @Volatile private var initialized = false

    /** À appeler une seule fois au démarrage du processus. */
    fun initialize(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val pointagePrefs = app.getSharedPreferences(POINTAGE_PREFS, Context.MODE_PRIVATE)
            pointageListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == POINTAGE_KEY && !applyingRemote) onLocalChanged(app)
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
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DIRTY, true).apply()
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
        if (syncing) {
            onDone?.invoke(true, "Synchronisation déjà en cours")
            return
        }
        syncing = true
        val app = context.applicationContext
        Thread {
            val result = runCatching {
                val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val initializedKey = KEY_INITIALIZED_PREFIX + user.uid
                val local = PointageStore.load(app)
                val mustUploadFirst = prefs.getBoolean(KEY_DIRTY, false) ||
                    (!prefs.getBoolean(initializedKey, false) && local.length() > 0)

                if (mustUploadFirst) uploadAll(user.uid, local)
                downloadAndMerge(app, user.uid)

                prefs.edit()
                    .putBoolean(KEY_DIRTY, false)
                    .putBoolean(initializedKey, true)
                    .apply()
                "historique Google à jour"
            }
            syncing = false
            onDone?.let { cb -> handler.post { cb(result.isSuccess, result.getOrElse { it.message ?: "Erreur de synchronisation" }) } }
        }.start()
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

    private fun downloadAndMerge(context: Context, uid: String) {
        val db = FirebaseFirestore.getInstance()
        val snapshot = Tasks.await(db.collection("users").document(uid).collection("pointages").get())
        if (snapshot.isEmpty) return

        val local = PointageStore.load(context)
        val known = HashSet<String>()
        for (i in 0 until local.length()) {
            local.optJSONObject(i)?.let { known += cloudId(it) }
        }

        var changed = false
        for (doc in snapshot.documents) {
            if (doc.id in known) continue
            val raw = doc.getString("payload").orEmpty()
            if (raw.isBlank()) continue
            val item = runCatching { JSONObject(raw) }.getOrNull() ?: continue
            local.put(item)
            known += doc.id
            changed = true
        }
        if (changed) {
            sortByEntry(local)
            applyingRemote = true
            try {
                context.getSharedPreferences(POINTAGE_PREFS, Context.MODE_PRIVATE)
                    .edit().putString(POINTAGE_KEY, local.toString()).commit()
            } finally {
                applyingRemote = false
            }
            PointageWidgetProvider.updateAll(context)
            QuickActionsWidgetProvider.updateAll(context)
        }
    }

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
