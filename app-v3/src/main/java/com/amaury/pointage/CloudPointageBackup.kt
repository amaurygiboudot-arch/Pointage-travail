package com.amaury.pointage

import android.content.Context
import com.amaury.pointage.v2.HoraTrackV2
import com.amaury.pointage.v2.V2BackupManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.WriteBatch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Sauvegarde/restauration de l'historique de pointage dans l'espace Firestore
 * privé de l'utilisateur connecté.
 *
 * Quand le nouveau moteur est actif, le document contient le snapshot complet.
 * Les anciens documents restent lisibles uniquement pour une restauration/migration conservatrice.
 */
object CloudPointageBackup {
    private const val MAX_BATCH_OPS = 450
    private const val V2_DOCUMENT = "v2_complete"

    fun saveAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")
        if (HoraTrackV2.ENABLED) {
            val payload = runCatching { V2BackupManager.snapshot(context.applicationContext).toString() }
                .getOrElse { return onDone(false, "Impossible de préparer la sauvegarde : ${it.localizedMessage ?: it.javaClass.simpleName}") }
            FirebaseFirestore.getInstance()
                .collection("users").document(user.uid).collection("pointages").document(V2_DOCUMENT)
                .set(hashMapOf("format" to "horatrack_v2", "payload" to payload, "updatedAt" to FieldValue.serverTimestamp()))
                .addOnSuccessListener { onDone(true, "Sauvegarde HoraTrack complète enregistrée") }
                .addOnFailureListener { onDone(false, it.localizedMessage ?: "Échec de la sauvegarde HoraTrack") }
            return
        }

        val local = PointageStore.load(context.applicationContext)
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(user.uid).collection("pointages")
        collection.get().addOnSuccessListener { remote ->
            val desired = LinkedHashMap<String, Map<String, Any>>()
            for (i in 0 until local.length()) {
                val item = local.optJSONObject(i) ?: continue
                val entry = item.optLong("entry", 0L)
                val id = "${entry}_${i}"
                desired[id] = hashMapOf("entry" to entry, "index" to i, "payload" to item.toString(), "updatedAt" to FieldValue.serverTimestamp())
            }
            val operations = mutableListOf<(WriteBatch) -> Unit>()
            remote.documents.filter { it.id != V2_DOCUMENT }.forEach { doc ->
                if (!desired.containsKey(doc.id)) operations += { batch -> batch.delete(doc.reference) }
            }
            desired.forEach { (id, data) -> operations += { batch -> batch.set(collection.document(id), data) } }
            commitOperations(db, operations, 0) { ok, error ->
                if (ok) onDone(true, "${desired.size} pointage(s) sauvegardé(s)") else onDone(false, error ?: "Échec de la sauvegarde")
            }
        }.addOnFailureListener { onDone(false, it.localizedMessage ?: "Firestore refuse la lecture") }
    }

    fun restoreAll(context: Context, onDone: (Boolean, String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
            ?: return onDone(false, "Aucun compte Google connecté")
        val db = FirebaseFirestore.getInstance()
        val collection = db.collection("users").document(user.uid).collection("pointages")

        if (HoraTrackV2.ENABLED) {
            collection.document(V2_DOCUMENT).get()
                .addOnSuccessListener { doc ->
                    val payload = doc.getString("payload")
                    if (!payload.isNullOrBlank()) {
                        val result = V2BackupManager.restoreFromJson(context.applicationContext, payload)
                        if (result.isSuccess) {
                            val r = result.getOrThrow()
                            onDone(true, "HoraTrack restaurée sans effacer les données locales (${r.mergedSessions} session(s) ajoutée(s))")
                        } else onDone(false, "Sauvegarde HoraTrack illisible : rien n'a été remplacé")
                    } else restoreLegacyIntoV2(collection, context, onDone)
                }
                .addOnFailureListener { restoreLegacyIntoV2(collection, context, onDone) }
            return
        }

        collection.orderBy("index").get().addOnSuccessListener { snapshot ->
            val restored = JSONArray()
            for (doc in snapshot.documents) {
                val payload = doc.getString("payload") ?: continue
                runCatching { JSONObject(payload) }.getOrNull()?.let(restored::put)
            }
            if (snapshot.size() > 0 && restored.length() == 0) return@addOnSuccessListener onDone(false, "Sauvegarde cloud illisible : rien n'a été remplacé")
            PointageStore.save(context.applicationContext, restored)
            onDone(true, "${restored.length()} pointage(s) restauré(s)")
        }.addOnFailureListener { onDone(false, it.localizedMessage ?: "Firestore refuse la restauration") }
    }

    private fun restoreLegacyIntoV2(collection: com.google.firebase.firestore.CollectionReference, context: Context, onDone: (Boolean, String) -> Unit) {
        collection.orderBy("index").get().addOnSuccessListener { snapshot ->
            val restored = JSONArray()
            for (doc in snapshot.documents) {
                val payload = doc.getString("payload") ?: continue
                runCatching { JSONObject(payload) }.getOrNull()?.let(restored::put)
            }
            if (restored.length() == 0) {
                onDone(false, "Aucune sauvegarde HoraTrack ou historique compatible trouvé")
                return@addOnSuccessListener
            }
            val result = V2BackupManager.importLegacyPointageJson(context.applicationContext, restored)
            if (result > 0) onDone(true, "$result ancien(s) pointage(s) fusionné(s) dans HoraTrack")
            else onDone(true, "Aucun pointage supplémentaire à restaurer")
        }.addOnFailureListener { onDone(false, it.localizedMessage ?: "Firestore refuse la restauration") }
    }

    private fun commitOperations(db: FirebaseFirestore, operations: List<(WriteBatch) -> Unit>, start: Int, onDone: (Boolean, String?) -> Unit) {
        if (start >= operations.size) return onDone(true, null)
        val end = minOf(start + MAX_BATCH_OPS, operations.size)
        val batch = db.batch()
        for (i in start until end) operations[i](batch)
        batch.commit().addOnSuccessListener { commitOperations(db, operations, end, onDone) }
            .addOnFailureListener { onDone(false, it.localizedMessage ?: it.javaClass.simpleName) }
    }
}
