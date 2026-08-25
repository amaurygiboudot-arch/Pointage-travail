package com.amaury.pointage

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View

/** Petit parcours guidé affiché une seule fois au premier lancement.
 * Il déplace l'utilisateur vers l'onglet concerné avant chaque explication.
 */
object FirstStepsTutorial {
    private const val PREFS = "onboarding"
    private const val KEY_DONE = "first_steps_v1_done"

    private data class Step(
        val title: String,
        val message: String,
        val tabId: Int? = null
    )

    private val steps = listOf(
        Step(
            "Bienvenue dans HoraTrack 👋",
            "On va préparer l'application en quelques étapes. Tu peux quitter ce guide à tout moment et le pointage continuera de fonctionner normalement."
        ),
        Step(
            "1 • Enregistre ton entreprise",
            "Commence dans l'onglet Salaire. Dans MES ENTREPRISES, saisis le numéro SIRET à 14 chiffres de ton entreprise puis appuie sur RECHERCHER. L'application récupère les informations de l'entreprise et sa convention quand elles sont disponibles.",
            R.id.tabSalary
        ),
        Step(
            "2 • Règle tes pauses habituelles",
            "Toujours dans Entreprise, règle PAUSE 1 et, si besoin, PAUSE 2. Ce sont tes pauses de base : elles seront automatiquement déduites tous les jours pour cette entreprise. Tu n'as pas besoin de les repointer chaque jour.",
            R.id.tabSalary
        ),
        Step(
            "3 • Pointe ta journée",
            "Dans Aujourd'hui : ENTRÉE démarre ta journée, PAUSE sert uniquement aux pauses supplémentaires et SORTIE termine la journée. Les pauses de base de l'entreprise restent ajoutées automatiquement au calcul.",
            R.id.tabToday
        ),
        Step(
            "4 • Vérifie tes heures",
            "L'onglet Historique te permet de retrouver tes journées et tes heures calculées. Utilise la saisie manuelle pour corriger ou ajouter une journée oubliée : la pause de base de l'entreprise choisie sera également prise en compte.",
            R.id.tabHistory
        ),
        Step(
            "5 • Analyses et PDF",
            "Dans Analyses, tu peux contrôler tes totaux et générer tes récapitulatifs PDF. C'est pratique pour comparer tes heures avec ta fiche de paie.",
            R.id.tabAnalytics
        ),
        Step(
            "6 • Ajoute ton lieu GPS",
            "Dans Paramètres, appuie sur AJOUTER UNE ADRESSE. Associe le lieu à l'Entreprise 1 ou 2, donne-lui un nom puis saisis son adresse. HoraTrack place d'abord un point GPS à partir de l'adresse. Ensuite, vérifie toujours le POINT GPS PRÉCIS sur la carte : l'adresse postale sert à trouver le lieu, mais c'est le point placé sur la carte qui devient le vrai centre de détection.",
            R.id.tabSettings
        ),
        Step(
            "7 • Place le point précis",
            "Le repère doit être placé là où tu veux réellement que la zone soit centrée : entrée du bâtiment, atelier, portail ou parking. Tu peux déplacer la carte ou le repère. Le bouton MA POSITION peut aussi t'aider si tu es physiquement sur place. Déplacer ce point ne change pas l'adresse enregistrée.",
            R.id.tabSettings
        ),
        Step(
            "8 • Comprends le rayon GPS",
            "Le rayon est la distance autour du point précis dans laquelle HoraTrack considère que tu es sur le lieu de travail. Exemple : avec 150 m, l'application utilise un cercle de 150 m autour du point choisi. Un rayon trop petit peut rater une arrivée à cause de la précision GPS ; un rayon trop grand peut déclencher le pointage alors que tu es seulement à proximité. Ajuste-le selon la taille réelle du site.",
            R.id.tabSettings
        ),
        Step(
            "9 • Plusieurs zones pour un grand site",
            "Un même site peut nécessiter plusieurs points GPS si l'adresse couvre une grande surface ou plusieurs accès. Le principe est de créer plusieurs zones rattachées au même lieu, chacune avec son propre point précis et son rayon. Cela permet par exemple de couvrir un portail d'entrée et un parking éloigné sans mettre un rayon énorme autour d'un seul point.",
            R.id.tabSettings
        ),
        Step(
            "10 • Active le pointage automatique",
            "Quand les points et les rayons sont corrects, active le pointage automatique GPS et autorise la localisation demandée par Android. Le GPS automatique reste facultatif : les boutons ENTRÉE, PAUSE et SORTIE fonctionnent toujours manuellement.",
            R.id.tabSettings
        ),
        Step(
            "C'est prêt ✓",
            "Le plus important : renseigne d'abord ton entreprise et tes pauses de base. Pour le GPS, retiens que l'adresse sert à identifier le lieu, le point précis définit le centre réel et le rayon définit jusqu'où la zone s'étend. Ensuite, au quotidien, HoraTrack peut suivre tes heures automatiquement ou avec les boutons de pointage.",
            R.id.tabToday
        )
    )

    fun showIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DONE, false)) return
        activity.window.decorView.postDelayed({ show(activity, 0) }, 450L)
    }

    fun restart(activity: Activity) {
        show(activity, 0)
    }

    private fun show(activity: Activity, index: Int) {
        if (activity.isFinishing || activity.isDestroyed || index !in steps.indices) return
        val step = steps[index]
        step.tabId?.let { id -> activity.findViewById<View>(id)?.performClick() }

        val progress = "Étape ${index + 1}/${steps.size}"
        val dialog = AlertDialog.Builder(activity)
            .setTitle(step.title)
            .setMessage("$progress\n\n${step.message}")
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
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_DONE, true).apply()
    }
}
