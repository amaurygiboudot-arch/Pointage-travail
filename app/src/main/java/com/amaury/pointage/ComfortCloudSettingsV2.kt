package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/** Opt-in foreground snapshot exchange. No background traffic or automatic overwrite. */
object ComfortCloudSettingsV2 {
    fun open(activity: Activity, onRestored: () -> Unit) {
        val uid = runCatching { FirebaseAuth.getInstance().currentUser?.uid }.getOrNull()
        if (uid == null) {
            PersonalizationRuntimeV2.track(AlertDialog.Builder(activity).setTitle("Confort du compte")
                .setMessage("Connecte-toi à ton compte pour retrouver le confort sur un autre appareil.")
                .setPositiveButton("Fermer", null).show())
            return
        }
        val owner = PersonalizationStoreV2.accountScope()
        val operationAllowed = java.util.concurrent.atomic.AtomicBoolean(true)
        val box = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 16, 24, 16) }
        val status = TextView(activity)
        box.addView(TextView(activity).apply {
            text = "Sauvegarde volontaire de trois réglages : contraste, mouvements réduits et zoom de lecture. Utilise le même compte sur Android ou iOS. Les autres réglages et tes données de travail ne sont pas transmis ici. Aucune synchronisation automatique."
        })
        box.addView(status)
        val buttons = mutableListOf<Button>()
        var busy = false
        var loaded: ComfortCloudSnapshotV2? = null
        lateinit var dialog: AlertDialog
        fun active() = operationAllowed.get() && !activity.isDestroyed && dialog.isShowing && owner == PersonalizationStoreV2.accountScope()
            && FirebaseAuth.getInstance().currentUser?.uid == uid
        fun describe(value: ComfortTransferV2) = "Contraste renforcé : ${if (value.highContrast) "oui" else "non"}. Mouvements réduits : ${if (value.reduceMotion) "oui" else "non"}. Zoom : ${(value.readerScale * 100).toInt()} %."
        fun display() {
            val value = loaded
            status.text = when {
                value == null -> "Lis la sauvegarde du compte avant d’enregistrer ou de restaurer."
                value.revision == 0L -> "Aucune sauvegarde sur ce compte."
                value.comfort == null -> "Sauvegarde supprimée (révision ${value.revision})."
                else -> "Révision ${value.revision}. ${describe(value.comfort)}"
            }
        }
        fun setBusy(value: Boolean) { busy = value; buttons.forEach { it.isEnabled = !value } }
        val reference = FirebaseFirestore.getInstance().collection("users").document(uid)
            .collection("app_backup").document("comfort_shared_v1")
        fun parse(data: Map<String, Any>?): ComfortCloudSnapshotV2 {
            if (data != null) require(data["updatedAt"] is Timestamp) { "Date de sauvegarde invalide" }
            return ComfortCloudSnapshotV2.decode(data)
        }
        fun write(value: ComfortTransferV2?, expected: Long) {
            if (!active() || busy) return
            setBusy(true)
            status.text = "Enregistrement sur le compte…"
            FirebaseFirestore.getInstance().runTransaction { transaction ->
                check(operationAllowed.get() && owner == PersonalizationStoreV2.accountScope() && FirebaseAuth.getInstance().currentUser?.uid == uid) { "Compte modifié" }
                val current = parse(transaction.get(reference).data)
                current.requireExpected(expected)
                val next = current.nextRevision
                check(operationAllowed.get() && owner == PersonalizationStoreV2.accountScope() && FirebaseAuth.getInstance().currentUser?.uid == uid) { "Compte modifié" }
                transaction.set(reference, mapOf("schemaVersion" to 1, "revision" to next,
                    "payload" to (value?.encode() ?: ""), "deleted" to (value == null), "updatedAt" to FieldValue.serverTimestamp()))
                next
            }.addOnSuccessListener { revision ->
                if (!active()) return@addOnSuccessListener
                loaded = ComfortCloudSnapshotV2(revision, value)
                setBusy(false); display()
            }.addOnFailureListener {
                if (!active()) return@addOnFailureListener
                loaded = null; setBusy(false)
                status.text = "Enregistrement non confirmé. Relis la sauvegarde : elle peut avoir changé sur un autre appareil, ou le réseau est indisponible. Aucun nouvel essai automatique."
            }
        }
        fun button(title: String, action: () -> Unit) {
            val control = Button(activity).apply { text = title; isAllCaps = false; setOnClickListener { if (active() && !busy) action() } }
            buttons.add(control); box.addView(control)
        }
        button("Lire la sauvegarde du compte") {
            setBusy(true); status.text = "Lecture sur le serveur…"
            reference.get(Source.SERVER).addOnSuccessListener { document ->
                if (!active()) return@addOnSuccessListener
                setBusy(false)
                val result = runCatching { parse(document.data) }
                if (result.isSuccess) { loaded = result.getOrThrow(); display() }
                else { loaded = null; status.text = "Sauvegarde incompatible : aucun réglage n’a été remplacé." }
            }.addOnFailureListener {
                if (!active()) return@addOnFailureListener
                setBusy(false); loaded = null
                status.text = "Lecture impossible. Vérifie la connexion ; aucun réglage n’a été remplacé."
            }
        }
        button("Sauvegarder ce confort sur le compte") {
            val current = loaded
            if (current == null) { display(); return@button }
            val proposed = ComfortTransferV2.from(PersonalizationStoreV2.read(activity))
            PersonalizationRuntimeV2.track(AlertDialog.Builder(activity).setTitle("Sauvegarder ces trois réglages ?")
                .setMessage("${describe(proposed)} Ils remplaceront la sauvegarde du compte à la révision ${current.revision}.")
                .setPositiveButton("Sauvegarder") { _, _ -> write(proposed, current.revision) }
                .setNegativeButton("Annuler", null).show())
        }
        button("Restaurer le confort affiché") {
            val current = loaded
            val value = current?.comfort
            if (value == null) { display(); return@button }
            PersonalizationRuntimeV2.track(AlertDialog.Builder(activity).setTitle("Restaurer ces trois réglages ?")
                .setMessage("Révision ${current.revision}. ${describe(value)} Les autres réglages restent conservés ; le contexte économie peut continuer à réduire les mouvements.")
                .setPositiveButton("Restaurer") { _, _ ->
                    if (!active()) return@setPositiveButton
                    val saved = PersonalizationStoreV2.save(activity, value.applyTo(PersonalizationStoreV2.read(activity)))
                    if (saved) { PersonalizationRuntimeV2.refresh(); onRestored(); status.text = "Confort affiché restauré sur cet appareil." }
                    else status.text = "Confort non enregistré sur cet appareil."
                }.setNegativeButton("Annuler", null).show())
        }
        button("Supprimer la sauvegarde du compte") {
            val current = loaded
            if (current == null || current.comfort == null) { display(); return@button }
            PersonalizationRuntimeV2.track(AlertDialog.Builder(activity).setTitle("Supprimer la sauvegarde distante ?")
                .setMessage("Les trois réglages sauvegardés seront effacés. Le confort de tes appareils reste conservé. Un numéro de révision sans préférences évite qu’un ancien appareil réécrive cette sauvegarde sans la relire.")
                .setPositiveButton("Supprimer") { _, _ -> write(null, current.revision) }
                .setNegativeButton("Annuler", null).show())
        }
        display()
        dialog = AlertDialog.Builder(activity).setTitle("Confort entre appareils")
            .setView(ScrollView(activity).apply { addView(box) }).setPositiveButton("Fermer", null).show()
        dialog.setOnDismissListener { operationAllowed.set(false) }
        PersonalizationRuntimeV2.track(dialog)
    }
}
