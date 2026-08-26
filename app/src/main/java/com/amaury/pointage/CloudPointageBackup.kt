package com.amaury.pointage

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import org.json.JSONArray

/**
 * Sauvegarde/restauration de l'historique de pointage dans l'espace Firestore
 * privé de l'utilisateur connecté.
 *
 * Chemin : users/{uid}/pointages/{entry_index}
 */
object CloudPointageBackup {
    private const val MAX_BATCH_OPS = 450

    fun saveAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")

        val local = PointageStore.load(context.applicationContext)
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(user.uid).collection("pointages")

        collection.get()
            .addOnSuccessListener { remote ->
                val desired = LinkedHashMap<String, Map<String, Any>>()
                for (i in 0 until local.length()) {
                    val item = local.optJSONObject(i) ?: continue
                    val entry = item.optLong("entry", 0L)
                    val id = "${entry}_${i}"
                    desired[id] = hashMapOf(
                        "entry" to entry,
                        "index" to i,
                        "payload" to item.toString(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                }

                val operations = mutableListOf<(WriteBatch) -> Unit>()
                remote.documents.forEach { doc ->
                    if (!desired.containsKey(doc.id)) operations += { batch -> batch.delete(doc.reference) }
                }
                desired.forEach { (id, data) ->
                    operations += { batch -> batch.set(collection.document(id), data) }
                }

                commitOperations(db, operations, 0) { ok, error ->
                    if (ok) onDone(true, "${desired.size} pointage(s) sauvegardé(s)")
                    else onDone(false, error ?: "Échec de la sauvegarde")
                }
            }
            .addOnFailureListener { error ->
                onDone(false, error.localizedMessage ?: "Firestore refuse la lecture")
            }
    }

    fun restoreAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")

        val db = FirebaseFirestore.getInstance()
        db.collection("users").document(user.uid).collection("pointages")
            .orderBy("index")
            .get()
            .addOnSuccessListener { snapshot ->
                val restored = JSONArray()
                for (doc in snapshot.documents) {
                    val payload = doc.getString("payload") ?: continue
                    runCatching { org.json.JSONObject(payload) }.getOrNull()?.let(restored::put)
                }
                if (snapshot.size() > 0 && restored.length() == 0) {
                    onDone(false, "Sauvegarde cloud illisible : rien n'a été remplacé")
                    return@addOnSuccessListener
                }
                PointageStore.save(context.applicationContext, restored)
                onDone(true, "${restored.length()} pointage(s) restauré(s)")
            }
            .addOnFailureListener { error ->
                onDone(false, error.localizedMessage ?: "Firestore refuse la restauration")
            }
    }

    private fun commitOperations(
        db: FirebaseFirestore,
        operations: List<(WriteBatch) -> Unit>,
        start: Int,
        onDone: (Boolean, String?) -> Unit
    ) {
        if (start >= operations.size) {
            onDone(true, null)
            return
        }
        val end = minOf(start + MAX_BATCH_OPS, operations.size)
        val batch = db.batch()
        for (i in start until end) operations[i](batch)
        batch.commit()
            .addOnSuccessListener { commitOperations(db, operations, end, onDone) }
            .addOnFailureListener { error -> onDone(false, error.localizedMessage ?: error.javaClass.simpleName) }
    }
}
