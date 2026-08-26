package com.amaury.pointage

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.text.DateFormat
import java.util.Date

class FirebaseAccountActivity : Activity() {
    companion object {
        private const val RC_GOOGLE_SIGN_IN = 4101
        // Ce fichier de préférences est déjà exclu de AppCloudBackup.
        private const val STATUS_PREFS = "history_cloud_sync"
        private const val KEY_UI_LAST_AT = "ui_last_cloud_at_"
        private const val KEY_UI_LAST_OK = "ui_last_cloud_ok_"
        private const val KEY_UI_LAST_MESSAGE = "ui_last_cloud_message_"
        private const val KEY_UI_LAST_COUNT = "ui_last_cloud_count_"
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button
    private lateinit var backupButton: Button
    private lateinit var restoreButton: Button
    private lateinit var statusText: TextView
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()
        setContentView(buildContent())
        styleAsCenteredPopup()
        refreshUi()
        DeviceRegistry.registerIfSignedIn(this)
    }

    override fun onResume() {
        super.onResume()
        styleAsCenteredPopup()
        refreshUi()
    }

    private data class PopupPalette(val appBackground:Int,val panel:Int,val text:Int,val accent:Int)

    private fun palette(): PopupPalette {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        return PopupPalette(
            if (dark) theme.darkBackground else theme.lightBackground,
            if (dark) theme.darkPanel else theme.lightPanel,
            if (dark) theme.darkText else theme.lightText,
            if (dark) theme.accentLight else theme.accent
        )
    }

    private fun styleAsCenteredPopup() {
        val p = palette()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.statusBarColor = p.appBackground
        window.navigationBarColor = p.appBackground
        window.attributes = window.attributes.apply {
            gravity = Gravity.CENTER
            width = (resources.displayMetrics.widthPixels * 0.92f).toInt()
            height = (resources.displayMetrics.heightPixels * 0.90f).toInt()
            dimAmount = 0.62f
        }
        window.decorView.setBackgroundColor(Color.TRANSPARENT)
    }

