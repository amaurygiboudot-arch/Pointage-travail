package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import kotlin.math.roundToInt
import android.widget.*

/** User-facing settings always change an implemented consumer, never an inert toggle. */
object PersonalizationSettingsV2 {
    fun open(activity: Activity) {
        fun dp(value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
        fun rowParams() = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        fun TextView.wrapLabel() {
            setSingleLine(false)
            setHorizontallyScrolling(false)
            maxLines = Int.MAX_VALUE
            ellipsize = null
            includeFontPadding = true
        }
        val owner = PersonalizationStoreV2.accountScope()
        fun sameOwner(): Boolean = owner == PersonalizationStoreV2.accountScope()
        fun show(builder: AlertDialog.Builder): AlertDialog = PersonalizationRuntimeV2.track(builder.show())
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        var buttonParent = content
        var binding = false
        val refreshControls = mutableListOf<() -> Unit>()
        fun syncControls() { binding = true; try { refreshControls.forEach { it() } } finally { binding = false } }
        var profile = PersonalizationStoreV2.read(activity)
        fun saved(ok: Boolean) {
            profile = PersonalizationStoreV2.read(activity)
            syncControls()
            if (ok) PersonalizationRuntimeV2.refresh()
            else Toast.makeText(activity, "Réglages non enregistrés. Si le profil est illisible, importe un profil valide ou réinitialise explicitement le confort.", Toast.LENGTH_LONG).show()
        }
        fun update(change: (PersonalizationProfileV2) -> PersonalizationProfileV2) {
            if (sameOwner()) saved(PersonalizationStoreV2.update(activity, owner, change))
        }
        fun replace(next: PersonalizationProfileV2) {
            if (sameOwner()) saved(PersonalizationStoreV2.save(activity, next, replaceUnreadable = true))
        }
        fun button(label: String, action: () -> Unit): Button {
            val control = Button(activity).apply {
                text = label; isAllCaps = false
                wrapLabel()
                minHeight = dp(48)
                minimumHeight = dp(48)
                minWidth = 0
                minimumWidth = 0
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = rowParams()
                setOnClickListener { if (sameOwner()) action() }
            }
            buttonParent.addView(control)
            return control
        }
        content.addView(TextView(activity).apply {
            text = "Confort visuel de ce compte sur cet appareil. La taille système est conservée. Appui long sur un texte pour ouvrir la lecture agrandie ; le lecteur d’écran dispose aussi de cette action."
        })
        val sizeButton = button("Taille du texte : ${(profile.textScale * 100).toInt()} %") {
            val values = floatArrayOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)
            show(AlertDialog.Builder(activity).setTitle("Taille supplémentaire du texte")
                .setItems(values.map { "${(it * 100).toInt()} %" }.toTypedArray()) { _, index ->
                    update { it.copy(textScale = values[index]) }
                })
        }
        refreshControls.add { sizeButton.text = "Taille du texte : ${(profile.textScale * 100).toInt()} %" }
        fun toggle(label: String, value: () -> Boolean, change: (PersonalizationProfileV2, Boolean) -> PersonalizationProfileV2) {
            val control = Switch(activity).apply {
                text = label; isChecked = value()
                wrapLabel()
                minHeight = dp(48)
                minimumHeight = dp(48)
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                switchPadding = dp(12)
                setPadding(dp(12), dp(10), dp(12), dp(10))
                layoutParams = rowParams()
                setOnCheckedChangeListener { _, checked -> if (!binding) update { current -> change(current, checked) } }
            }
            content.addView(control)
            refreshControls.add { control.isChecked = value() }
        }
        toggle("Contraste renforcé des textes", { profile.highContrast }) { current, checked -> current.copy(highContrast = checked) }
        toggle("Réduire les mouvements du ciel", { profile.effectiveReduceMotion }) { current, checked ->
            current.copy(reduceMotion = checked, context = if (current.context == "economy") "normal" else current.context)
        }
        toggle("Outils d’aide à l’écriture", { profile.writingAssistance }) { current, checked -> current.copy(writingAssistance = checked) }
        fun hour(minute: Int) = String.format(java.util.Locale.ROOT, "%02d:%02d", minute / 60, minute % 60)
        val legacyButton = button("Annuler l’ancien contexte visuel") {
            update { it.copy(context = "normal") }
        }
        val legacyDescription = TextView(activity)
        content.addView(legacyDescription)
        refreshControls.add {
            val legacy = profile.context != "normal"
            legacyButton.visibility = if (legacy) android.view.View.VISIBLE else android.view.View.GONE
            legacyDescription.visibility = legacyButton.visibility
            legacyDescription.text = when (profile.context) {
                "night" -> "Un ancien profil force le mode sombre. Annule ce contexte pour retrouver le mode choisi dans Apparence."
                "economy" -> "Un ancien profil réduit les mouvements. L’interrupteur ci-dessus permet de les réactiver."
                else -> "Cet ancien contexte n’apporte aucun réglage visuel. Tu peux l’annuler sans changer tes autres préférences."
            }
        }
        val transferContent = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(16))
        }
        button("Sauvegarder ou transférer ces réglages") {
            (transferContent.parent as? ViewGroup)?.removeView(transferContent)
            show(AlertDialog.Builder(activity).setTitle("Sauvegarde et transfert")
                .setView(ScrollView(activity).apply { addView(transferContent) })
                .setPositiveButton("Fermer", null))
        }
        buttonParent = transferContent
        button("Exporter le profil de confort Android") {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, PersonalizationStoreV2.read(activity).encode())
                putExtra(Intent.EXTRA_SUBJECT, "AGKGMG — confort visuel")
            }, "Exporter le confort visuel"))
        }
        transferContent.addView(TextView(activity).apply {
            text = "Le transfert Android/iOS partage le contraste, la réduction des mouvements, le zoom de lecture et la programmation facultative du mode nuit. Pour recevoir les nouveaux transferts, mets à jour l’application sur l’autre appareil. Copie le texte partagé puis colle-le sur l’autre appareil."
        })
        button("Confort du compte sur mes appareils") {
            ComfortCloudSettingsV2.open(activity) {
                if (sameOwner()) { profile = PersonalizationStoreV2.read(activity); syncControls() }
            }
        }
        button("Partager le confort Android/iOS") {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, ComfortTransferV2.from(PersonalizationStoreV2.read(activity)).encode())
                putExtra(Intent.EXTRA_SUBJECT, "AGKGMG — confort Android/iOS")
            }, "Partager le confort Android/iOS"))
        }
        button("Coller le confort Android/iOS") {
            val input = EditText(activity).apply {
                hint = "Coller le texte partagé Android/iOS"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                maxLines = 8
                filters = arrayOf(android.text.InputFilter.LengthFilter(4097))
                UniversalWritingInstaller.exclude(this)
            }
            val dialog = AlertDialog.Builder(activity).setTitle("Confort Android/iOS").setView(input)
                .setPositiveButton("Vérifier", null).setNegativeButton("Annuler", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!sameOwner()) { dialog.dismiss(); return@setOnClickListener }
                    val result = runCatching { ComfortTransferV2.decode(input.text.toString()) }
                    if (result.isFailure) { input.error = result.exceptionOrNull()?.message ?: "Profil invalide" }
                    else {
                        val patch = result.getOrThrow()
                        show(AlertDialog.Builder(activity).setTitle("Appliquer ce confort partagé ?")
                            .setMessage("${patch.preview()} Les autres réglages restent conservés. Le contexte économie peut continuer à réduire les mouvements.")
                            .setPositiveButton("Appliquer") { _, _ ->
                                if (sameOwner()) update(patch::applyTo)
                                dialog.dismiss()
                            }.setNegativeButton("Annuler", null))
                    }
                }
            }
            dialog.show()
            PersonalizationRuntimeV2.track(dialog)
        }
        button("Restaurer un profil Android") {
            val input = EditText(activity).apply {
                hint = "Coller le profil exporté"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                maxLines = 8
            }
            val dialog = AlertDialog.Builder(activity).setTitle("Importer le confort visuel").setView(input)
                .setPositiveButton("Vérifier", null).setNegativeButton("Annuler", null).create()
            dialog.setOnShowListener {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    if (!sameOwner()) { dialog.dismiss(); return@setOnClickListener }
                    val next = runCatching { PersonalizationProfileV2.decode(input.text.toString()) }
                    if (next.isFailure) input.error = next.exceptionOrNull()?.message ?: "Profil invalide"
                    else {
                        val value = next.getOrThrow()
                        show(AlertDialog.Builder(activity).setTitle("Remplacer ces réglages ?")
                            .setMessage("Texte ${(value.textScale * 100).toInt()} %, contraste ${if (value.highContrast) "renforcé" else "normal"}, zoom ${(value.readerScale * 100).toInt()} %. Mouvements réduits : ${value.reduceMotion}. Contexte : ${value.context}. Aide à l’écriture : ${value.writingAssistance}. Nuit automatique : ${value.nightScheduleEnabled}, ${hour(value.nightStartMinute)}–${hour(value.nightEndMinute)}. Ces réglages du compte courant seront remplacés.")
                            .setPositiveButton("Appliquer") { _, _ -> replace(value); dialog.dismiss() }
                            .setNegativeButton("Annuler", null))
                    }
                }
            }
            dialog.show()
            PersonalizationRuntimeV2.track(dialog)
        }
        buttonParent = content
        button("Réinitialiser uniquement le confort visuel") {
            show(AlertDialog.Builder(activity).setTitle("Réinitialiser le confort visuel ?")
                .setMessage("Les pointages, salaires, comptes et thèmes existants sont conservés. Si le profil est illisible, la réinitialisation ne pourra pas être annulée depuis cet écran. Sinon, l’annulation restera disponible.")
                .setPositiveButton("Réinitialiser") { _, _ ->
                    if (sameOwner() && PersonalizationStoreV2.reset(activity)) {
                        profile = PersonalizationStoreV2.read(activity); syncControls(); PersonalizationRuntimeV2.refresh()
                    }
                }.setNegativeButton("Annuler", null))
        }
        button("Annuler la dernière réinitialisation") {
            val ok = sameOwner() && PersonalizationStoreV2.undoReset(activity)
            if (ok) { profile = PersonalizationStoreV2.read(activity); syncControls(); PersonalizationRuntimeV2.refresh() }
            Toast.makeText(activity, if (ok) "Confort restauré" else "Aucune restauration disponible", Toast.LENGTH_SHORT).show()
        }
        syncControls()
        show(AlertDialog.Builder(activity).setTitle("Confort visuel et écriture")
            .setView(ScrollView(activity).apply { addView(content) }).setPositiveButton("Fermer", null))
    }
}
