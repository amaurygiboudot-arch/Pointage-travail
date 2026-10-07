# Salaire V2 — suivi des 16 points

Demande du 7 octobre 2026 : terminer la chaîne de paie et importer un bulletin réel pour préremplir les données utiles à l’estimation. Base auditée : `16402435950e2f14c8c872b29f9d08ae2b851ee6` (PR #683).

Ce document distingue code existant, défauts corrigés et exigences non closes. Il ne constitue pas une certification de la paie ni une déclaration de disponibilité publique.

| Point | Objet | État de l’audit / critère à fermer |
|---|---|---|
| 1 | Temps payé | Gardes de couverture et de stockage présentes. Validation des parcours et semaines intermensuelles sur appareils réels requise. |
| 2 | Profil salarié et entreprise | Contrats datés, classification et entreprise canonique présents. Vérifier changements historiques et profils variés. |
| 3 | Base mensuelle et proratisation | Chaîne segmentée présente. Absences indemnisées non résolues bloquent la base fiable. |
| 4 | Heures supplémentaires/complémentaires | Barèmes complémentaires distincts, sourcés et datés conservés par les stores Android/iOS et consommés par les pipelines canoniques segmentés. Contrat, dates et couverture des paliers vérifiés. Sans preuve complète, résultat provisoire/bloqué ; extraction automatique des accords encore absente. |
| 5 | Majorations et cumuls | Garde canonique Android/iOS des chevauchements nuit/weekend/férié et primes/heures supplémentaires ou complémentaires. Des primes distinctes restent calculables avec preuve réelle d’absence de chevauchement. Les cumuls non prouvés et le 1er mai canonique restent bloqués. |
| 6 | Primes et indemnités | Composantes fixes et paniers structurés. Ce lot rejette montants négatifs/non finis et débordements dans les moteurs Android/iOS. |
| 7 | Absences, IJSS et maintien | Estimation IJSS Android existante sécurisée contre salaires non finis, durée excessive et année sans barème. Saisie persistante des IJSS réelles mensuelles Android/iOS avec entreprise, source et destinataire ; aucun ajout au net employeur. Maintien et projection fiscale mensuelle restent à intégrer. |
| 8 | Brut et minima | Résolution sourcée existante ; classification/période inconnues ne deviennent pas un minimum inventé. |
| 9 | Cotisations | Catalogues et entrées datées existants. Exhaustivité et couverture des profils doivent être démontrées. |
| 10 | Net, PAS et coût employeur | Net/PAS bloqués sur données fiscales incomplètes. Coût employeur explicitement incomplet : `employerCostComplete = false`. |
| 11 | Résultats uniques | Sorties canoniques présentes. Correction iOS : les ventilations des variables sont conservées dans la production détaillée. Audit complet écran/PDF/comparaison/historique à terminer. |
| 12 | Données manquantes | Politique inconnu ≠ zéro présente. Ce lot bloque champs importés invalides et période non confirmée. |
| 13 | Bulletins réels | Comparaison présente ; validation avec plusieurs bulletins indépendants encore requise. |
| 14 | Android/iOS et appareils | Parité des gardes monétaires dans ce lot. CI et essais sur appareils requis ; aucune validation téléphone revendiquée. |
| 15 | Livraison | Branche séparée ; contrôles de la révision exacte obligatoires avant fusion et distribution Google Play. |
| 16 | Import et préremplissage | Import local Android/iOS ; taux horaire explicitement libellé proposé en brouillon contractuel, période mensuelle libellée proposée avec source et confirmation. Android conserve et affiche les extraits des montants inchangés ; iOS conserve les cinq observations confirmées et leurs extraits par entreprise/mois, avec restauration explicite dans la comparaison. Pas de service IA distant ajouté ni d'interprétation juridique universelle revendiquée. |

## Défauts d’import corrigés dans ce lot

- Période explicitement choisie : aucun rattachement silencieux au mois courant.
- Champ facultatif non vide invalide : blocage au lieu de conversion en absence.
- Sauvegarde détaillée échouée : message de résultat partiel, aucun faux succès.
- Source textuelle affichée pour les propositions OCR.
- Document tronqué : aucun préremplissage des totaux à partir d’une lecture incomplète.
- Cumuls annuels, colonnes ambiguës et corrections négatives : aucun montant mensuel/part salariale deviné.
- Agrégat incomplet : aucune somme partielle présentée comme proposition fiable.

## Validation

`git diff --check` et contrôle d’architecture de mise à jour réussis. Les quatre suites ciblées Kotlin ont été compilées indépendamment du SDK Android et exécutées avec JUnit 4 : 28 tests réussis (moteur de paie, parseur bulletin, confirmation et brouillon de taux). Cette preuve ne remplace pas le build Android ni les tests Swift.
La commande officielle Android `bash scripts/agent-toolbox.sh v2-tests` a été tentée ; échec de résolution des dépendances sur `dl.google.com`, avant exécution des tests. Swift/Xcode absents de ce runtime Linux. Ces faits ne valent pas PASS ; les validations CI de la révision finale restent nécessaires.

## Dépendances restantes

La clôture complète dépend d’entrées légales/contractuelles datées, d’une couverture exhaustive des contributions employeur, de scénarios d’absence indemnisée, de bulletins réels de validation et d’essais sur appareils. Un OCR local utilise la reconnaissance du texte disponible sur la plateforme ; il ne fournit pas à lui seul une interprétation juridique universelle du bulletin.

## Suite de l’implémentation

74 tests Kotlin ciblés exécutés avec JUnit : les 44 tests moteur/import/période/IJSS et les 30 tests variables segmentées/indemnité du 1er mai passent. Les codecs Android dépendant du SDK et les tests Swift restent soumis à la CI de la révision finale.

Le traitement Android du 1er mai distingue désormais son blocage dédié des autres preuves manquantes ; une indemnité confirmée ne lève aucun autre blocage. Durée négative et dépassement monétaire restent non fiables. Le chemin canonique segmenté conserve son blocage du 1er mai tant que sa preuve dédiée n’est pas intégrée sur les deux plateformes.

Les diagnostics de coût employeur distinguent contributions d’entreprise non prouvées, brut social non fiable et réductions inconnues ; une réduction explicitement confirmée à zéro reste connue. Aucun total patronal complet n’est revendiqué.

Après correction des cumuls canoniques, les dix suites Kotlin ciblées passent : 106 tests, incluant le constructeur de preuves de sessions. Ce contrôle ne remplace pas la CI Android complète, les tests Swift et les essais sur appareils.

La copie iOS de production détaillée conserve les ventilations des variables ; une régression vérifie leur égalité avec le résultat du calcul canonique. Le raccord du 1er mai canonique exige encore une preuve LEGI datée sur iOS, sa résolution dans les bridges et une allocation journalière sans double application de l’ajustement Android existant.

## Corrections après revue QA

Les espaces fines insécables préservent les milliers. L’annulation iOS garde le verrou de lecture jusqu’à la fin du worker ; une édition manuelle invalide la provenance importée. Le brouillon contractuel Android reste attaché à son entreprise lors des rafraîchissements et est effacé après confirmation canonique. Tests Kotlin ciblés : 28 réussis ; validation complète Android/iOS toujours requise.
