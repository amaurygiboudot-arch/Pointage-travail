package com.amaury.pointage

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

object UserGuideDialog {
    fun show(context: Context) {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 24))
        }

        content.addView(title(context, "NOTICE D'UTILISATION — AGKGMG"))
        content.addView(
            body(
                context,
                "AGKGMG enregistre les faits de temps de travail, les pauses et les lieux configurés. " +
                    "Il peut ensuite les utiliser pour l'historique, les analyses, les sauvegardes et les contrôles de paie. " +
                    "Une donnée absente ou non fiable reste à confirmer : l'application ne doit pas inventer un horaire, une règle ou un montant."
            )
        )

        addSection(content, context, "POINTAGE", """
ENTRÉE
Enregistre le début réel de la période de travail.

PAUSE / REPRISE
Ouvre puis ferme une pause. Lorsqu'un statut payé ou non payé est nécessaire et n'est pas connu, AGKGMG doit demander une qualification explicite au lieu de choisir silencieusement.

SORTIE
Ferme la période de travail en cours. Une sortie manquante ou ambiguë ne doit pas être inventée.

SAISIE ET CORRECTIONS MANUELLES
Les corrections servent à décrire ce qui s'est réellement passé. Elles sont conservées comme faits explicites et ne doivent pas créer automatiquement une règle de paie.
        """.trimIndent())

        addSection(content, context, "ONGLETS", """
ACCUEIL
Affiche l'accueil et le système Céleste lorsque cette fonction est disponible.

AUJOURD'HUI
Affiche l'état du pointage et les informations de la journée en cours.

HISTORIQUE
Affiche les périodes de travail enregistrées et les informations disponibles pour les contrôler.

ANALYSES
Présente les analyses construites à partir des données suffisamment fiables et permet d'accéder aux rapports disponibles.

SALAIRE
Regroupe les entreprises, profils, contrats, conventions, bulletins et résultats de paie V2.

PARAMÈTRES
Regroupe notamment l'application, le compte et la sécurité, les lieux/GPS, Céleste, l'apparence, les widgets, la sauvegarde et l'aide.
        """.trimIndent())

        addSection(content, context, "SALAIRE V2", """
AGKGMG sépare les données de chaque entreprise et utilise un identifiant stable pour éviter de mélanger plusieurs employeurs.

Les calculs s'appuient sur les faits de temps disponibles, le profil de l'entreprise et du salarié, la période concernée et les règles suffisamment prouvées. Une règle juridique ou une donnée manquante ne doit pas devenir un faux 0 €.

Selon les informations disponibles, l'application peut présenter un résultat fiable, un écart expliqué, une anomalie potentielle ou une donnée à confirmer. Les bulletins importés restent des valeurs observées à comparer ; ils ne remplacent pas les règles applicables.

AGKGMG aide à contrôler une paie mais ne remplace ni le bulletin de paie officiel ni une vérification professionnelle lorsqu'une situation reste incertaine.
        """.trimIndent())

        addSection(content, context, "SAUVEGARDE ET SYNCHRONISATION", """
GOOGLE DRIVE
Quand un dossier Drive est configuré, la sauvegarde V2 enregistre un snapshot des données fonctionnelles prises en charge afin de permettre une restauration ultérieure.

COMPTE GOOGLE / FIREBASE
La sauvegarde déclenchée depuis le compte utilisateur est stockée dans l'espace Firestore privé de l'utilisateur connecté.

RESTAURATION
La restauration V2 est conservatrice : l'historique est fusionné lorsqu'il est compatible et une sauvegarde illisible ou incohérente doit bloquer l'opération plutôt que remplacer silencieusement les données locales.

SÉCURITÉ
Le PIN de verrouillage, les jetons d'authentification, les identifiants techniques de l'appareil et les états GPS purement temporaires ne font pas partie des préférences transférables.
        """.trimIndent())

        addSection(content, context, "POINTAGE ET LIEUX / GPS", """
Une entreprise peut utiliser plusieurs zones de travail ou de contexte. Chaque zone possède son propre identifiant et peut avoir son rayon, son type et son entreprise associée.

La présence dans une zone GPS est un indice de contexte : elle ne signifie pas automatiquement qu'une durée est du temps de travail payé. En cas d'ambiguïté pouvant changer le résultat, AGKGMG doit conserver l'incertitude ou demander une confirmation.

Sur Android, la détection automatique lorsque l'application n'est pas au premier plan dépend des autorisations de localisation accordées par l'utilisateur et des capacités du système.
        """.trimIndent())

        addSection(content, context, "APPARENCE ET WIDGETS", """
L'apparence de l'application et celle des widgets sont des réglages visuels. Elles ne doivent jamais modifier une règle de pointage ou de paie.

Le widget principal affiche l'état et les informations utiles du pointage. Le widget d'actions rapides permet d'utiliser les actions de pointage proposées sans ouvrir l'écran principal. Les couleurs personnalisées du widget sont conservées indépendamment du thème de l'application.
        """.trimIndent())

        addSection(content, context, "MISES À JOUR", """
Pour une installation provenant de Google Play, les mises à jour de l'application sont gérées par Google Play. Une version installée depuis Google Play ne doit pas être remplacée par-dessus avec un APK GitHub signé différemment.
        """.trimIndent())

        content.addView(body(context, "© 2026 AGKGMG — Tous droits réservés.").apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(context, 24), 0, dp(context, 8))
        })

        val scroll = ScrollView(context).apply {
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        AlertDialog.Builder(context)
            .setTitle("Notice d'utilisation")
            .setView(scroll)
            .setPositiveButton("Fermer", null)
            .show()
    }

    private fun addSection(parent: LinearLayout, context: Context, heading: String, text: String) {
        parent.addView(sectionTitle(context, heading))
        parent.addView(body(context, text))
    }

    private fun title(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 20f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 8), 0, dp(context, 12))
    }

    private fun sectionTitle(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 16f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(context, 18), 0, dp(context, 6))
    }

    private fun body(context: Context, value: String) = TextView(context).apply {
        text = value
        textSize = 14f
        setLineSpacing(0f, 1.15f)
    }

    private fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()
}
