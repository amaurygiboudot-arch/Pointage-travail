# HoraTrack — audit de monétisation du 6 octobre 2026

## Décisions approuvées

Application gratuite à télécharger. Premium reste à 4,99 €/mois ou 49,99 €/an ; PDF ordinaire à 0,99 €, sans aperçu avant achat vérifié. Quatre nouvelles prestations distinctes sont approuvées ci-dessous. Leur PDF est inclus. Pas de nouveau paiement pour récupérer le même résultat acheté ; un nouvel audit portant sur des données différentes est une nouvelle prestation clairement annoncée.

| Prestation | Prix cible | Livrable supplémentaire | Existant réutilisable | Portée livrée / extension restante |
|---|---:|---|---|---|
| Analyse du bulletin | 4,99 € | Contrôle documenté des lignes, bases, taux, retenues et incohérences | `PayslipDocumentParserV2`, `V2PayslipStore`, `PayslipLineComparisonEngineV2` | Rapport factuel des montants confirmés ; contrôle complet des bases/taux/lignes non implémenté |
| Comparaison pointage/bulletin | 6,99 € | Chronologie des heures et rapprochement des familles de montants disponibles avec écarts expliqués | `V2PayslipStore.comparison`, `SegmentedPayslipComparisonValuesV2`, moteur salarial canonique | Comparaison des familles fiables avec chronologie et références ; lignes non recalculables clairement exclues |
| Bilan annuel approfondi | 9,99 € | Audit de 12 mois, cumuls comparés aux bulletins, périodes manquantes et anomalies répétées | `AnnualPdfReports`, comparaisons mensuelles | Couverture des douze mois, cumuls des mois exploitables et écarts répétés ; sources insuffisantes signalées |
| Dossier de réclamation | 14,99 € | Chronologie, justificatifs, écarts documentés et courrier modifiable | Historique, comparaisons, sources officielles datées | Chronologie, écarts, courrier modifiable et liste des originaux à joindre ; aucun envoi ni expertise juridique |

Les quatre parcours disposent désormais de rapports factuels déterministes et d’un raccordement au paiement par rapport. Ils restent à valider sur une version Google Play réelle avant activation commerciale. Le catalogue décrit leur portée ; le paiement utilise uniquement les offres et prix actifs retournés par Google Play. Les calculs peuvent être déterministes : aucune IA ne doit être revendiquée sans service réellement raccordé. Les données absentes restent « à confirmer ». Le dossier présente des faits à vérifier ; il ne promet ni validation juridique, ni récupération certaine de salaire.

## Inventaire des fonctions et décision proposée

Audit des surfaces applicatives Android, des modules V2, des sources iOS et du serveur Firebase ; lecture statique, sans prétendre avoir exercé tous les écrans sur téléphone.

| Domaine | État observé / sources | Politique recommandée |
|---|---|---|
| Entrée, sortie, pauses, GPS, planning, tolérances | `MainActivity`, `GeofenceManager`, `ShiftProfileManager`, moteur V2 | Socle gratuit fiable, pas de modification de la paie pour augmenter les ventes |
| Historique, recherche, corrections et temps par lieu | `HistorySearchFilterView`, `MainActivity` | Consultation et récupération des données conservées accessibles |
| Widgets et personnalisation existante | `PointageWidgetProvider`, `QuickActionsWidgetProvider`, `WidgetStyleSettings` | Préserver l’existant ; de nouveaux thèmes peuvent devenir un achat esthétique |
| Paramètres entreprise, contrats, conventions et droits | `SalaryV2RootView`, magasins et moteurs V2 | Données nécessaires aux calculs accessibles ; audits approfondis payants distincts |
| Import photo/PDF de bulletin et confirmation | `V2PayslipImportActivity`, `PayslipDocumentParserV2` | Import et saisie gratuits ; ne pas facturer une extraction partielle comme audit complet |
| Comparaison simple | `V2PayslipStore.comparison`, iOS `SalaryPayslipComparisonV2` | Conserver la comparaison rapide ; tarif réservé au rapprochement détaillé livré |
| Statistiques de base | `LiveAnalyticsTextView`, `MainActivity` | Garder les indicateurs existants ; tendances et simulations nouvelles candidates à Premium |
| PDF quotidien, mensuel, annuel, exemple de paie | `DailyPdfReport`, `MonthlyPdfReport`, `AnnualPdfReports`, `SalaryExamplePdfV2` | 0,99 € par document ou inclus Premium, conformément aux décisions précédentes |
| Sauvegarde des données et restauration | `CloudPointageBackup`, `CloudSettingsBackup`, `V2BackupManager`, `DriveBackupScheduler` | Préserver sauvegardes brutes ; une future archive cloud documentaire est un service distinct |
| Publicité | Aucun SDK ou affichage publicitaire repéré | Ne pas vendre « suppression des pubs » tant que l’application n’affiche pas de publicité |
| Apparence, fonds, horloge et Céleste | `BackgroundPickerActivity`, `HpAnalogClockView`, `SunIndicatorView` | Nouveaux packs esthétiques possibles ; conserver les choix existants |
| Mini-jeux | `SnakeGameActivity`, `ObjectiveDeliveryGameActivity`, `DiamondLabActivity` | Priorité commerciale faible pour une app de pointage ; pas de vente préparée |
| Compte, sécurité, diagnostics et assistance | Firebase/Auth, `V2SecuritySettingsView`, `SecurityInfoActivity`, `OwnerFeedbackActivity` | Protection du compte et accès aux données conservés accessibles |
| Offre entreprises | Sélection employeur présente, pas de véritable portail multi-salariés livré | Projet distinct : administration, consentements, permissions et prix à concevoir |
| iOS | Moteurs Salaire V2 et préparation/partage PDF existants conservés, encore gratuits ; aucun achat Apple activé | Tarifs Google Play limités à Android ; migration StoreKit future distincte sans suppression du parcours existant |

