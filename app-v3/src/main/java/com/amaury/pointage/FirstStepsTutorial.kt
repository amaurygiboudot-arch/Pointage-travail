package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View

object FirstStepsTutorial {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "first_steps_v1_done"

    private data class Step(val title: String, val message: String, val tabId: Int? = null)

    private val steps = listOf(
        Step("Bienvenue dans HoraTrack 👋", "HoraTrack démarre avec la CONFIGURATION INTELLIGENTE activée. Le but est simple : tu renseignes surtout ton entreprise, puis l'application apprend progressivement le lieu de travail et les habitudes de pause. Android te demandera toujours ton accord pour les autorisations de localisation."),
        Step("1 • Le SIRET lance la configuration", "Dans l'onglet Salaire, saisis le SIRET à 14 chiffres de ton entreprise puis appuie sur RECHERCHER. HoraTrack récupère automatiquement le nom, l'adresse de l'établissement et les informations disponibles. Dès que le SIRET est reconnu, le vrai nom de l'entreprise remplace Entreprise 1 ou Entreprise 2 dans l'interface, même si le SIRET a été saisi manuellement.", R.id.tabSalary),
        Step("2 • Active la détection intelligente", "HoraTrack peut détecter progressivement ton lieu de travail. Active cette fonction seulement si tu le souhaites : Android demandera les autorisations de localisation nécessaires et HoraTrack n'enregistrera jamais un nouveau lieu de travail sans confirmation.", R.id.tabSettings),
        Step("3 • Éviter de confondre travail et domicile", "Un lieu n'est proposé comme travail qu'après une présence d'au moins 7 heures pendant 3 jours consécutifs dans la même zone. Ensuite HoraTrack demande si c'est bien ton lieu de travail. Une même zone n'est proposée que deux fois maximum pour ne pas être intrusive.", R.id.tabSettings),
        Step("4 • Entreprises déjà renseignées", "Si aucun employeur n'est encore enregistré, le premier lieu confirmé est associé au premier emplacement disponible. Si Entreprise 1 est déjà renseignée et qu'un autre employeur est confirmé, HoraTrack utilise Entreprise 2. Si les deux sont déjà remplies, aucune donnée n'est écrasée.", R.id.tabSalary),
        Step("5 • Le point GPS précis", "Quand une adresse est connue, vérifie le point GPS précis. L'adresse sert à retrouver le site, mais c'est le point placé sur la carte qui devient le centre réel de détection. Place-le sur l'entrée, le portail, l'atelier ou le parking selon le fonctionnement du site.", R.id.tabSettings),
        Step("6 • Comprends le rayon GPS", "Le rayon est la distance autour du point précis dans laquelle HoraTrack considère que tu es sur le lieu de travail. Exemple : 150 m = un cercle de 150 m autour du point choisi. Un rayon trop grand peut déclencher trop tôt ; un rayon trop petit peut rater une arrivée à cause de la précision GPS.", R.id.tabSettings),
        Step("7 • Plusieurs points pour un même site", "Un grand site peut avoir plusieurs accès. Tu peux créer plusieurs zones GPS rattachées à la même entreprise, par exemple un portail et un parking éloigné. Chaque zone garde son propre point précis et son propre rayon.", R.id.tabSettings),
        Step("8 • Les pauses peuvent être apprises", "Tu peux toujours régler manuellement Pause 1 et Pause 2 dans l'entreprise concernée. Si elles ne sont pas réglées, HoraTrack peut apprendre les pauses réellement pointées lorsqu'elles reviennent régulièrement à des horaires et durées proches. Un réglage manuel garde toujours la priorité.", R.id.tabSalary),
        Step("9 • Pauses de base et pauses supplémentaires", "Les pauses de base apprises ou réglées sont automatiquement déduites du temps travaillé. Le bouton PAUSE sert aux pauses supplémentaires ou aux premières journées pendant lesquelles HoraTrack apprend encore ton rythme. Une pause automatique n'est jamais déduite deux fois.", R.id.tabToday),
        Step("10 • Alarmes de pause", "Pour Pause 1 et Pause 2, tu peux activer 🔔 SONNER AU DÉBUT et choisir le son. L'alarme sert seulement à te prévenir : elle ne crée pas une pause supplémentaire dans le calcul. Elle ne sonne que lorsqu'une journée est réellement en cours pour l'entreprise concernée.", R.id.tabSalary),
        Step("11 • Vérifie tes heures", "L'onglet Historique permet de contrôler chaque journée. En cas d'oubli, la saisie manuelle permet de corriger les heures ; la pause de base de l'entreprise choisie est également prise en compte.", R.id.tabHistory),
        Step("12 • Analyses et PDF", "Dans Analyses, tu peux contrôler tes totaux et générer tes récapitulatifs PDF pour les comparer avec ta fiche de paie.", R.id.tabAnalytics),
        Step("C'est prêt ✓", "Au quotidien, l'objectif est que tu aies le moins possible à régler : SIRET, autorisation GPS si tu actives la détection intelligente, puis HoraTrack apprend. Le nom réel de chaque entreprise est utilisé dès qu'il est connu.", R.id.tabToday)
    )

    fun showIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        activity.window.decorView.postDelayed({ show(activity, 0) }, 450L)
    }

    fun restart(activity: Activity) { show(activity, 0) }

    private fun show(activity: Activity, index: Int) {
        if (activity.isFinishing || activity.isDestroyed || index !in steps.indices) return
        val step = steps[index]
        step.tabId?.let { id -> activity.findViewById<View>(id)?.performClick() }
        val title = CompanyNameUiBinder.replaceCompanyLabels(step.title, activity)
        val message = CompanyNameUiBinder.replaceCompanyLabels(step.message, activity)
        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage("Étape ${index + 1}/${steps.size}\n\n$message")
            .setNegativeButton(if (index == 0) "PASSER" else "PRÉCÉDENT", null)
            .setPositiveButton(if (index == steps.lastIndex) "TERMINER" else "SUIVANT", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss()
                if (index == steps.lastIndex) {
                    markDone(activity)
                    activity.findViewById<View>(R.id.tabToday)?.performClick()
                } else show(activity, index + 1)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                dialog.dismiss()
                if (index == 0) markDone(activity) else show(activity, index - 1)
            }
        }
        dialog.setOnCancelListener { markDone(activity) }
        dialog.show()
    }

    private fun markDone(activity: Activity) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply()
    }
}
