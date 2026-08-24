# HoraTrack — pipeline graphique actif

Ce document sert de source de vérité avant tout nettoyage de ressources. Une ressource ou une classe graphique ne doit être supprimée que si Android Lint/R8, la recherche des références XML/Kotlin et la vérification des instanciations réflexives/XML confirment qu'elle n'est plus utilisée.

## Écran principal Android

Le layout canonique est `app/src/main/res/layout/activity_main.xml`.

Les trois actions principales utilisent actuellement les vues personnalisées suivantes :

- `GreenDiamondFinalButton` — Entrée ;
- `OrangeDiamondFinalButton` — Pause ;
- `RedDiamondFinalButton` — Sortie.

Ces classes et toutes leurs dépendances directes constituent le pipeline diamant actif. Les ressources portant des noms historiques comme `original`, `true3d`, `final`, `dynamic`, `composite` ou similaires ne sont pas réputées actives par leur seul nom : leur statut dépend d'une référence réelle depuis ces classes, les layouts ou le code chargé en production.

Aucun nettoyage de ressources ne doit modifier la géométrie, les facettes, les couleurs, l'éclairage dynamique ou le comportement capteur du pipeline diamant actif.

## Fond de l'application

`ThemedBackgroundScrollView` est le moteur de fond actif pour les écrans qui l'utilisent. L'image personnalisée d'application provient de `AppearanceManager.BACKGROUND_FILE` et ne doit jamais être confondue avec une texture de bouton ou de cadre.

`AppearanceManager` et `AppThemeCatalog` gèrent la palette/thème. Les textures de boutons ne doivent pas être réutilisées implicitement comme fond d'application.

## Horloge et soleil/lune

Dans `activity_main.xml`, l'horloge principale est rendue par `HpAnalogClockView` (`heroClockPermanent`). `SunIndicatorView` est le point d'entrée de l'indication soleil/lune dans ce layout.

Les anciens assets d'horloge ne sont supprimables que si aucune classe active, aucun XML, aucun widget et aucune ressource composée ne les référence.

## Widgets Android

Le widget principal est piloté par `PointageWidgetProvider` et `WidgetVisualRenderer`.

Les trois boutons du widget sont produits par :

- `WidgetVisualRenderer.jewelFrame(...)` ;
- `WidgetVisualRenderer.jewelInner(..., Jewel.ENTRY, ...)` ;
- `WidgetVisualRenderer.jewelInner(..., Jewel.PAUSE, ...)` ;
- `WidgetVisualRenderer.jewelInner(..., Jewel.EXIT, ...)`.

L'horloge du widget est produite par `WidgetVisualRenderer.clock(...)`.

Le petit widget est piloté par `QuickActionsWidgetProvider`.

Une ressource utilisée uniquement par une génération historique du widget peut être supprimée seulement après confirmation qu'elle n'est pas référencée par `RemoteViews`, XML, `WidgetVisualRenderer`, les providers ou une construction dynamique.

## Ressources de récupération

Le dépôt contient plusieurs générations de récupération (`RecoveryActivity`, `RecoveryActivityV2` et ressources associées). Elles sont considérées comme fonctionnelles tant que le manifeste ou un provider peut les lancer. Leur consolidation doit faire l'objet d'une correction dédiée avec test du parcours de récupération ; elles ne doivent pas être supprimées dans un simple nettoyage graphique.

## Méthode obligatoire avant suppression

Pour chaque candidat :

1. rechercher son nom exact dans Kotlin/Java, XML, manifestes, Gradle et ressources ;
2. vérifier les références indirectes (`getIdentifier`, réflexion, noms construits, `RemoteViews`, aliases) ;
3. lancer `lintRelease` et le build minifié/R8 ;
4. vérifier les écrans et widgets qui utilisent le même pipeline ;
5. ne supprimer que les ressources pour lesquelles aucune voie d'exécution ou référence n'existe ;
6. effectuer les suppressions par petits lots indépendants afin qu'une régression soit attribuable.

## Règle de maintenance

Toute nouvelle génération graphique doit soit remplacer explicitement une génération existante avec suppression testée de l'ancienne, soit documenter pourquoi les deux doivent coexister. Ajouter une nouvelle variante `final2`, `new`, `true3d2`, etc. sans définir laquelle est la source de vérité est interdit.
