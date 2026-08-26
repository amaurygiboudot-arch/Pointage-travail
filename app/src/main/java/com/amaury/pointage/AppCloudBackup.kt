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
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Sauvegarde/restauration des préférences et fichiers utilisateur de HoraTrack.
 *
 * L'historique de pointage est volontairement géré uniquement par HistoryCloudSync.
 * Les instantanés de préférences et de fond personnalisé utilisent des générations
 * immuables : la métadonnée active n'est changée qu'après l'écriture complète et
 * vérifiable de tous les morceaux.
 */
object AppCloudBackup {
    private const val LOCAL_PREFS = "app_cloud_backup"
    private const val INITIALIZED_PREFIX = "initialized_"
    private const val BACKUP_DOC = "main"
    private const val CURRENT_SCHEMA = 2L
    private const val CHUNK_SIZE = 500_000
    private const val MAX_IMAGE_BYTES = 25L * 1024L * 1024L
    private const val MAX_PREFS_BYTES = 10L * 1024L * 1024L
    private const val MAX_CHUNKS = 256
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
            onDone?.let { callback ->
                handler.post { callback(result.isSuccess, result.getOrElse { it.message ?: "Erreur de sauvegarde" }) }
            }
        }.start()
    }

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
            onDone?.let { callback ->
                handler.post { callback(result.isSuccess, result.getOrElse { it.message ?: "Erreur de restauration" }) }
            }
        }.start()
    }

    private fun backupNowBlocking(context: Context, uid: String) {
        registerPreferenceListeners(context)
        backupCustomBackground(context, uid)

        val rawPrefs = collectPreferences(context).toString().toByteArray(Charsets.UTF_8)
        if (rawPrefs.size.toLong() > MAX_PREFS_BYTES) error("Réglages trop volumineux pour la sauvegarde cloud")

        val base = backupCollection(uid)
        val mainRef = base.document(BACKUP_DOC)
        val previous = Tasks.await(mainRef.get())
        val oldGeneration = previous.getString("prefsGeneration")
        val oldChunks = (previous.getLong("prefsChunks") ?: 0L).toInt().coerceIn(0, MAX_CHUNKS)

        val generation = generationId()
        val encoded = Base64.encodeToString(rawPrefs, Base64.NO_WRAP)
        val chunks = encoded.chunked(CHUNK_SIZE)
        if (chunks.size > MAX_CHUNKS) error("Sauvegarde des réglages trop fragmentée")

        try {
            writeGeneration(base, "prefs", generation, chunks)
            Tasks.await(mainRef.set(mapOf(
                "schema" to CURRENT_SCHEMA,
                "prefsGeneration" to generation,
                "prefsChunks" to chunks.size,
                "prefsBytes" to rawPrefs.size.toLong(),
                "prefsSha256" to sha256(rawPrefs),
                "updatedAt" to FieldValue.serverTimestamp(),
                "platform" to "android"
            )))
        } catch (t: Throwable) {
            deleteGenerationQuietly(base, "prefs", generation, chunks.size)
            throw t
        }

        if (!oldGeneration.isNullOrBlank() && oldGeneration != generation) {
            deleteGeneration(base, "prefs", oldGeneration, oldChunks)
        }
    }

    private fun restoreSettings(context: Context, uid: String): Boolean {
        val base = backupCollection(uid)
        val doc = Tasks.await(base.document(BACKUP_DOC).get())
        if (!doc.exists()) return false

        val schema = doc.getLong("schema") ?: 1L
        if (schema > CURRENT_SCHEMA) error("Sauvegarde créée par une version plus récente de HoraTrack")

        val root = if (schema >= 2L && !doc.getString("prefsGeneration").isNullOrBlank()) {
            val generation = requireNotNull(doc.getString("prefsGeneration"))
            val count = (doc.getLong("prefsChunks") ?: 0L).toInt().coerceIn(0, MAX_CHUNKS)
            if (count <= 0) error("Sauvegarde des réglages incomplète")
            val encoded = readGeneration(base, "prefs", generation, count)
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            verifyBytes(bytes, doc.getLong("prefsBytes"), doc.getString("prefsSha256"), "réglages")
            JSONObject(bytes.toString(Charsets.UTF_8))
        } else {
            // Compatibilité avec les sauvegardes schema 1 déjà créées.
            val raw = doc.getString("preferencesJson").orEmpty()
            if (raw.isBlank()) JSONObject() else JSONObject(raw)
        }

        restorePreferences(context, root)
        restoreCustomBackground(context, uid)
        registerPreferenceListeners(context)
        PointageWidgetProvider.updateAll(context)
        QuickActionsWidgetProvider.updateAll(context)
        return true
    }

    private fun collectPreferences(context: Context): JSONObject {
        val root = JSONObject()
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".xml") }?.sortedBy { it.name }?.forEach { file ->
            val name = file.name.removeSuffix(".xml")
            if (!shouldBackupPreferenceFile(name)) return@forEach
            val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
            val values = JSONObject()
            prefs.all.toSortedMap().forEach { (key, value) ->
                val item = JSONObject()
                when (value) {
                    is String -> { item.put("t", "s"); item.put("v", value) }
                    is Int -> { item.put("t", "i"); item.put("v", value) }
                    is Long -> { item.put("t", "l"); item.put("v", value) }
                    is Float -> { item.put("t", "f"); item.put("v", value.toDouble()) }
                    is Boolean -> { item.put("t", "b"); item.put("v", value) }
                    is Set<*> -> {
                        item.put("t", "ss")
                        val array = JSONArray()
                        value.filterIsInstance<String>().sorted().forEach(array::put)
                        item.put("v", array)
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
                        val array = item.optJSONArray("v") ?: JSONArray()
                        val set = linkedSetOf<String>()
                        for (i in 0 until array.length()) set += array.optString(i)
                        editor.putStringSet(key, set)
                    }
                }
            }
            if (!editor.commit()) error("Impossible de restaurer les réglages $name")
        }
    }

    private fun shouldBackupPreferenceFile(name: String): Boolean {
        val normalized = name.lowercase()
        if (normalized == "pointage" || normalized == "drive_backup" || normalized == LOCAL_PREFS || normalized == "history_cloud_sync") return false
        val blocked = listOf(
            "firebase", "google", "gms", "sentry", "workmanager", "appcheck", "app_check",
            "device_registry", "telemetry", "security", "recovery", "crash", "update_", "com.google"
        )
        return blocked.none { normalized.contains(it) }
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
        val base = backupCollection(uid)
        val metaRef = base.document("background")
        val previous = Tasks.await(metaRef.get())
        val oldGeneration = previous.getString("generation")
        val oldChunks = (previous.getLong("chunks") ?: 0L).toInt().coerceIn(0, MAX_CHUNKS)
        val oldLegacy = oldGeneration.isNullOrBlank() && oldChunks > 0

        if (file == null || !file.exists() || file.length() <= 0L) {
            Tasks.await(metaRef.set(mapOf(
                "schema" to CURRENT_SCHEMA,
                "present" to false,
                "chunks" to 0,
                "updatedAt" to FieldValue.serverTimestamp()
            )))
            if (!oldGeneration.isNullOrBlank()) deleteGeneration(base, "background", oldGeneration, oldChunks)
            if (oldLegacy) deleteLegacyBackgroundChunks(base, oldChunks)
            return
        }
        if (file.length() > MAX_IMAGE_BYTES) error("Fond personnalisé trop volumineux pour la sauvegarde cloud")

        val bytes = file.readBytes()
        val generation = generationId()
        val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
        val chunks = encoded.chunked(CHUNK_SIZE)
        if (chunks.size > MAX_CHUNKS) error("Fond personnalisé trop fragmenté")

        try {
            writeGeneration(base, "background", generation, chunks)
            Tasks.await(metaRef.set(mapOf(
                "schema" to CURRENT_SCHEMA,
                "present" to true,
                "generation" to generation,
                "chunks" to chunks.size,
                "bytes" to bytes.size.toLong(),
                "sha256" to sha256(bytes),
                "updatedAt" to FieldValue.serverTimestamp()
            )))
        } catch (t: Throwable) {
            deleteGenerationQuietly(base, "background", generation, chunks.size)
            throw t
        }

        if (!oldGeneration.isNullOrBlank() && oldGeneration != generation) {
            deleteGeneration(base, "background", oldGeneration, oldChunks)
        }
        if (oldLegacy) deleteLegacyBackgroundChunks(base, oldChunks)
    }

    private fun restoreCustomBackground(context: Context, uid: String) {
        val base = backupCollection(uid)
        val meta = Tasks.await(base.document("background").get())
        if (!meta.exists()) return
        if (meta.getBoolean("present") != true) {
            CustomBackgroundStore.clear(context)
            return
        }

        val count = (meta.getLong("chunks") ?: 0L).toInt().coerceIn(0, MAX_CHUNKS)
        if (count <= 0) error("Sauvegarde du fond personnalisé incomplète")
        val generation = meta.getString("generation")
        val encoded = if (!generation.isNullOrBlank()) {
            readGeneration(base, "background", generation, count)
        } else {
            // Compatibilité schema 1.
            buildString {
                for (i in 0 until count) {
                    val part = Tasks.await(base.document("background_$i").get()).getString("data").orEmpty()
                    if (part.isBlank()) error("Morceau $i du fond personnalisé manquant")
                    append(part)
                }
            }
        }

        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        verifyBytes(bytes, meta.getLong("bytes"), meta.getString("sha256"), "fond personnalisé")

        val target = CustomBackgroundStore.primary(context)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, target.name + ".restore.tmp")
        temp.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            temp.delete()
            error("Impossible de remplacer le fond personnalisé")
        }
        if (!temp.renameTo(target)) {
            target.writeBytes(bytes)
            temp.delete()
        }
        CustomBackgroundStore.saveBackup(context)
        context.getSharedPreferences("appearance_settings", Context.MODE_PRIVATE)
            .edit().putBoolean("custom_image_bg", true).apply()
    }

    private fun backupCollection(uid: String): CollectionReference =
        FirebaseFirestore.getInstance().collection("users").document(uid).collection("appBackup")

    private fun writeGeneration(base: CollectionReference, prefix: String, generation: String, chunks: List<String>) {
        chunks.forEachIndexed { index, chunk ->
            Tasks.await(base.document("${prefix}_${generation}_$index").set(mapOf(
                "generation" to generation,
                "index" to index,
                "data" to chunk
            )))
        }
    }

    private fun readGeneration(base: CollectionReference, prefix: String, generation: String, count: Int): String = buildString {
        for (i in 0 until count) {
            val doc = Tasks.await(base.document("${prefix}_${generation}_$i").get())
            if (doc.getString("generation") != generation) error("Génération de sauvegarde incohérente")
            val part = doc.getString("data").orEmpty()
            if (part.isBlank()) error("Morceau $i de $prefix manquant")
            append(part)
        }
    }

    private fun deleteGeneration(base: CollectionReference, prefix: String, generation: String, count: Int) {
        for (i in 0 until count.coerceIn(0, MAX_CHUNKS)) {
            Tasks.await(base.document("${prefix}_${generation}_$i").delete())
        }
    }

    private fun deleteGenerationQuietly(base: CollectionReference, prefix: String, generation: String, count: Int) {
        for (i in 0 until count.coerceIn(0, MAX_CHUNKS)) {
            runCatching { Tasks.await(base.document("${prefix}_${generation}_$i").delete()) }
        }
    }

    private fun deleteLegacyBackgroundChunks(base: CollectionReference, count: Int) {
        for (i in 0 until count.coerceIn(0, MAX_CHUNKS)) {
            Tasks.await(base.document("background_$i").delete())
        }
    }

    private fun verifyBytes(bytes: ByteArray, expectedSize: Long?, expectedSha: String?, label: String) {
        if (expectedSize != null && bytes.size.toLong() != expectedSize) error("$label restauré avec une taille incorrecte")
        if (!expectedSha.isNullOrBlank() && sha256(bytes) != expectedSha) error("Contrôle d'intégrité SHA-256 invalide pour $label")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun generationId(): String = UUID.randomUUID().toString().replace("-", "")
}
