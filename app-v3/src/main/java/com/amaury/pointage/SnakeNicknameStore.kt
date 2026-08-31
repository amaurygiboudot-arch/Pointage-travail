package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Gère le surnom Snake. Aucun nom Google / e-mail / vrai nom n'est utilisé.
 * Le surnom est choisi une seule fois par compte (et mémorisé localement en secours).
 */
object SnakeNicknameStore {
    private const val PREFS = "snake_profile"
    private const val KEY_LOCAL = "nickname"
    private const val MIN_LEN = 3
    private const val MAX_LEN = 16

    fun current(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LOCAL, null)
            ?.trim()
            ?.takeIf { it.length in MIN_LEN..MAX_LEN }

    fun ensure(context: Context, ready: (String) -> Unit) {
        current(context)?.let { ready(it); return }

        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            promptOnce(context, ready)
            return
        }

        FirebaseFirestore.getInstance().collection("snake_scores").document(user.uid)
            .get()
            .addOnSuccessListener { doc ->
                val remote = doc.getString("nickname")?.trim()
                if (!remote.isNullOrBlank() && remote.length in MIN_LEN..MAX_LEN) {
                    saveLocal(context, remote)
                    ready(remote)
                } else {
                    promptOnce(context) { nickname ->
                        // Le profil Snake ne reçoit que le surnom. Aucun displayName réel.
                        FirebaseFirestore.getInstance().collection("snake_scores").document(user.uid)
                            .set(mapOf("uid" to user.uid, "nickname" to nickname), com.google.firebase.firestore.SetOptions.merge())
                        ready(nickname)
                    }
                }
            }
            .addOnFailureListener { promptOnce(context, ready) }
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
            .setMessage("Il sera visible dans le classement. Ton vrai nom ne sera jamais affiché. Choisis bien : ce surnom n'est demandé qu'une fois.")
            .setView(input)
            .setNegativeButton("Annuler", null)
            .setPositiveButton("VALIDER", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = sanitize(input.text.toString())
                if (value.length !in MIN_LEN..MAX_LEN) {
                    input.error = "Entre $MIN_LEN et $MAX_LEN caractères"
                    return@setOnClickListener
                }
                saveLocal(context, value)
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

    private fun saveLocal(context: Context, nickname: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LOCAL, nickname).apply()
    }
}
