package com.amaury.pointage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Sauvegarde cloud des réglages utilisateur de l'application.
 *
 * Les données spécifiques à l'installation (identifiant appareil, URI Drive persistée,
 * données Firebase/Google internes et historique pointage) sont volontairement exclues.
 * L'historique est géré séparément par [CloudPointageBackup].
 */
object CloudSettingsBackup {
    private const val COLLECTION = "app_backup"
    private const val DOCUMENT = "settings"
    private const val SCHEMA_VERSION = 1L

    private val excludedPrefs = setOf(
        "pointage",
        "firebase_device_registry",
        "drive_backup"
    )

    fun saveAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")

        val app = context.applicationContext
        val payload = runCatching { exportPreferences(app) }
            .getOrElse { return onDone(false, "Impossible de lire les réglages : ${it.localizedMessage ?: it.javaClass.simpleName}") }

        val data = hashMapOf<String, Any>(
            "schemaVersion" to SCHEMA_VERSION,
            "payload" to payload.toString(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection(COLLECTION).document(DOCUMENT)
            .set(data)
            .addOnSuccessListener { onDone(true, "réglages sauvegardés") }
            .addOnFailureListener { error ->
                onDone(false, error.localizedMessage ?: "Firestore refuse la sauvegarde des réglages")
            }
    }

    fun restoreAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")

        val app = context.applicationContext
        FirebaseFirestore.getInstance()
            .collection("users").document(user.uid)
            .collection(COLLECTION).document(DOCUMENT)
            .get()
            .addOnSuccessListener { doc ->
                if (!doc.exists()) {
                    onDone(false, "Aucune sauvegarde de réglages trouvée")
                    return@addOnSuccessListener
                }
                val payload = doc.getString("payload")
                if (payload.isNullOrBlank()) {
                    onDone(false, "Sauvegarde de réglages vide : rien n'a été remplacé")
                    return@addOnSuccessListener
                }
                val root = runCatching { JSONObject(payload) }.getOrNull()
                if (root == null || root.length() == 0) {
                    onDone(false, "Sauvegarde de réglages illisible : rien n'a été remplacé")
                    return@addOnSuccessListener
                }
                val result = runCatching { importPreferences(app, root) }
                if (result.isSuccess) onDone(true, "réglages restaurés")
                else onDone(false, "Restauration des réglages impossible : ${result.exceptionOrNull()?.localizedMessage ?: "erreur inconnue"}")
            }
            .addOnFailureListener { error ->
                onDone(false, error.localizedMessage ?: "Firestore refuse la restauration des réglages")
            }
    }

    private fun exportPreferences(context: Context): JSONObject {
        val root = JSONObject()
        val dir = File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles().orEmpty()
            .filter { it.isFile && it.name.endsWith(".xml") }
            .map { it.name.removeSuffix(".xml") }
            .filter(::shouldBackup)
            .sorted()
            .forEach { name ->
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                val values = JSONObject()
                prefs.all.forEach { (key, value) ->
                    when (value) {
                        null -> Unit
                        is Boolean, is Int, is Long, is Float, is String -> values.put(key, value)
                        is Set<*> -> values.put(key, JSONArray(value.filterIsInstance<String>().sorted()))
                    }
                }
                root.put(name, values)
            }
        return root
    }

    private fun importPreferences(context: Context, root: JSONObject) {
        val prefNames = root.keys().asSequence().toList().filter(::shouldBackup)
        for (name in prefNames) {
            val values = root.optJSONObject(name) ?: continue
            val editor = context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
            editor.clear()
            val keys = values.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                when (val value = values.opt(key)) {
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Double -> {
                        val asLong = value.toLong()
                        if (value == asLong.toDouble()) editor.putLong(key, asLong)
                        else editor.putFloat(key, value.toFloat())
                    }
                    is String -> editor.putString(key, value)
                    is JSONArray -> {
                        val set = linkedSetOf<String>()
                        for (i in 0 until value.length()) value.optString(i, null)?.let(set::add)
                        editor.putStringSet(key, set)
                    }
                }
            }
            if (!editor.commit()) error("Échec d'écriture de $name")
        }
    }

    private fun shouldBackup(name: String): Boolean {
        if (name in excludedPrefs) return false
        val lower = name.lowercase()
        return !lower.startsWith("com.google.firebase") &&
            !lower.startsWith("firebase") &&
            !lower.contains("google_sign_in") &&
            !lower.contains("google_app_measurement")
    }
}