    private fun buildContent(): ThemedBackgroundScrollView {
        val density = resources.displayMetrics.density
        val pad = (22 * density).toInt()
        val p = palette()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            addView(TextView(this@FirebaseAccountActivity).apply {
                text = "COMPTE GOOGLE & SAUVEGARDE"
                textSize = 21f
                gravity = Gravity.CENTER
                setTextColor(p.accent)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.035f
                setPadding(0, 6, 0, (12 * density).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            statusText = TextView(this@FirebaseAccountActivity).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(p.text)
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (14 * density).toInt())
            }
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            signInButton = themedButton("SE CONNECTER AVEC GOOGLE", p.text, p.accent, p.panel) { startGoogleSignIn() }
            addView(signInButton, buttonLayout(density))

            backupButton = themedButton("SAUVEGARDER TOUT MAINTENANT", p.text, p.accent, p.panel) { backupAllNow() }
            addView(backupButton, buttonLayout(density, 10))

            restoreButton = themedButton("RESTAURER TOUT MAINTENANT", p.text, p.accent, p.panel) { restoreAllNow() }
            addView(restoreButton, buttonLayout(density, 10))

            signOutButton = themedButton("SE DÉCONNECTER", p.text, p.accent, p.panel) {
                auth.signOut()
                GoogleSignIn.getClient(this@FirebaseAccountActivity, googleOptions()).signOut()
                busy = false
                refreshUi()
            }
            addView(signOutButton, buttonLayout(density, 10))

            addView(themedButton("FERMER", p.text, p.accent, p.panel) { finish() }, buttonLayout(density, 16))
        }

        return ThemedBackgroundScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = true
            setPadding(0, 0, 0, 0)
            addView(content)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(Color.TRANSPARENT)
                setStroke((2f * density).toInt().coerceAtLeast(2), p.accent)
            }
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
    }

    private fun buttonLayout(density: Float, topDp: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (62 * density).toInt()).apply {
            topMargin = (topDp * density).toInt()
        }

    private fun themedButton(label:String,textColor:Int,accentColor:Int,panelColor:Int,action:()->Unit):Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 15f
            setTextColor(textColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(Color.argb(220, Color.red(panelColor), Color.green(panelColor), Color.blue(panelColor)))
                setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1), accentColor)
            }
            setPadding(16,10,16,10)
            setOnClickListener { if (!busy) action() }
        }

    private fun googleOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()

    private fun startGoogleSignIn() {
        if (busy) return
        setBusy(true, "Connexion Google en cours…")
        val client = GoogleSignIn.getClient(this, googleOptions())
        client.signOut().addOnCompleteListener { startActivityForResult(client.signInIntent, RC_GOOGLE_SIGN_IN) }
    }

    @Deprecated("Deprecated in Android API but kept for Google Sign-In compatibility")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:android.content.Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode != RC_GOOGLE_SIGN_IN) return
        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken,null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener {
                    saveUserProfile()
                    DeviceRegistry.registerIfSignedIn(this, force = true)
                    setBusy(true, "Compte connecté. Vérification de l’historique Google…")
                    syncHistoryWithRetry(8) { ok, message ->
                        val count = PointageStore.load(this).length()
                        val finalMessage = if (ok) {
                            "$count pointage(s) disponible(s) après vérification. $message"
                        } else {
                            "Échec de la vérification de l’historique : $message"
                        }
                        persistCloudStatus(ok, finalMessage, count)
                        setBusy(false)
                        refreshUi()
                        Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { error ->
                    setBusy(false)
                    refreshUi()
                    Toast.makeText(this,"Connexion Firebase impossible : ${error.localizedMessage ?: "erreur inconnue"}",Toast.LENGTH_LONG).show()
                }
        } catch (error:ApiException) {
            setBusy(false)
            refreshUi()
            Toast.makeText(this,"Connexion Google annulée ou impossible (code ${error.statusCode}).",Toast.LENGTH_LONG).show()
        }
    }

    private fun backupAllNow() {
        if (auth.currentUser == null) {
            Toast.makeText(this, "Connecte d’abord ton compte Google.", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Sauvegarde de l’historique en cours…")
        syncHistoryWithRetry(8) { historyOk, historyMessage ->
            val count = PointageStore.load(this).length()
            if (!historyOk) {
                val message = "Historique non sauvegardé : $historyMessage"
                persistCloudStatus(false, message, count)
                setBusy(false)
                refreshUi()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@syncHistoryWithRetry
            }

            statusText.text = "Historique vérifié ($count pointage(s)). Sauvegarde des réglages et fichiers…"
            AppCloudBackup.backupNow(this) { settingsOk, settingsMessage ->
                val ok = historyOk && settingsOk
                val message = if (ok) {
                    "Sauvegarde complète réussie : $count pointage(s), réglages et fichiers utilisateur."
                } else {
                    "Historique sauvegardé ($count pointage(s)), mais sauvegarde des réglages incomplète : $settingsMessage"
                }
                persistCloudStatus(ok, message, count)
                setBusy(false)
                refreshUi()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreAllNow() {
        if (auth.currentUser == null) {
            Toast.makeText(this, "Connecte le même compte Google que celui utilisé pour la sauvegarde.", Toast.LENGTH_LONG).show()
            return
        }
        setBusy(true, "Recherche et restauration de l’historique Google…")
        syncHistoryWithRetry(8) { historyOk, historyMessage ->
            val count = PointageStore.load(this).length()
            if (!historyOk) {
                val message = "Restauration de l’historique impossible : $historyMessage"
                persistCloudStatus(false, message, count)
                setBusy(false)
                refreshUi()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                return@syncHistoryWithRetry
            }

            statusText.text = "Historique synchronisé ($count pointage(s)). Restauration des réglages et fichiers…"
            AppCloudBackup.restoreNow(this) { settingsOk, settingsMessage ->
                val noSettingsBackup = !settingsOk && settingsMessage.contains("Aucune sauvegarde", ignoreCase = true)
                val ok = historyOk && (settingsOk || noSettingsBackup)
                val message = when {
                    settingsOk -> "Restauration réussie : $count pointage(s), réglages et fichiers utilisateur récupérés."
                    noSettingsBackup -> "$count pointage(s) disponible(s). Aucune ancienne sauvegarde de réglages n’a été trouvée."
                    else -> "Historique récupéré ($count pointage(s)), mais restauration des réglages incomplète : $settingsMessage"
                }
                persistCloudStatus(ok, message, count)
                setBusy(false)
                refreshUi()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * L'initialisation automatique peut déjà avoir démarré une synchro au moment où
     * l'utilisateur ouvre cet écran. Dans ce cas HistoryCloudSync répond
     * "Synchronisation déjà en cours". On attend sa fin au lieu de présenter ce texte
     * comme un faux succès.
     */
    private fun syncHistoryWithRetry(attemptsLeft: Int, callback: (Boolean, String) -> Unit) {
        HistoryCloudSync.syncNow(this) { ok, message ->
            if (ok && message.contains("déjà en cours", ignoreCase = true) && attemptsLeft > 0) {
                window.decorView.postDelayed({ syncHistoryWithRetry(attemptsLeft - 1, callback) }, 650L)
            } else {
                callback(ok, message)
            }
        }
    }

    private fun saveUserProfile() {
        val user = auth.currentUser ?: return
        val data = hashMapOf<String,Any?>(
            "uid" to user.uid,"displayName" to user.displayName,"email" to user.email,
            "photoUrl" to user.photoUrl?.toString(),"lastLoginAt" to FieldValue.serverTimestamp(),"platform" to "android"
        )
        db.collection("users").document(user.uid).set(data,SetOptions.merge())
            .addOnFailureListener { error ->
                val message = "Compte connecté, mais Firestore refuse l’écriture du profil : ${error.localizedMessage ?: "règles à configurer"}"
                persistCloudStatus(false, message, PointageStore.load(this).length())
                refreshUi()
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
    }

    private fun persistCloudStatus(ok: Boolean, message: String, count: Int) {
        val uid = auth.currentUser?.uid ?: return
        getSharedPreferences(STATUS_PREFS, MODE_PRIVATE).edit()
            .putLong(KEY_UI_LAST_AT + uid, System.currentTimeMillis())
            .putBoolean(KEY_UI_LAST_OK + uid, ok)
            .putString(KEY_UI_LAST_MESSAGE + uid, message)
            .putInt(KEY_UI_LAST_COUNT + uid, count)
            .apply()
    }

    private fun statusLabel(): String {
        val user = auth.currentUser
            ?: return "Compte Google non connecté.\nAprès une réinstallation, reconnecte le même compte pour récupérer les sauvegardes."

        val prefs = getSharedPreferences(STATUS_PREFS, MODE_PRIVATE)
        val at = prefs.getLong(KEY_UI_LAST_AT + user.uid, 0L)
        val localCount = PointageStore.load(this).length()
        val account = user.email ?: user.displayName ?: "compte Google connecté"
        if (at <= 0L) {
            return "$account\n$localCount pointage(s) actuellement sur ce téléphone.\nAucune sauvegarde cloud n’a encore été vérifiée depuis cette installation."
        }
        val ok = prefs.getBoolean(KEY_UI_LAST_OK + user.uid, false)
        val savedCount = prefs.getInt(KEY_UI_LAST_COUNT + user.uid, 0)
        val message = prefs.getString(KEY_UI_LAST_MESSAGE + user.uid, "").orEmpty()
        val whenText = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(at))
        val state = if (ok) "✓ DERNIÈRE VÉRIFICATION RÉUSSIE" else "⚠ DERNIÈRE VÉRIFICATION EN ÉCHEC"
        return "$account\n$state — $whenText\nPointages vérifiés : $savedCount • présents ici : $localCount\n$message"
    }

    private fun setBusy(value: Boolean, message: String? = null) {
        busy = value
        if (::signInButton.isInitialized) signInButton.isEnabled = !value
        if (::signOutButton.isInitialized) signOutButton.isEnabled = !value
        if (::backupButton.isInitialized) backupButton.isEnabled = !value
        if (::restoreButton.isInitialized) restoreButton.isEnabled = !value
        if (message != null && ::statusText.isInitialized) statusText.text = message
    }

    private fun refreshUi() {
        val connected = auth.currentUser != null
        signInButton.visibility = if (connected) View.GONE else View.VISIBLE
        signOutButton.visibility = if (connected) View.VISIBLE else View.GONE
        backupButton.visibility = if (connected) View.VISIBLE else View.GONE
        restoreButton.visibility = if (connected) View.VISIBLE else View.GONE
        if (!busy) statusText.text = statusLabel()
    }
}
