package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.os.Build
import android.text.InputType
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

class V2SecuritySettingsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    companion object { const val TAG = "v2_security_settings" }
    private val status = TextView(context)

    init {
        tag = TAG
        orientation = VERTICAL
        setPadding(0, dp(14), 0, dp(4))
        addView(TextView(context).apply { text = "VERROUILLAGE HORATRACK"; textSize = 15f })
        addView(status.apply { textSize = 12f; setPadding(0, dp(4), 0, dp(6)) })
        addView(button("🔢 CONFIGURER LE PIN") { configurePin() })
        addView(button("👆 ACTIVER / DÉSACTIVER LA BIOMÉTRIE") {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
                Toast.makeText(context, "Biométrie disponible à partir d'Android 9", Toast.LENGTH_LONG).show()
            } else {
                V2AppLock.setBiometricEnabled(context, !V2AppLock.biometricEnabled(context))
                refresh()
            }
        })
        addView(button("⏱ DÉLAI DE VERROUILLAGE") { chooseTimeout() })
        addView(button("🔒 ACTIVER / DÉSACTIVER LE VERROUILLAGE") {
            val target = !V2AppLock.isEnabled(context)
            if (!V2AppLock.setEnabled(context, target)) {
                Toast.makeText(context, "Configure d'abord un PIN ou active la biométrie", Toast.LENGTH_LONG).show()
            }
            refresh()
        })
        refresh()
    }

    fun refresh() {
        status.text = buildString {
            append(if (V2AppLock.isEnabled(context)) "🔒 Actif" else "🔓 Désactivé")
            append(" • PIN ").append(if (V2AppLock.hasPin(context)) "configuré" else "absent")
            append(" • biométrie ").append(if (V2AppLock.biometricEnabled(context)) "active" else "inactive")
            append(" • ").append(V2AppLock.timeoutMinutes(context)).append(" min")
        }
    }

    private fun configurePin() {
        val first = EditText(context).apply {
            hint = "Nouveau PIN (4 à 8 chiffres)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val second = EditText(context).apply {
            hint = "Confirmer le PIN"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val box = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(18), dp(8), dp(18), 0)
            addView(first)
            addView(second)
        }
        val dialog = AlertDialog.Builder(context)
            .setTitle("Configurer le PIN")
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val a = first.text.toString()
                val b = second.text.toString()
                when {
                    a != b -> second.error = "Les deux PIN sont différents"
                    !V2AppLock.setPin(context, a) -> first.error = "4 à 8 chiffres requis"
                    else -> {
                        Toast.makeText(context, "PIN enregistré", Toast.LENGTH_SHORT).show()
                        refresh()
                        dialog.dismiss()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun chooseTimeout() {
        val values = intArrayOf(1, 5, 15, 30, 60)
        val labels = values.map { "$it minute${if (it > 1) "s" else ""}" }.toTypedArray()
        val selected = values.indexOf(V2AppLock.timeoutMinutes(context)).coerceAtLeast(1)
        AlertDialog.Builder(context)
            .setTitle("Verrouiller après")
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                V2AppLock.setTimeoutMinutes(context, values[which])
                refresh()
                dialog.dismiss()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setBackgroundResource(R.drawable.hp_panel)
        setOnClickListener { action() }
        layoutParams = LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(5) }
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
