# HoraTrack — Plan des 6 couches Salaire V2

Ce document fixe l'ordre de développement. Chaque couche doit être livrée dans un commit distinct, compiler, conserver l'isolation par entreprise et ne pas toucher au moteur OpenGL/diamants.

## Couche 1 — Cotisations légales de base
Objectif : référentiel daté par période, avec source, assiette, plafond, taux salarial/patronal et règle d'arrondi.
- Vieillesse plafonnée/déplafonnée
- CSG déductible, CSG imposable, CRDS
- PASS/PMSS et plafonds applicables
- Cas Alsace-Moselle séparé
- Aucune valeur inconnue inventée
- Tests de calcul aux bornes du plafond

Commit cible : `Salaire V2 couche 1 - cotisations legales datees`

## Couche 2 — Retraite complémentaire
Objectif : intégrer les prélèvements complémentaires avec tranches datées.
- Agirc-Arrco T1/T2
- CEG T1/T2
- CET quand applicable
- Taux salarié et employeur séparés
- Tranches calculées à partir du plafond de la période
- Tests sous/au-dessus du PMSS

Commit cible : `Salaire V2 couche 2 - retraite complementaire`

## Couche 3 — Convention collective
Objectif : règles conventionnelles rattachées à l'IDCC et à leur date d'effet.
- IDCC stable par entreprise
- Minima/coefficient/classification
- Ancienneté
- Heures supplémentaires
- Nuit, samedi, dimanche et jours fériés quand la convention le prévoit
- Primes/indemnités conventionnelles
- Historique des versions et source Légifrance
- Si règle absente : À confirmer, jamais inventée

Commit cible : `Salaire V2 couche 3 - conventions collectives datees`

## Couche 4 — Spécificités entreprise et salarié
Objectif : compléter ce qui ne peut pas être déduit du seul droit commun/IDCC.
- Mutuelle
- Prévoyance
- Taux AT/MP employeur
- Transport/versement mobilité si nécessaire au coût employeur
- Paniers et indemnités propres à l'entreprise
- Primes contractuelles/personnelles
- Avantages en nature
- Valeurs stockées par companyId, avec période de validité

Commit cible : `Salaire V2 couche 4 - specificites entreprise salarie`

## Couche 5 — Calcul net complet
Objectif : une seule chaîne de calcul canonique utilisée par tous les écrans.
Entrées : contrat + entreprise + sessions + convention + référentiel de cotisations + spécificités.
Sorties :
- salaire de base
- heures/majorations/primes
- brut
- cotisations ligne par ligne
- net avant impôt
- net imposable
- prélèvement à la source uniquement si un taux valide est connu
- coût employeur lorsque les données nécessaires sont disponibles
- traces et avertissements expliquant chaque estimation

Commit cible : `Salaire V2 couche 5 - calcul net canonique`

## Couche 6 — Analyse et comparaison IA
Objectif : le bulletin réel et l'estimation utilisent exactement le même contexte entreprise/période.
- Comparaison ligne par ligne quand les données du bulletin sont disponibles
- Brut, net, heures, majorations, primes, cotisations et convention
- Tolérances explicites
- États : conforme / écart expliqué / anomalie potentielle / donnée insuffisante
- L'IA explique les écarts mais ne remplace jamais le moteur déterministe
- Aucun mélange entre entreprises
- Suppression/réordonnancement d'une entreprise ne remappe jamais les données d'une autre

Commit cible : `Salaire V2 couche 6 - comparaison IA bulletin`

## Règles de validation entre commits
1. Un seul objectif fonctionnel par commit.
2. Sources officielles et date d'effet conservées dans le référentiel.
3. Toute donnée incertaine reste `À confirmer`.
4. Toutes les lectures/écritures nouvelles utilisent l'identifiant stable `companyId`.
5. Tests unitaires des formules et des changements de tranche/période.
6. GitHub CI doit passer sur le SHA exact avant de considérer la couche techniquement intégrée.
7. Après les 6 couches : audit indépendant depuis le cahier des charges, puis test APK réel avec deux entreprises volontairement différentes.

## Sources de référence prioritaires
- Urssaf : taux, assiettes, plafonds et règles sociales.
- Agirc-Arrco : retraite complémentaire, tranches et contributions d'équilibre.
- Légifrance : conventions collectives, IDCC, accords et dates d'effet.

Ce plan est un plan de développement. Une couche n'est pas marquée VALIDÉE uniquement parce que son auteur l'a relue : CI, audit indépendant et tests réels restent séparés.
