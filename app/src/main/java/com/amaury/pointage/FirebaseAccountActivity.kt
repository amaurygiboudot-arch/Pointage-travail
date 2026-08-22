package com.amaury.pointage

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
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

class FirebaseAccountActivity : Activity() {

    companion object {
        private const val RC_GOOGLE_SIGN_IN = 4101
    }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var statusText: TextView
    private lateinit var signInButton: Button
    private lateinit var signOutButton: Button

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
    }

    private fun palette(): Triple<Int, Int, Int> {
        val theme = AppThemeCatalog.current(this)
        val dark = AppThemeCatalog.useDarkPalette(this)
        val backgroundColor = if (dark) theme.darkPanel else theme.lightPanel
        val textColor = if (dark) theme.darkText else theme.lightText
        val accentColor = if (dark) theme.accentLight else theme.accent
        return Triple(backgroundColor, textColor, accentColor)
    }

    private fun styleAsCenteredPopup() {
        val (backgroundColor, _, _) = palette()
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        val params = window.attributes
        params.gravity = Gravity.CENTER
        params.width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
        params.height = WindowManager.LayoutParams.WRAP_CONTENT
        params.dimAmount = 0.55f
        window.attributes = params
        window.decorView.setBackgroundColor(Color.TRANSPARENT)

        val root = window.decorView.findViewById<ViewGroup>(android.R.id.content)?.getChildAt(0)
        if (root != null) {
            root.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * resources.displayMetrics.density
                setColor(backgroundColor)
            }
        }
    }

    private fun buildContent(): LinearLayout {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val (backgroundColor, textColor, accentColor) = palette()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad, pad, pad)
            this.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 22f * resources.displayMetrics.density
                setColor(backgroundColor)
            }
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )

            addView(TextView(this@FirebaseAccountActivity).apply {
                text = "COMPTE GOOGLE"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(accentColor)
                setPadding(0, 20, 0, 24)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            statusText = TextView(this@FirebaseAccountActivity).apply {
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(textColor)
                setPadding(12, 16, 12, 24)
            }
            addView(statusText, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            signInButton = themedButton("SE CONNECTER AVEC GOOGLE", textColor, accentColor) {
                startGoogleSignIn()
            }
            addView(signInButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            signOutButton = themedButton("SE DÉCONNECTER", textColor, accentColor) {
                auth.signOut()
                GoogleSignIn.getClient(this@FirebaseAccountActivity, googleOptions()).signOut()
                refreshUi()
            }
            addView(signOutButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = 12
            })

            addView(themedButton("FERMER", textColor, accentColor) { finish() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = 24
                })
        }
    }

    private fun themedButton(label: String, textColor: Int, accentColor: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(textColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14f * resources.displayMetrics.density
                setColor(Color.TRANSPARENT)
                setStroke((1.2f * resources.displayMetrics.density).toInt().coerceAtLeast(1), accentColor)
            }
            setPadding(14, 12, 14, 12)
            setOnClickListener { action() }
        }

    private fun googleOptions(): GoogleSignInOptions =
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(getString(R.string.default_web_client_id))
            .requestEmail()
            .build()

    private fun startGoogleSignIn() {
        val client = GoogleSignIn.getClient(this, googleOptions())
        client.signOut().addOnCompleteListener {
            startActivityForResult(client.signInIntent, RC_GOOGLE_SIGN_IN)
        }
    }

    @Deprecated("Deprecated in Android API but kept for Google Sign-In compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != RC_GOOGLE_SIGN_IN) return

        try {
            val account = GoogleSignIn.getSignedInAccountFromIntent(data).getResult(ApiException::class.java)
            val credential = GoogleAuthProvider.getCredential(account.idToken, null)
            auth.signInWithCredential(credential)
                .addOnSuccessListener {
                    saveUserProfile()
                    DeviceRegistry.registerIfSignedIn(this, force = true)
                    refreshUi()
                    Toast.makeText(this, "Connexion Google réussie", Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { error ->
                    statusText.text = "Connexion Firebase impossible : ${error.localizedMessage ?: "erreur inconnue"}"
                }
        } catch (error: ApiException) {
            statusText.text = "Connexion Google annulée ou impossible (code ${error.statusCode})."
        }
    }

    private fun saveUserProfile() {
        val user = auth.currentUser ?: return
        val data = hashMapOf<String, Any?>(
            "uid" to user.uid,
            "displayName" to user.displayName,
            "email" to user.email,
            "photoUrl" to user.photoUrl?.toString(),
            "lastLoginAt" to FieldValue.serverTimestamp(),
            "platform" to "android"
        )

        db.collection("users").document(user.uid)
            .set(data)
            .addOnFailureListener { error ->
                Toast.makeText(
                    this,
                    "Compte connecté, mais Firestore refuse encore l'écriture : ${error.localizedMessage ?: "règles à configurer"}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }

    private fun refreshUi() {
        val user = auth.currentUser
        if (user == null) {
            statusText.text = "Aucun compte connecté.\nConnecte ton compte Google pour activer la synchronisation Firebase."
            signInButton.isEnabled = true
            signOutButton.isEnabled = false
        } else {
            val label = user.displayName ?: user.email ?: "Compte Google"
            statusText.text = "Connecté : $label\nUID Firebase : ${user.uid}\nInstallation : ${DeviceRegistry.installId(this)}"
            signInButton.isEnabled = false
            signOutButton.isEnabled = true
        }
    }
}
