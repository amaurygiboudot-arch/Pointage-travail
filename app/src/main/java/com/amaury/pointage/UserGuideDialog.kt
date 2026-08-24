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

        content.addView(title(context, "NOTICE D'UTILISATION — HP TRAVAIL"))
        content.addView(body(context, "HP Travail permet d'enregistrer tes heures de travail, tes pauses, de suivre ton historique, d'estimer ton salaire et d'exporter automatiquement tes rapports PDF."))

        addSection(content, context, "POINTAGE", """
ENTRÉE
Appuie en arrivant au travail. L'heure de début est enregistrée.

PAUSE
Appuie au début de ta pause. Appuie de nouveau pour reprendre le travail. Le temps de pause est retiré du temps réellement travaillé.

SORTIE
Appuie quand tu termines ta journée. HP Travail calcule alors le temps travaillé en retirant les pauses.

SAISIE MANUELLE D'UNE PAUSE
Permet d'ajouter ou corriger une pause qui n'a pas été enregistrée avec le bouton Pause.
        """.trimIndent())

        addSection(content, context, "ONGLETS", """
AUJOURD'HUI
Affiche le pointage et les informations de la journée en cours.

HISTORIQUE
Affiche les pointages déjà enregistrés.

ANALYSES
Affiche les heures enregistrées et permet de consulter ou générer un rapport PDF pour le mois choisi.

PARAMÈTRES
Permet de régler les lieux de travail, le GPS automatique, l'apparence, les widgets, Google Drive et les mises à jour.
        """.trimIndent())

        addSection(content, context, "SALAIRE — COMMENT ÇA MARCHE ?", """
L'onglet Salaire utilise les heures enregistrées dans HP Travail pour donner une estimation du salaire brut du mois choisi.

1. TAUX HORAIRE BRUT
Entre le montant brut payé pour une heure de travail, par exemple 13,70 €. Le calcul se met à jour automatiquement quand le taux change.

2. DATE D'ENTRÉE DANS L'ENTREPRISE
Indique ta date d'embauche. Elle permet à HP Travail d'afficher ton ancienneté pour le mois sélectionné.

3. ENTREPRISE PRINCIPALE
Renseigne ton entreprise principale. Les informations enregistrées permettent d'associer la bonne convention collective et ses règles lorsque celles-ci sont disponibles dans l'application.

4. CONVENTION COLLECTIVE
La convention choisie détermine les règles utilisées pour les heures supplémentaires et les majorations intégrées. Appuie sur le nom de la convention pour voir ses détails. « Règles intégrées » signifie que HP Travail connaît les règles utilisées pour le calcul. « Calcul légal provisoire » signifie que certaines règles particulières de cette convention ne sont pas encore intégrées.

5. CHOISIR LE MOIS
Choisis le mois que tu veux contrôler. HP Travail reprend les pointages enregistrés pendant ce mois.

6. RECALCULER
Relance le calcul avec les informations actuellement enregistrées.

7. HEURES DU MOIS
« Heures normales » correspond aux heures payées au taux normal. Les lignes d'heures supplémentaires montrent les heures auxquelles une majoration s'applique. « Total pointé » correspond au temps de travail enregistré pour le mois, après prise en compte des pauses par le système de pointage.

8. ESTIMATION BRUTE
« Taux horaire » rappelle le taux saisi. « Heures supplémentaires » affiche le montant brut estimé lié aux heures supplémentaires. « Salaire estimé » donne l'estimation brute calculée pour le mois.

IMPORTANT
Le résultat est une estimation. HP Travail ne remplace pas le bulletin de paie. Une prime, une absence, un accord d'entreprise, une règle conventionnelle non intégrée ou une information mal renseignée peut créer une différence avec la paie réelle.
        """.trimIndent())

        addSection(content, context, "EXPORT PDF AUTOMATIQUE ET GOOGLE DRIVE", """
Une fois le dossier Google Drive configuré, tu n'as normalement plus rien à faire.

• HP Travail vérifie automatiquement les journées terminées et crée un PDF de chaque journée.
• Les fichiers sont classés automatiquement par lieu ou entreprise, puis par année et par mois.
• Le PDF quotidien reprend les heures d'entrée et de sortie, les pauses et le temps réellement travaillé.
• Lorsqu'un mois est terminé, HP Travail crée automatiquement le récapitulatif PDF du mois terminé.
• Le récapitulatif mensuel regroupe les pointages et les totaux du mois.
• « EXPORTER LES PDF DE L'HISTORIQUE » permet de recréer les rapports à partir des données encore présentes dans l'application.

IMPORTANT — CE N'EST PAS UNE SAUVEGARDE RESTAURABLE
Les fichiers Drive sont des rapports PDF destinés à la consultation et à l'archivage. Ils ne contiennent pas un format de données réimportable dans HP Travail et ne permettent pas de reconstruire automatiquement l'historique sur un nouveau téléphone. Les données de pointage structurées restent stockées localement dans l'application.

La sauvegarde système Android est désactivée par l'application. Selon la version d'Android et le constructeur, certains mécanismes de transfert direct entre appareils peuvent toutefois suivre leurs propres règles. Tant qu'un export structuré, chiffré et réimportable n'est pas disponible, considère les PDF Drive comme des archives de consultation, pas comme un moyen de restauration de l'historique dans l'application.

L'export PDF automatique nécessite que le dossier Drive reste accessible sur le téléphone. Si Google Drive n'est pas disponible au moment du contrôle, HP Travail réessaiera lors d'un prochain contrôle.
        """.trimIndent())

        addSection(content, context, "POINTAGE GPS", """
Le pointage GPS peut détecter l'arrivée ou le départ d'un lieu de travail enregistré. Pour fonctionner quand l'application est fermée, Android doit autoriser HP Travail à utiliser la localisation en arrière-plan. Le rayon définit la distance autour du lieu dans laquelle la détection peut se déclencher.
        """.trimIndent())

        addSection(content, context, "WIDGETS", """
Le widget complet affiche les principales informations de pointage directement sur l'écran d'accueil.

Le widget rapide contient seulement Entrée, Pause/Reprendre et Sortie pour pointer plus vite sans ouvrir l'application.
        """.trimIndent())

        content.addView(body(context, "© 2026 HP Travail — Tous droits réservés.").apply {
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