## Autres ventes possibles : propositions, non activées

| Proposition | Modèle proposé | Valeur réellement nouvelle à construire |
|---|---|---|
| Simulations de salaire et de changement d’horaires | Inclus Premium de préférence | Scénarios comparés, hypothèses explicites, aucun changement du pointage réel |
| Statistiques avancées et objectifs | Inclus Premium de préférence | Tendances multi-mois, suivi heures/majorations, comparaisons de périodes |
| Archive cloud des rapports achetés | Option récurrente ou niveau supérieur, tarif à chiffrer | Retrouver les mêmes fichiers après changement d’appareil, stockage et droits synchronisés |
| Nouveaux thèmes/widgets | Pack ponctuel, tarif à définir | Nouveaux styles ; aucun retrait des styles existants |
| Pack de prestations | Tarif à décider après estimation des coûts | Crédits de services nommés, consommation et durée clairement affichées |
| Portail entreprises | Abonnement par effectif, tarif à étudier | Gestion multi-utilisateurs, exports collectifs et permissions employeur/salarié |

Les propositions ne sont pas approuvées à la vente : aucun changement de prix ou paywall supplémentaire dans cette préparation.

## Architecture et parcours nécessaires

1. Quatre produits distincts ; `horatrack_analysis` conservé pour le bulletin, trois nouveaux IDs nommés dans `BILLING_SETUP.md`. Aucun crédit générique ne doit financer un service différent par accident.
2. Avant achat : choisir la prestation, employeur/période/document ; vérifier les données et la possibilité réelle de produire le résultat. Montrer le contenu promis et le prix Google Play, sans aperçu du PDF généré.
3. Vérification serveur du compte, du produit et du jeton Play. Lier le droit à un identifiant de demande et à une empreinte canonique des données. Une relance ne crée ni deuxième débit, ni deuxième consommation.
4. Livrer et conserver le résultat immuable avec son empreinte PDF. Le PDF ordinaire à 0,99 € ou Premium ne doit pas autoriser l’analyse elle-même ; l’analyse achetée inclut seulement son propre PDF.
5. En cas d’échec, reprendre le même travail ou rendre le crédit disponible ; aucun crédit perdu simplement parce que l’application se ferme. Prévoir annulation/remboursement et échec réseau.
6. Même rapport : téléchargement sans repayer ; document modifié : prévenir avant toute nouvelle prestation. Le compte propriétaire reste exempté uniquement sur décision serveur.
7. Tester chaque prestation de bout en bout, droits et remboursements inclus ; configurer Google Play/Firebase et StoreKit séparément. Ne pas activer les offres non livrables.

Les PDF factuels sont générés sur l’appareil à partir des données confirmées. Le backend de paiement conserve seulement les identifiants et empreintes, vérifie les reçus et accorde les droits sur ces octets. Il ne certifie pas les calculs salariaux. Les anciens objets `completedAnalyses` ne remplacent pas ce parcours par rapport.

## Revenus : mesurer plutôt que promettre

Objectif utilisateur : au moins 1 000 € par mois. Les prix listés sont des objectifs commerciaux ; ils ne garantissent aucun revenu. Suivre abonnements actifs, achats par service, coût de livraison, stockage, remboursements et revenus réellement versés. Les montants affichés clients et les sommes nettes reçues diffèrent selon frais et fiscalité. Aucun calcul net n’est présenté sans connaître ces paramètres.

## Sources officielles vérifiées

- Politique des paiements et présentation fidèle des prix/services : https://support.google.com/googleplay/android-developer/answer/9858738
- Achat consommable et types de produits : https://support.google.com/googleplay/android-developer/answer/14590082
- Prix retournés par Play et droits de paiement : https://developer.android.com/google/play/billing/integrate

## Portée effectivement développée

Analyse : table de neuf familles de montants maximum, cohérence des données confirmées et limites, pas contrôle exhaustif des lignes/bases/taux. Comparaison : valeurs observées contre références canoniques fiables et chronologie des pointages. Annuel : mois contrôlés et manquants, cumuls partiels et écarts récurrents, sans additionner brut/net/composants entre eux. Dossier : écarts factuels, chronologie et courrier modifiable ; liste des pièces à joindre, originaux non joints automatiquement. Les descriptions commerciales doivent respecter cette portée au lieu de promettre la couverture complète envisagée dans le tableau initial.
