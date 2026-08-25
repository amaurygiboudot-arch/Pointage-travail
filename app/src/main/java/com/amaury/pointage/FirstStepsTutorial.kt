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
        Step("1 • Le SIRET lance la configuration", "Dans l'onglet Salaire, saisis le SIRET à 14 chiffres de ton entreprise puis appuie sur RECHERCHER. HoraTrack récupère automatiquement le nom, l'adresse de l'établissement et les informations disponibles. Cette adresse devient aussi le premier point de départ du réglage GPS intelligent.", R.id.tabSalary),
        Step("2 • Le lieu de travail est préparé automatiquement", "Si l'adresse de l'établissement est trouvée, HoraTrack crée automatiquement une première zone GPS autour de ce lieu avec un rayon de départ de 150 m et l'associe à la bonne entreprise. Tu peux ensuite vérifier ou déplacer le point précis si l'entrée, l'atelier ou le parking est ailleurs sur le site.", R.id.tabSettings),
        Step("3 • Autorise la localisation", "Android doit autoriser la localisation précise et, pour le pointage automatique en arrière-plan, la localisation en permanence. HoraTrack ne peut pas contourner cette autorisation. Une fois accordée, la zone créée à partir du SIRET peut détecter automatiquement ton arrivée et ton départ.", R.id.tabSettings),
        Step("4 • Comprends le rayon GPS", "Le rayon est la distance autour du point précis dans laquelle HoraTrack considère que tu es sur le lieu de travail. Exemple : 150 m = un cercle de 150 m autour du point choisi. Si le site est petit, réduis-le ; s'il est grand, augmente-le raisonnablement. Un rayon trop grand peut déclencher trop tôt, un rayon trop petit peut rater une arrivée.", R.id.tabSettings),
        Step("5 • Plusieurs points pour un même site", "Un grand site peut avoir plusieurs accès. Tu peux créer plusieurs zones GPS rattachées à la même entreprise, par exemple un portail et un parking éloigné. Chaque zone garde son propre point précis et son propre rayon.", R.id.tabSettings),
        Step("6 • Les pauses peuvent être apprises", "Tu peux toujours régler manuellement Pause 1 et Pause 2 dans Entreprise. Mais si tu ne les règles pas, HoraTrack observe les pauses réellement pointées. Lorsqu'une pause revient au moins plusieurs fois à une heure et avec une durée proches, elle peut devenir automatiquement une pause de base pour cette entreprise. Un réglage manuel garde toujours la priorité.", R.id.tabSalary),
        Step("7 • Pauses de base et pauses supplémentaires", "Les pauses de base apprises ou réglées dans Entreprise sont automatiquement déduites du temps travaillé. Le bouton PAUSE sert aux pauses supplémentaires ou aux premières journées pendant lesquelles HoraTrack apprend encore ton rythme. Une pause automatique n'est jamais déduite deux fois.", R.id.tabToday),
        Step("8 • Alarmes de pause", "Pour Pause 1 et Pause 2, tu peux activer 🔔 SONNER AU DÉBUT et choisir le son. L'alarme sert seulement à te prévenir : elle ne crée pas une pause supplémentaire dans le calcul. Elle ne sonne que lorsqu'une journée est réellement en cours pour l'entreprise concernée.", R.id.tabSalary),
        Step("9 • Vérifie tes heures", "L'onglet Historique permet de contrôler chaque journée. En cas d'oubli, la saisie manuelle permet de corriger les heures ; la pause de base de l'entreprise choisie est également prise en compte.", R.id.tabHistory),
        Step("10 • Analyses et PDF", "Dans Analyses, tu peux contrôler tes totaux et générer tes récapitulatifs PDF pour les comparer avec ta fiche de paie.", R.id.tabAnalytics),
        Step("C'est prêt ✓", "Au quotidien, l'objectif est que tu aies le moins possible à régler : SIRET, autorisation GPS, puis HoraTrack apprend. Tu peux toujours reprendre la main dans Entreprise pour modifier les pauses et dans Paramètres pour déplacer un point GPS ou changer son rayon.", R.id.tabToday)
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
        val dialog = AlertDialog.Builder(activity).setTitle(step.title).setMessage("Étape ${index + 1}/${steps.size}\n\n${step.message}").setNegativeButton(if (index == 0) "PASSER" else "PRÉCÉDENT", null).setPositiveButton(if (index == steps.lastIndex) "TERMINER" else "SUIVANT", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                dialog.dismiss(); if (index == steps.lastIndex) { markDone(activity); activity.findViewById<View>(R.id.tabToday)?.performClick() } else show(activity, index + 1)
            }
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss(); if (index == 0) markDone(activity) else show(activity, index - 1) }
        }
        dialog.setOnCancelListener { markDone(activity) }
        dialog.show()
    }

    private fun markDone(activity: Activity) { activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_DONE, true).apply() }
}
