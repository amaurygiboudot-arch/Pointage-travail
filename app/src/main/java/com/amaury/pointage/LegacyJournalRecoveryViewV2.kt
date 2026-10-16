package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.util.AttributeSet
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import com.amaury.pointage.v2.V2LegacyJournalRecovery
import com.amaury.pointage.v2.V2RuntimeAccountScope
import com.google.firebase.auth.FirebaseAuth

/** Explicit ownership declaration; no journal contents are shown before recovery. */
class LegacyJournalRecoveryViewV2 @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val status = TextView(context)
    private val recover = Button(context)
    private var confirmation: AlertDialog? = null
    private var displayedUid: String? = null
    private var feedback: String? = null
    private val authListener = FirebaseAuth.AuthStateListener {
        post { refresh() }
    }

    init {
        orientation = VERTICAL
        status.setTextColor(context.getColor(R.color.hp_white))
        status.textSize = 14f
        status.accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
        addView(status, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        recover.text = "RÉCUPÉRER MON ANCIEN JOURNAL"
        recover.minHeight = (48 * resources.displayMetrics.density).toInt()
        recover.setOnClickListener { requestRecovery() }
        addView(recover, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        runCatching { FirebaseAuth.getInstance().addAuthStateListener(authListener) }
        refresh()
    }

    override fun onDetachedFromWindow() {
        runCatching { FirebaseAuth.getInstance().removeAuthStateListener(authListener) }
        dismissConfirmation()
        super.onDetachedFromWindow()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus) refresh()
    }

    private fun uid() = runCatching { FirebaseAuth.getInstance().currentUser?.uid }
        .getOrNull()?.takeIf { it.isNotBlank() }

    private fun dismissConfirmation() {
        val dialog = confirmation
        confirmation = null
        dialog?.setMessage("")
        dialog?.dismiss()
    }

    private fun refresh() {
        val currentUid = uid()
        if (currentUid != displayedUid) {
            dismissConfirmation()
            feedback = null
            displayedUid = currentUid
        }
        val unassigned = V2RuntimeAccountScope.hasUnassignedHistory(context)
        visibility = if (unassigned || feedback != null) VISIBLE else GONE
        status.text = feedback ?: if (currentUid == null) {
            "Un ancien journal non attribué est conservé. Connectez-vous à votre compte personnel pour demander sa récupération."
        } else {
            "Un ancien journal non attribué est conservé. Il reste séparé de ce compte. Si cet appareil a servi à plusieurs personnes, ne récupérez pas globalement ce journal."
        }
        recover.visibility = if (unassigned) VISIBLE else GONE
        recover.isEnabled = unassigned && currentUid != null
    }

    private fun requestRecovery() {
        val openingUid = uid() ?: return
        val preview = V2LegacyJournalRecovery.preview(context)
        if (preview == null || !preview.eligible) {
            feedback = preview?.message ?: "Récupération indisponible : vérifiez le compte et le journal conservé."
            refresh()
            return
        }
        if (uid() != openingUid || preview.scope != "uid:$openingUid") {
            refresh()
            return
        }
        val account = runCatching { FirebaseAuth.getInstance().currentUser?.email }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: openingUid
        val declaration = CheckBox(context).apply {
            text = "Je confirme que TOUS les anciens pointages de cet appareil m'appartiennent et doivent être rattachés à ce compte."
            isChecked = false
            setPadding(24, 12, 24, 12)
        }
        dismissConfirmation()
        val dialog = AlertDialog.Builder(context)
            .setTitle("Confirmer le propriétaire du journal")
            .setMessage("Compte destinataire : $account\n\nAucun contenu de l'ancien journal n'est affiché ici. Si des pointages appartiennent à une autre personne, annulez. La récupération doit conserver les données existantes ; un conflit bloque l'opération.")
            .setView(declaration)
            .setNegativeButton("ANNULER", null)
            .setPositiveButton("CONFIRMER ET RÉCUPÉRER", null)
            .create()
        confirmation = dialog
        dialog.setOnDismissListener {
            if (confirmation === dialog) confirmation = null
        }
        dialog.setOnShowListener {
            val confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            confirm.isEnabled = false
            declaration.setOnCheckedChangeListener { _, checked ->
                confirm.isEnabled = checked && uid() == openingUid
            }
            confirm.setOnClickListener {
                if (uid() != openingUid || !declaration.isChecked) {
                    dismissConfirmation()
                    refresh()
                    return@setOnClickListener
                }
                val result = V2LegacyJournalRecovery.confirm(context, preview, declaredOwnership = true)
                feedback = if (result.success) "Journal récupéré. ${result.message}" else
                    "Journal non récupéré. ${result.message}"
                dismissConfirmation()
                refresh()
            }
        }
        dialog.show()
    }
}
