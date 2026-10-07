package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.text.InputType
import android.view.ViewGroup
import android.widget.*

/** User-facing settings always change an implemented consumer, never an inert toggle. */
object PersonalizationSettingsV2 {
    fun open(activity: Activity) {
        val owner = PersonalizationStoreV2.accountScope()
        fun sameOwner(): Boolean = owner == PersonalizationStoreV2.accountScope()
        fun show(builder: AlertDialog.Builder): AlertDialog = PersonalizationRuntimeV2.track(builder.show())
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 16)
        }
        var binding = false
        val refreshControls = mutableListOf<() -> Unit>()
        fun syncControls() { binding = true; try { refreshControls.forEach { it() } } finally { binding = false } }
        var profile = PersonalizationStoreV2.read(activity)
        fun save(next: PersonalizationProfileV2) {
            if (!sameOwner()) return
            if (PersonalizationStoreV2.save(activity, next)) {
                profile = next
                syncControls()
                PersonalizationRuntimeV2.refresh()
            } else Toast.makeText(activity, "Réglages non enregistrés", Toast.LENGTH_LONG).show()
        }
        fun button(label: String, action: () -> Unit): Button {
            val control = Button(activity).apply {
                text = label; isAllCaps = false
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                setOnClickListener { if (sameOwner()) action() }
            }
            content.addView(control)
            return control
        }
        content.addView(TextView(activity).apply {
            text = "Confort visuel de ce compte sur cet appareil. La taille système est conservée. Appui long sur un texte pour ouvrir la lecture agrandie ; le lecteur d’écran dispose aussi de cette action."
        })
        val sizeButton = button("Taille du texte : ${(profile.textScale * 100).toInt()} %") {
            val values = floatArrayOf(1f, 1.15f, 1.3f, 1.5f, 1.75f, 2f)
            show(AlertDialog.Builder(activity).setTitle("Taille supplémentaire du texte")
                .setItems(values.map { "${(it * 100).toInt()} %" }.toTypedArray()) { _, index ->
                    save(profile.copy(textScale = values[index]))
                    Toast.makeText(activity, "Taille : ${(values[index] * 100).toInt()} %", Toast.LENGTH_SHORT).show()
                })
        }
        refreshControls.add { sizeButton.text = "Taille du texte : ${(profile.textScale * 100).toInt()} %" }
        fun toggle(label: String, value: () -> Boolean, update: (Boolean) -> PersonalizationProfileV2) {
            val control = Switch(activity).apply {
                text = label; isChecked = value()
                setOnCheckedChangeListener { _, checked -> if (!binding) save(update(checked)) }
            }
            content.addView(control)
            refreshControls.add { control.isChecked = value() }
        }
        toggle("Contraste renforcé des textes", { profile.highContrast }) { profile.copy(highContrast = it) }
        toggle("Réduire les mouvements du ciel", { profile.reduceMotion }) { profile.copy(reduceMotion = it) }
        toggle("Outils d’aide à l’écriture", { profile.writingAssistance }) { profile.copy(writingAssistance = it) }
        button("Contexte visuel") {
            val names = arrayOf("Normal", "Travail", "Maison", "Nuit", "Économie d’énergie")
            val keys = arrayOf("normal", "work", "home", "night", "economy")
            show(AlertDialog.Builder(activity).setTitle("Contexte manuel")
                .setSingleChoiceItems(names, keys.indexOf(profile.context)) { dialog, index ->
                    save(profile.copy(context = keys[index])); dialog.dismiss()
                }.setNegativeButton("Fermer", null))
        }
        content.addView(TextView(activity).apply {
            text = "Nuit force la palette sombre ; économie réduit les effets célestes. Normal, travail et maison conservent vos réglages. Les règles de pointage et de salaire restent indépendantes."
        })
        button("Exporter ces réglages") {
            activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, PersonalizationStoreV2.read(activity).encode())
                putExtra(Intent.EXTRA_SUBJECT, "AGKGMG — confort visuel")
            }, "Exporter le confort visuel"))
        }
        content.addView(TextView(activity).apply {
            text = "Le transfert Android/iOS partage uniquement le contraste, la réduction des mouvements et le zoom de lecture. Copie le texte partagé puis colle-le sur l’autre appareil."
        })
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
                            .setMessage("Contraste renforcé : ${if (patch.highContrast) "oui" else "non"}. Mouvements réduits : ${if (patch.reduceMotion) "oui" else "non"}. Zoom de lecture : ${(patch.readerScale * 100).toInt()} %. Les autres réglages restent conservés. Le contexte économie peut continuer à réduire les mouvements.")
                            .setPositiveButton("Appliquer") { _, _ ->
                                if (sameOwner()) save(patch.applyTo(PersonalizationStoreV2.read(activity)))
                                dialog.dismiss()
                            }.setNegativeButton("Annuler", null))
                    }
                }
            }
            dialog.show()
            PersonalizationRuntimeV2.track(dialog)
        }
        button("Importer des réglages") {
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
                            .setMessage("Texte ${(value.textScale * 100).toInt()} %, contraste ${if (value.highContrast) "renforcé" else "normal"}, zoom ${(value.readerScale * 100).toInt()} %. Mouvements réduits : ${value.reduceMotion}. Contexte : ${value.context}. Aide à l’écriture : ${value.writingAssistance}. Ces réglages du compte courant seront remplacés.")
                            .setPositiveButton("Appliquer") { _, _ -> save(value); dialog.dismiss() }
                            .setNegativeButton("Annuler", null))
                    }
                }
            }
            dialog.show()
            PersonalizationRuntimeV2.track(dialog)
        }
        button("Réinitialiser uniquement le confort visuel") {
            show(AlertDialog.Builder(activity).setTitle("Réinitialiser le confort visuel ?")
                .setMessage("Les pointages, salaires, comptes et thèmes existants sont conservés. Cette réinitialisation peut être annulée.")
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
        show(AlertDialog.Builder(activity).setTitle("Confort visuel et écriture")
            .setView(ScrollView(activity).apply { addView(content) }).setPositiveButton("Fermer", null))
    }
}
