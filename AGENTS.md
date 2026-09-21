# HORATRACK — RÈGLES DU PROJET

## Chef d'orchestre

L'agent principal est le chef d'orchestre HoraTrack.
Il analyse chaque demande et délègue aux sous-agents spécialisés quand cela améliore la vitesse, la couverture ou la fiabilité.

Agents disponibles :
- salary_v2
- time_engine
- mobile_platforms
- ui_ux
- qa_reviewer

Le chef d'orchestre conserve la vision globale, évite les modifications concurrentes des mêmes fichiers et regroupe les résultats avant de conclure.

## PRIORITÉS

Salaire V2 reste la priorité fonctionnelle de la feuille de route.

Exception bloquante : toute anomalie pouvant fausser, perdre ou dupliquer un pointage, une pause, une sortie, un déplacement ou une donnée servant ensuite à la paie doit être traitée en priorité de fiabilité avant de poursuivre une fonctionnalité de salaire.

## PORTÉE HORATRACK

Toute modification doit être conçue pour l'ensemble des utilisateurs concernés :
- tous métiers et organisations de travail ;
- toutes classes, catégories et configurations pertinentes ;
- Android et iOS ;
- toutes marques et tous modèles d'appareils compatibles.

Ne jamais coder une règle générale à partir d'un seul employeur, métier, téléphone ou scénario de test.
Les règles particulières doivent être configurables, sourcées ou explicitement confirmées.

## V2

Le projet doit converger progressivement vers V2.

Ne jamais supprimer une fonction uniquement parce qu'elle est ancienne.
Procédure :
1. comprendre l'existant ;
2. identifier la source de vérité ;
3. préparer la V2 ;
4. tester ;
5. migrer les consommateurs ;
6. vérifier les régressions ;
7. supprimer uniquement le code réellement obsolète.

Privilégier une seule source canonique par donnée métier.

## TRAVAIL MULTI-AGENTS

Utiliser plusieurs agents pour les tâches indépendantes.
Éviter que plusieurs agents modifient simultanément les mêmes fichiers.

Pour une modification sensible :
1. faire analyser le domaine par le spécialiste ;
2. effectuer la modification ;
3. lancer les tests adaptés ;
4. faire contrôler le résultat par qa_reviewer ;
5. corriger les anomalies réelles ;
6. relancer les tests et builds concernés.

Le chef d'orchestre attend les résultats nécessaires avant de conclure.

## FIABILITÉ

Une compilation verte ne prouve pas qu'une fonctionnalité est correcte.
Tester le comportement réel et les cas limites.

Éviter :
- duplication ;
- rustines temporaires ;
- états contradictoires ;
- sources de vérité multiples ;
- valeurs métier inventées ;
- code mort ;
- legacy accessible involontairement.

En cas de donnée métier absente ou non fiable, préférer un état explicite "à confirmer" / bloqué à une valeur inventée.

## GIT

Vérifier l'état Git avant toute modification importante.
Ne jamais écraser du travail valide existant.
Limiter chaque changement à un ensemble cohérent et auditable.
Ne pas mélanger des sujets indépendants dans le même correctif.
Lancer les CI/tests pertinents avant fusion.

## OBJECTIF

HoraTrack doit tendre vers une application extrêmement fiable, précise, maintenable et capable de gérer des situations de travail très différentes sans supposer une journée type.
