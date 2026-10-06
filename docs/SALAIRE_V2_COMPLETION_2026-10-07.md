# Salaire V2 — suivi des 16 points

Demande du 7 octobre 2026 : terminer la chaîne de paie et importer un bulletin réel pour préremplir les données utiles à l’estimation. Base auditée : `16402435950e2f14c8c872b29f9d08ae2b851ee6` (PR #683).

Ce document distingue code existant, défauts corrigés et exigences non closes. Il ne constitue pas une certification de la paie ni une déclaration de disponibilité publique.

| Point | Objet | État de l’audit / critère à fermer |
|---|---|---|
| 1 | Temps payé | Gardes de couverture et de stockage présentes. Validation des parcours et semaines intermensuelles sur appareils réels requise. |
| 2 | Profil salarié et entreprise | Contrats datés, classification et entreprise canonique présents. Vérifier changements historiques et profils variés. |
| 3 | Base mensuelle et proratisation | Chaîne segmentée présente. Absences indemnisées non résolues bloquent la base fiable. |
| 4 | Heures supplémentaires/complémentaires | Temps plein couvert par règles confirmées. Certains calculs temps partiel restent provisoires : aucune clôture globale. |
| 5 | Majorations et cumuls | Règles arbitrées et preuves requises ; régime du 1er mai et certains cumuls encore bloqués dans l’adaptateur. |
| 6 | Primes et indemnités | Composantes fixes et paniers structurés. Ce lot rejette montants négatifs/non finis et débordements dans les moteurs Android/iOS. |
| 7 | Absences, IJSS et maintien | Impact des absences non payées couvert partiellement ; calcul complet des IJSS, congés et maintien reste à intégrer avec preuves. |
| 8 | Brut et minima | Résolution sourcée existante ; classification/période inconnues ne deviennent pas un minimum inventé. |
| 9 | Cotisations | Catalogues et entrées datées existants. Exhaustivité et couverture des profils doivent être démontrées. |
| 10 | Net, PAS et coût employeur | Net/PAS bloqués sur données fiscales incomplètes. Coût employeur explicitement incomplet : `employerCostComplete = false`. |
| 11 | Résultats uniques | Sorties canoniques présentes. Audit écran/PDF/comparaison/historique et absence de calcul parallèle à terminer. |
| 12 | Données manquantes | Politique inconnu ≠ zéro présente. Ce lot bloque champs importés invalides et période non confirmée. |
| 13 | Bulletins réels | Comparaison présente ; validation avec plusieurs bulletins indépendants encore requise. |
| 14 | Android/iOS et appareils | Parité des gardes monétaires dans ce lot. CI et essais sur appareils requis ; aucune validation téléphone revendiquée. |
| 15 | Livraison | Branche séparée ; contrôles de la révision exacte obligatoires avant fusion et distribution Google Play. |
| 16 | Import et préremplissage | Import local Android existant, renforcé ; brouillon contractuel Android pour un taux horaire explicitement libellé et import local iOS de cinq montants de comparaison implémentés, à valider. Pas de service IA distant ajouté. Extraction ≠ validation juridique ; confirmation avant mise à jour du profil. |

## Défauts d’import corrigés dans ce lot

- Période explicitement choisie : aucun rattachement silencieux au mois courant.
- Champ facultatif non vide invalide : blocage au lieu de conversion en absence.
- Sauvegarde détaillée échouée : message de résultat partiel, aucun faux succès.
- Source textuelle affichée pour les propositions OCR.
- Document tronqué : aucun préremplissage des totaux à partir d’une lecture incomplète.
- Cumuls annuels, colonnes ambiguës et corrections négatives : aucun montant mensuel/part salariale deviné.
- Agrégat incomplet : aucune somme partielle présentée comme proposition fiable.

## Validation

`git diff --check` et contrôle d’architecture de mise à jour réussis. Les quatre suites ciblées Kotlin ont été compilées indépendamment du SDK Android et exécutées avec JUnit 4 : 27 tests réussis (moteur de paie, parseur bulletin, confirmation et brouillon de taux). Cette preuve ne remplace pas le build Android ni les tests Swift.
La commande officielle Android `bash scripts/agent-toolbox.sh v2-tests` a été tentée ; échec de résolution des dépendances sur `dl.google.com`, avant exécution des tests. Swift/Xcode absents de ce runtime Linux. Ces faits ne valent pas PASS ; les validations CI de la révision finale restent nécessaires.

## Dépendances restantes

La clôture complète dépend d’entrées légales/contractuelles datées, d’une couverture exhaustive des contributions employeur, de scénarios d’absence indemnisée, de bulletins réels de validation et d’essais sur appareils. Un OCR local utilise la reconnaissance du texte disponible sur la plateforme ; il ne fournit pas à lui seul une interprétation juridique universelle du bulletin.
