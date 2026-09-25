# Céleste — nuages V5 : noyau et raccord graphique

## Reprise du 25 septembre 2026

PR de travail unique : **#430**, `feat/celeste-v5-density-core`.
Ce raccord prolonge le noyau `9b731336be06a4e2bec7089d6419822dc60364db`, lui-même basé sur `main` après #426.
Ne pas recréer les lots #383/#426 ni une pile de PR de validation concurrente.
Référence fonctionnelle : document utilisateur « Nuages réalistes V5 — Recherche météorologique + spécification de rendu », 24/09/2026.

## Changements de ce raccord

1. **État canonique** : `CelestialRenderStateFactoryV2` transmet `CloudAtmosphereStateV2` aux deux interfaces. Les étages absents restent absents ; un zéro confirmé reste zéro.
2. **Qualification avant consommation** : âge, date future, lieu qualifié, cellule météo et données numériques sont contrôlés avant de calculer une visibilité ou une texture. Une météo rejetée ne continue plus à atténuer Soleil/Lune/étoiles derrière un simple warning.
3. **Expiration autonome** : dates de collecte et d'expiration voyagent avec le rendu. Les adaptateurs vérifient cette fenêtre même sans nouvel événement GPS. La durée reste détenue par le modèle météo ; aucune seconde durée de validité n'est codée dans les renderers. Le calcul d'âge Android rejette aussi un débordement de Long.
4. **Textures V5 actives en code** : les chemins de formes fermées V4, anciens helpers de bancs, cirrus dessinés et voiles concurrents sont retirés des deux renderers. Le raster partagé produit les pixels depuis le noyau de densité V5 ; le brouillard est bas et n'exige pas une couverture non nulle.
5. **Lumière et composition** : recettes de couleur identiques, pilotées par jour/crépuscule/nuit canoniques ; modulation intérieure même à couverture totale ; nuit volontairement discrète. Le fond nuage est composé avant les étoiles, dont la visibilité canonique ne reçoit donc pas une seconde atténuation par superposition du nuage. Soleil/Lune gardent leur visibilité et géométrie canoniques existantes.
6. **Cache et cycle de vie** : préparation hors thread UI ; textures bornées à 128 × 256 pixels ; détail 2/3/4 octaves selon qualité ; aucune couche météo mesurée retirée pour un appareil faible. Réutilisation tant que recette, taille, qualité et instant graphique ne changent pas. Annulation du travail obsolète, rejet d'une autre cellule/source, mémoire à deux images affichables et un calcul en cours par adaptateur Android.
7. **Transitions** : fondu de six secondes par addition prémultipliée isolée, et non deux voiles source-over. Réduction des animations respectée sans abandonner le contrôle d'expiration. iOS arrête son animation hors écran/scène active ; Android annule son tick lors de `clear()` et ne relance le tick que depuis un dessin effectif.
8. **iOS** : l'Accueil transmet le contexte météo au propriétaire canonique au lieu de conserver une qualification UI concurrente ; le message d'indisponibilité lit le résultat canonique.

Aucune position de nuage n'est revendiquée comme observation réelle. Bruit, ombrage, vitesse de déformation et palette sont des choix visuels. Aucun vent mesuré ni microphysique exacte n'est inventé. Le noyau de densité du premier lot reste inchangé.

## Vérifications réellement exécutées pour ce raccord

Environnement isolé Linux : Swift 6.2.1 et Kotlin 1.9.0/JVM (JRE 21).
Les sources de densité utilisées ont été recroisées avec les SHA des blobs du commit parent.

- Compilation native du raster Kotlin et Swift.
- **486 textures appariées**, issues de 9 types météo × 6 couvertures × 3 lumières × 3 niveaux de détail.
- **272 646 pixels ARGB comparés octet pour octet : identité exacte.**
- **239 167 assertions par langage** dans ce harnais : transparence du clair, nuit bornée, raccord périodique, annulation et variation interne du couvert.
- **8 tests XCTest du raster : 0 échec**, dans un package isolé portant le module `CelestialV2Contract`.
- Analyse syntaxique Swift des fichiers modifiés, y compris les vues ; ce n'est pas une compilation SwiftUI contre le SDK iOS.
- Aperçu interne du raster couvert, issu des pixels calculés ; ce n'est ni une capture de l'application ni une validation sur téléphone.

Tests ajoutés au dépôt : **8 tests raster + 4 tests de raccord canonique sur chaque plateforme**. Les tests de raccord utilisent le moteur réel dans la CI du projet. Leur présence n'est pas déclarée comme une exécution locale. JUnit/Gradle, build Android et compilation iOS complète restent à vérifier sur le nouveau HEAD par les workflows existants.
Les résultats CI du parent `9b73133` ne valent pas validation de ce nouveau raccord.

## État à ne pas confondre

- Noyau portable : implémenté dans le premier lot.
- Raccord aux consommateurs Android/iOS : implémenté dans ce lot, à valider par la CI et sur matériel.
- Rendu V5 satisfaisant sur téléphone : **NON VALIDÉ**.
- Performance/batterie/mémoire sur appareils : **NON MESURÉES** ; les bornes de texture ne constituent pas un benchmark.
- Revue multi-agents : aucune revue n'est simulée par le compte rendu de l'auteur.
- Fusion et publication : aucune autorisation de contournement ; pas de nouveau APK/AAB, déploiement Firebase ou publication store par ce document.

## Prochaine validation, sans nouvelle refonte

1. Exploiter CI Android/JUnit, APK+AAB, iOS/Swift, sécurité et fonctions sur le SHA exact. Corriger tout échec réel.
2. Obtenir la revue réelle des spécialistes requis, puis team_lead, QA et control_gate. `NOT_RUN`, quota et échec restent bloquants.
3. Vérifier appareils : changement de cellule, perte GPS, météo absente/périmée, hors ligne, arrière-plan/reprise, changement de taille, réduction des animations, économie d'énergie et pression mémoire.
4. Comparer vidéos Android/iOS pour clair, high seul, low/mid/high, couvert, brouillard, pluie/neige/orage et jour/crépuscule/nuit. Vérifier lisibilité du cadran et des textes, contours, répétitions et rythme réel.
5. Les améliorations fines restantes (vent comme influence limitée, éclairage directionnel solaire/lunaire du volume et éventuelle occultation locale) ne sont pas déclarées terminées. Toute occultation locale future doit remplacer la compensation correspondante, jamais la multiplier une deuxième fois, et ne doit pas présenter la forme procédurale comme un nuage localisé mesuré.
6. Publier un installateur de test seulement après les validations requises, via l'emplacement unique `dev-latest`.

## Périmètre

Tous métiers/classes/catégories, Android/iOS et appareils officiellement supportés. Aucune règle par marque/modèle.
Aucune modification de Salaire V2, Pointage, règles GPS métier, Firebase, catalogue stellaire, géométrie astronomique, gouvernance, permissions ou secrets.
