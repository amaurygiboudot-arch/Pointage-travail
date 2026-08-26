package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sauvegarde/restauration des préférences et fichiers utilisateur de HoraTrack.
 *
 * L'historique de pointage est volontairement géré uniquement par HistoryCloudSync :
 * aucun appel doublon n'est effectué ici.
 */
object AppCloudBackup {
    private const val LOCAL_PREFS = "app_cloud_backup"
    private const val INITIALIZED_PREFIX = "initialized_"
    private const val BACKUP_DOC = "main"
    private const val IMAGE_CHUNK_SIZE = 500_000
    private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
    private const val BACKUP_DELAY_MS = 1200L

    private val initialized = AtomicBoolean(false)
    private val initRunning = AtomicBoolean(false)
    private val backupRunning = AtomicBoolean(false)
    private val restoreRunning = AtomicBoolean(false)
    private val listeners = mutableMapOf<String, SharedPreferences.OnSharedPreferenceChangeListener>()
    private val handler = Handler(Looper.getMainLooper())
    private var pendingBackup: Runnable? = null

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        val app = context.applicationContext

        if (app is Application) {
            app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStopped(activity: Activity) { scheduleBackup(app) }
                override fun onActivityCreated(a: Activity, b: Bundle?) = Unit
                override fun onActivityStarted(a: Activity) = Unit
                override fun onActivityResumed(a: Activity) = Unit
                override fun onActivityPaused(a: Activity) = Unit
                override fun onActivitySaveInstanceState(a: Activity, b: Bundle) = Unit
                override fun onActivityDestroyed(a: Activity) = Unit
            })
        }

        FirebaseAuth.getInstance().addAuthStateListener { auth ->
            val user = auth.currentUser ?: return@addAuthStateListener
            registerPreferenceListeners(app)
            ensureAccountInitialized(app, user.uid)
        }
    }

    /**
     * Première connexion après installation : restaurer avant toute nouvelle sauvegarde.
     * Une erreur réseau ne doit jamais être interprétée comme "aucune sauvegarde".
     */
    private fun ensureAccountInitialized(context: Context, uid: String) {
        if (isReady(context, uid)) return
        if (!initRunning.compareAndSet(false, true)) return
        Thread {
            val completed = runCatching {
                val restored = restoreSettings(context, uid)
                if (!restored) backupNowBlocking(context, uid)
                true
            }.getOrDefault(false)
            if (completed) {
                context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(INITIALIZED_PREFIX + uid, true).apply()
            }
            initRunning.set(false)
        }.start()
    }

    private fun isReady(context: Context, uid: String): Boolean =
        context.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
            .getBoolean(INITIALIZED_PREFIX + uid, false)

    fun scheduleBackup(context: Context) {
        val user = FirebaseAuth.getInstance().currentUser ?: return
        val app = context.applicationContext
        if (!isReady(app, user.uid)) {
            ensureAccountInitialized(app, user.uid)
            return
        }
        pendingBackup?.let(handler::removeCallbacks)
        val task = Runnable { backupNow(app) }
        pendingBackup = task
        handler.postDelayed(task, BACKUP_DELAY_MS)
    }

    /** Sauvegarde uniquement les réglages/fichiers utilisateur, pas l'historique. */
    fun backupNow(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onDone?.invoke(false, "Aucun compte Google connecté")
            return
        }
        if (!isReady(context.applicationContext, user.uid)) {
            ensureAccountInitialized(context.applicationContext, user.uid)
            onDone?.invoke(false, "Restauration initiale en cours")
            return
        }
        if (!backupRunning.compareAndSet(false, true)) {
            onDone?.invoke(true, "Sauvegarde des réglages déjà en cours")
            return
        }
        Thread {
            val result = runCatching {
                backupNowBlocking(context.applicationContext, user.uid)
                "réglages et fichiers utilisateur sauvegardés"
            }
            backupRunning.set(false)
            onDone?.let { cb -> handler.post { cb(result.isSuccess, result.getOrElse { it.message ?: "Erreur de sauvegarde" }) } }
        }.start()
    }

    /** Restaure uniquement les réglages/fichiers utilisateur, pas l'historique. */
    fun restoreNow(context: Context, onDone: ((Boolean, String) -> Unit)? = null) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            onDone?.invoke(false, "Aucun compte Google connecté")
            return
        }
        if (!restoreRunning.compareAndSet(false, true)) {
            onDone?.invoke(true, "Restauration des réglages déjà en cours")
            return
        }
        Thread {
            val result = runCatching {
                val restored = restoreSettings(context.applicationContext, user.uid)
                if (!restored) error("Aucune sauvegarde de réglages trouvée")
                context.applicationContext.getSharedPreferences(LOCAL_PREFS, Context.MODE_PRIVATE)
                    .edit().putBoolean(INITIALIZED_PREFIX + user.uid, true).apply()
                "réglages et fichiers utilisateur restaurés"
            }
            restoreRunning.set(false)
            onDone?.let { cb -> handler.post { cb(result.isSuccess, result.getOrElse { it.message ?: "Erreur de restauration" }) } }
        }.start()
    }

    private fun backupNowBlocking(context: Context, uid: String) {
        // Le fichier est sauvegardé d'abord : si son écriture échoue, on n'annonce pas
        // qu'un instantané de réglages complet a été créé.
        backupCustomBackground(context, uid)
        val prefsJson = collectPreferences(context).toString()
        val doc = FirebaseFirestore.getInstance()
            .collection("users").document(uid).collection("appBackup").document(BACKUP_DOC)
        Tasks.await(doc.set(mapOf(
            "schema" to 1,
            "preferencesJson" to prefsJson,
            "updatedAt" to FieldValue.serverTimestamp(),
            "platform" to "android"
        )))
    }

    private fun restoreSettings(context: Context, uid: String): Boolean {
        val db = FirebaseFirestore.getInstance()
        val doc = Tasks.await(db.collection("users").document(uid).collection("appBackup").document(BACKUP_DOC).get())
        if (!doc.exists()) return false
        val raw = doc.getString("preferencesJson").orEmpty()
        if (raw.isNotBlank()) restorePreferences(context, JSONObject(raw))
        restoreCustomBackground(context, uid)
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        return true
    }

    private fun collectPreferences(context: Context): JSONObject {
        val root = JSONObject()
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            if (!shouldBackupPreferenceFile(name)) return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val values = JSONObject()
            prefs.all.forEach { (key, value) ->
                val item = JSONObject()
                when (value) {
                    is String -> { item.put("t", "s"); item.put("v", value) }
                    is Int -> { item.put("t", "i"); item.put("v", value) }
                    is Long -> { item.put("t", "l"); item.put("v", value) }
                    is Float -> { item.put("t", "f"); item.put("v", value.toDouble()) }
                    is Boolean -> { item.put("t", "b"); item.put("v", value) }
                    is Set<*> -> {
                        item.put("t", "ss")
                        val arr = JSONArray()
                        value.filterIsInstance<String>().forEach(arr::put)
                        item.put("v", arr)
                    }
                    else -> return@forEach
                }
                values.put(key, item)
            }
            root.put(name, values)
        }
        return root
    }

    private fun restorePreferences(context: Context, root: JSONObject) {
        val names = root.keys()
        while (names.hasNext()) {
            val name = names.next()
            if (!shouldBackupPreferenceFile(name)) continue
            val values = root.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = values.optJSONObject(key) ?: continue
                when (item.optString("t")) {
                    "s" -> editor.putString(key, item.optString("v", ""))
                    "i" -> editor.putInt(key, item.optInt("v"))
                    "l" -> editor.putLong(key, item.optLong("v"))
                    "f" -> editor.putFloat(key, item.optDouble("v").toFloat())
                    "b" -> editor.putBoolean(key, item.optBoolean("v"))
                    "ss" -> {
                        val arr = item.optJSONArray("v") ?: JSONArray()
                        val set = linkedSetOf<String>()
                        for (i in 0 until arr.length()) set += arr.optString(i)
                        editor.putStringSet(key, set)
                    }
                }
            }
            editor.apply()
        }
    }

    private fun shouldBackupPreferenceFile(name: String): Boolean {
        val n = name.lowercase()
        if (n == "pointage" || n == "drive_backup" || n == LOCAL_PREFS || n == "history_cloud_sync") return false
        val blocked = listOf(
            "firebase", "google", "gms", "sentry", "workmanager", "appcheck", "app_check",
            "device_registry", "telemetry", "security", "recovery", "crash", "update_", "com.google"
        )
        return blocked.none { n.contains(it) }
    }

    private fun registerPreferenceListeners(context: Context) {
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.filter { it.name.endsWith(".xml") }?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            if (!shouldBackupPreferenceFile(name) || listeners.containsKey(name)) return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> scheduleBackup(context) }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            listeners[name] = listener
        }
    }

    private fun backupCustomBackground(context: Context, uid: String) {
        val file = CustomBackgroundStore.resolve(context)
        val base = FirebaseFirestore.getInstance().collection("users").document(uid).collection("appBackup")
        val metaRef = base.document("background")
        val previousMeta = Tasks.await(metaRef.get())
        val previousChunks = (previousMeta.getLong("chunks") ?: 0L).toInt().coerceIn(0, 256)

        if (file == null || !file.exists() || file.length() <= 0L) {
            Tasks.await(metaRef.set(mapOf("present" to false, "chunks" to 0, "updatedAt" to FieldValue.serverTimestamp())))
            deleteBackgroundChunks(base, 0, previousChunks)
            return
        }
        if (file.length() > MAX_IMAGE_BYTES) {
            error("Fond personnalisé trop volumineux pour la sauvegarde cloud")
        }

        val encoded = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val chunks = encoded.chunked(IMAGE_CHUNK_SIZE)
        chunks.forEachIndexed { index, chunk ->
            Tasks.await(base.document("background_$index").set(mapOf("data" to chunk)))
        }
        Tasks.await(metaRef.set(mapOf(
            "present" to true,
            "chunks" to chunks.size,
            "bytes" to file.length(),
            "updatedAt" to FieldValue.serverTimestamp()
        )))
        deleteBackgroundChunks(base, chunks.size, previousChunks)
    }

    private fun deleteBackgroundChunks(
        base: com.google.firebase.firestore.CollectionReference,
        keepCount: Int,
        previousCount: Int
    ) {
        if (previousCount <= keepCount) return
        for (i in keepCount until previousCount) {
            Tasks.await(base.document("background_$i").delete())
        }
    }

    private fun restoreCustomBackground(context: Context, uid: String) {
        val base = FirebaseFirestore.getInstance().collection("users").document(uid).collection("appBackup")
        val meta = Tasks.await(base.document("background").get())
        if (!meta.exists()) return
        if (meta.getBoolean("present") != true) {
            CustomBackgroundStore.clear(context)
            return
        }
        val count = (meta.getLong("chunks") ?: 0L).toInt().coerceIn(0, 256)
        if (count == 0) error("Sauvegarde du fond personnalisée incomplète")

        val encoded = buildString {
            for (i in 0 until count) {
                val part = Tasks.await(base.document("background_$i").get()).getString("data").orEmpty()
                if (part.isBlank()) error("Morceau $i du fond personnalisé manquant")
                append(part)
            }
        }
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val expectedBytes = meta.getLong("bytes")
        if (expectedBytes != null && bytes.size.toLong() != expectedBytes) {
            error("Fond personnalisé restauré avec une taille incorrecte")
        }
        val target = CustomBackgroundStore.primary(context)
        target.parentFile?.mkdirs()
        target.writeBytes(bytes)
        CustomBackgroundStore.saveBackup(context)
        context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("custom_image_bg", true).apply()
    }
}
