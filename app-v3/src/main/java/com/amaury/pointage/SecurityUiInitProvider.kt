package com.amaury.pointage

import android.app.Activity
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SecurityUiInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext as? Application ?: return true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = install(activity)
            override fun onActivityResumed(activity: Activity) = install(activity)
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        return true
    }

    private fun install(activity: Activity) {
        if (activity !is MainActivity) return
        activity.window.decorView.post {
            val panel = activity.findViewById<LinearLayout>(R.id.gpsSettingsPanel) ?: return@post
            val existing = panel.findViewWithTag<View>(TAG_SECURITY_BLOCK)
            val user = FirebaseAuth.getInstance().currentUser

            if (user == null) {
                if (existing != null) panel.removeView(existing)
                return@post
            }

            FirebaseFirestore.getInstance().collection("users").document(user.uid).get()
                .addOnSuccessListener { profile ->
                    val isOwner = profile.getBoolean("owner") == true
                    val current = panel.findViewWithTag<View>(TAG_SECURITY_BLOCK)

                    if (!isOwner) {
                        if (current != null) panel.removeView(current)
                        return@addOnSuccessListener
                    }

                    if (current != null) return@addOnSuccessListener

                    val block = LinearLayout(activity).apply {
                        tag = TAG_SECURITY_BLOCK
                        orientation = LinearLayout.VERTICAL
                        setPadding(0, dp(activity, 18), 0, 0)
                    }
                    block.addView(TextView(activity).apply {
                        text = "SÉCURITÉ DE L'APPLICATION"
                        textSize = 16f
                        setPadding(0, 0, 0, dp(activity, 10))
                    })
                    block.addView(Button(activity).apply {
                        text = "🛡 VÉRIFIER LA SÉCURITÉ"
                        isAllCaps = false
                        textSize = 14f
                        gravity = Gravity.CENTER
                        minHeight = 0
                        minimumHeight = 0
                        setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10))
                        setBackgroundResource(R.drawable.hp_panel)
                        setOnClickListener { activity.startActivity(Intent(activity, SecurityInfoActivity::class.java)) }
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    panel.addView(block)
                    AppearanceManager.apply(activity)
                }
                .addOnFailureListener {
                    val current = panel.findViewWithTag<View>(TAG_SECURITY_BLOCK)
                    if (current != null) panel.removeView(current)
                }
        }
    }

    private fun dp(activity: Activity, value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG_SECURITY_BLOCK = "security_info_block"
    }
}
