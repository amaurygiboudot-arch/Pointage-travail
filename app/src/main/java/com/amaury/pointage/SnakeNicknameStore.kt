package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Gère le surnom Snake. Aucun nom Google / e-mail / vrai nom n'est utilisé.
 * Le surnom est isolé par compte Firebase afin qu'un changement de compte sur
 * le même téléphone ne réutilise jamais le surnom du joueur précédent.
 */
object SnakeNicknameStore {
    private const val PREFS = "snake_profile"
    private const val KEY_LEGACY_LOCAL = "nickname"
    private const val KEY_GUEST_LOCAL = "nickname_guest"
    private const val KEY_ACCOUNT_PREFIX = "nickname_account_"
    private const val MIN_LEN = 3
    private const val MAX_LEN = 16

    fun current(context: Context): String? {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        return readLocal(context, localKey(uid))
    }

    fun ensure(context: Context, ready: (String) -> Unit) {
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            val guest = readLocal(context, KEY_GUEST_LOCAL) ?: migrateLegacyGuest(context)
            if (guest != null) {
                ready(guest)
                return
            }
            promptOnce(context) { nickname ->
                saveLocal(context, null, nickname)
                ready(nickname)
            }
            return
        }

        // L'ancien cache était commun à tous les comptes du téléphone : il ne doit
        // jamais être attribué automatiquement au compte actuellement connecté.
        clearLegacyLocal(context)

        val uid = user.uid
        val local = readLocal(context, localKey(uid))
        val ref = FirebaseFirestore.getInstance().collection("snake_scores").document(uid)
        ref.get()
            .addOnSuccessListener { doc ->
                val remote = doc.getString("nickname")
                    ?.trim()
                    ?.takeIf(::isValidNickname)

                when {
                    remote != null -> {
                        saveLocal(context, uid, remote)
                        ready(remote)
                    }

                    local != null -> {
                        syncRemote(context, uid, local, doc.exists()) { ready(local) }
                    }

                    else -> {
                        promptOnce(context) { nickname ->
                            saveLocal(context, uid, nickname)
                            syncRemote(context, uid, nickname, doc.exists()) { ready(nickname) }
                        }
                    }
                }
            }
            .addOnFailureListener {
                if (local != null) {
                    ready(local)
                } else {
                    promptOnce(context) { nickname ->
                        saveLocal(context, uid, nickname)
                        Toast.makeText(
                            context,
                            "Pseudo Snake enregistré sur ce téléphone. Synchronisation dès que Firebase répondra.",
                            Toast.LENGTH_LONG
                        ).show()
                        ready(nickname)
                    }
                }
            }
    }

    private fun syncRemote(
        context: Context,
        uid: String,
        nickname: String,
        removeLegacyDisplayName: Boolean,
        done: () -> Unit
    ) {
        val values = mutableMapOf<String, Any>(
            "uid" to uid,
            "nickname" to nickname
        )
        if (removeLegacyDisplayName) {
            values["displayName"] = FieldValue.delete()
        }

        FirebaseFirestore.getInstance().collection("snake_scores").document(uid)
            .set(values, SetOptions.merge())
            .addOnCompleteListener { task ->
                if (!task.isSuccessful) {
                    Toast.makeText(
                        context,
                        "Impossible de synchroniser le pseudo Snake pour le moment.",
                        Toast.LENGTH_SHORT
                    ).show()
                }
                done()
            }
    }

    private fun promptOnce(context: Context, ready: (String) -> Unit) {
        val input = EditText(context).apply {
            hint = "Ton surnom"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            filters = arrayOf(InputFilter.LengthFilter(MAX_LEN))
            isSingleLine = true
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Choisis ton surnom Snake 🐍")
            .setMessage("Il sera visible dans le classement. Ton vrai nom ne sera jamais affiché. Choisis bien : ce surnom n'est demandé qu'une fois par compte.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("VALIDER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = sanitize(input.text.toString())
                if (!isValidNickname(value)) {
                    input.error = "Entre $MIN_LEN et $MAX_LEN caractères"
                    return@setOnClickListener
                }
                dialog.dismiss()
                ready(value)
            }
        }
        dialog.show()
    }

    private fun sanitize(raw: String): String = raw
        .trim()
        .replace(Regex("\\s+"), " ")
        .filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }
        .take(MAX_LEN)

    private fun isValidNickname(value: String): Boolean = value.length in MIN_LEN..MAX_LEN

    private fun localKey(uid: String?): String =
        if (uid == null) KEY_GUEST_LOCAL else "$KEY_ACCOUNT_PREFIX$uid"

    private fun readLocal(context: Context, key: String): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)
            ?.trim()
            ?.takeIf(::isValidNickname)

    private fun saveLocal(context: Context, uid: String?, nickname: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(localKey(uid), nickname)
            .apply()
    }

    private fun migrateLegacyGuest(context: Context): String? {
        val legacy = readLocal(context, KEY_LEGACY_LOCAL) ?: return null
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LEGACY_LOCAL)
            .putString(KEY_GUEST_LOCAL, legacy)
            .apply()
        return legacy
    }

    private fun clearLegacyLocal(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_LEGACY_LOCAL)
            .apply()
    }
}
