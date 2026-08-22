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

class FirebaseAccountActivity : Activity() {
    companion object { private const val RC_GOOGLE_SIGN_IN = 4101 }

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
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
            width = (resources.displayMetrics.widthPixels * 0.90f).toInt()
            height = WindowManager.LayoutParams.WRAP_CONTENT
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
                text = "COMPTE GOOGLE / iOS"
                textSize = 22f
                gravity = Gravity.CENTER
                setTextColor(p.accent)
                setTypeface(typeface, Typeface.BOLD)
                letterSpacing = 0.04f
                setPadding(0, 6, 0, (22 * density).toInt())
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

            signInButton = themedButton("SE CONNECTER AVEC GOOGLE", p.text, p.accent, p.panel) { startGoogleSignIn() }
            addView(signInButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (62 * density).toInt()))

            signOutButton = themedButton("SE DÉCONNECTER", p.text, p.accent, p.panel) {
                auth.signOut()
                GoogleSignIn.getClient(this@FirebaseAccountActivity, googleOptions()).signOut()
                refreshUi()
            }
            addView(signOutButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (62 * density).toInt()).apply {
                topMargin = (12 * density).toInt()
            })

            addView(themedButton("FERMER", p.text, p.accent, p.panel) { finish() },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (62 * density).toInt()).apply {
                    topMargin = (16 * density).toInt()
                })
        }

        return ThemedBackgroundScrollView(this).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            setPadding(0, 0, 0, 0)
            addView(content)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 24f * density
                setColor(Color.TRANSPARENT)
                setStroke((2f * density).toInt().coerceAtLeast(2), p.accent)
            }
            clipToOutline = true
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }

    private fun themedButton(label:String,textColor:Int,accentColor:Int,panelColor:Int,action:()->Unit):Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 16f
            setTextColor(textColor)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 18f * resources.displayMetrics.density
                setColor(Color.argb(220, Color.red(panelColor), Color.green(panelColor), Color.blue(panelColor)))
                setStroke((1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1), accentColor)
            }
            setPadding(16,10,16,10)
            setOnClickListener { action() }
        }

    private fun googleOptions(): GoogleSignInOptions = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
        .requestIdToken(getString(R.string.default_web_client_id)).requestEmail().build()

    private fun startGoogleSignIn() {
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
                    saveUserProfile(); DeviceRegistry.registerIfSignedIn(this, force = true); refreshUi()
                    Toast.makeText(this,"Connexion Google réussie",Toast.LENGTH_SHORT).show()
                }
                .addOnFailureListener { error -> Toast.makeText(this,"Connexion Firebase impossible : ${error.localizedMessage ?: "erreur inconnue"}",Toast.LENGTH_LONG).show() }
        } catch (error:ApiException) {
            Toast.makeText(this,"Connexion Google annulée ou impossible (code ${error.statusCode}).",Toast.LENGTH_LONG).show()
        }
    }

    private fun saveUserProfile() {
        val user = auth.currentUser ?: return
        val data = hashMapOf<String,Any?>(
            "uid" to user.uid,"displayName" to user.displayName,"email" to user.email,
            "photoUrl" to user.photoUrl?.toString(),"lastLoginAt" to FieldValue.serverTimestamp(),"platform" to "android"
        )
        db.collection("users").document(user.uid).set(data,SetOptions.merge())
            .addOnFailureListener { error -> Toast.makeText(this,"Compte connecté, mais Firestore refuse encore l'écriture : ${error.localizedMessage ?: "règles à configurer"}",Toast.LENGTH_LONG).show() }
    }

    private fun refreshUi() {
        val connected = auth.currentUser != null
        signInButton.visibility = if (connected) View.GONE else View.VISIBLE
        signOutButton.visibility = if (connected) View.VISIBLE else View.GONE
    }
}
