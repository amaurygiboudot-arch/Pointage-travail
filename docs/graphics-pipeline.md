# HoraTrack — pipeline graphique actif

Ce document sert de source de vérité avant tout nettoyage de ressources. Une ressource ou une classe graphique ne doit être supprimée que si Android Lint/R8, la recherche des références XML/Kotlin et la vérification des instanciations réflexives/XML confirment qu'elle n'est plus utilisée.

## Écran principal Android

Le layout canonique est `app/src/main/res/layout/activity_main.xml`.

Les trois actions principales utilisent actuellement les vues personnalisées suivantes :

- `GreenDiamondFinalButton` — Entrée ;
- `OrangeDiamondFinalButton` — Pause ;
- `RedDiamondFinalButton` — Sortie.

Ces classes et toutes leurs dépendances directes constituent le pipeline diamant actif. Le pipeline actif comprend également la chaîne d'éclairage dynamique : `SalaryTabTextView.onAttachedToWindow()` installe `ButtonReliefInstaller`, qui attache `LightDirectionController` et propage l'éclairage naturel/capteur vers les boutons diamant via les mises à jour globales prévues par les boutons finaux. Ces dépendances inverses sont protégées au même titre que les trois vues elles-mêmes.

### Renderers secondaires actifs

Les noms historiques ne suffisent pas à déterminer qu'un renderer est obsolète. Plusieurs générations portant des noms `true3d`, `dynamic` ou `composite` sont encore réellement utilisées par `ButtonReliefInstaller.applyToButton(...)` pour les boutons secondaires :

- `CarbonCompositeDrawable` pour le thème `natural_carbon` ;
- `DynamicDiamondDrawable` pour les thèmes ordinaires concernés ;
- `True3DButtonInstaller` pour le thème `diamond_crystal` ;
- `carbon_fill_b64` et `carbon_frame_b64` restent des dépendances actives du chemin carbone.

Ces classes et ressources ne sont donc pas des candidats de nettoyage tant que ce routage existe.

Aucun nettoyage de ressources ne doit modifier la géométrie, les facettes, les couleurs, l'éclairage dynamique ou le comportement capteur du pipeline diamant actif.

## Icône launcher pilotée par l'état

Le pipeline d'icône d'application est actif et dynamique. Les alias déclarés dans le manifeste utilisent `hp_icon_red`, `hp_icon_green` et `hp_icon_orange`. `PointageStore.scheduleIconSync()` déclenche `IconSwitcher.sync()` afin d'activer l'alias correspondant à l'état arrêté, en travail ou en pause.

Un alias initialement désactivé dans le manifeste n'est donc pas une preuve d'inutilisation : ces trois icônes et leurs aliases sont des points d'entrée runtime protégés.

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

## app-v3

`app-v3` contient son propre `activity_main.xml`, `V3HeaderView`, `V3JewelButton`, `V3SunIndicatorView` et des ressources `v3_*`, mais il n'est pas inclus dans le build racine (`settings.gradle.kts` ne construit que `:app`) et aucun workflow de production ne l'invoque.

Son pipeline graphique est donc **inactif dans le produit officiel actuel**. Il constitue un ensemble de prototype/cleanup distinct et ne doit pas être confondu avec le pipeline graphique de `:app`. Toute remise en service de `app-v3` exige une PR d'intégration et de CI dédiée.

## Candidats orphelins confirmés par la revue

La revue repository-wide a vérifié les références Kotlin/Java, XML, manifestes, `RemoteViews`, constructions de noms/réflexion et dépendances des renderers actifs. Les éléments ci-dessous sont des **candidats de nettoyage à haute confiance** ; ils doivent néanmoins être supprimés dans une PR dédiée et revalidés par lint/build avant fusion.

### Application / graphismes principaux

- `luxury_entry.webp` ;
- `luxury_exit.webp` ;
- `cadre_bouton_carbonne.xml` ;
- `hp_entry.xml` ;
- `hp_exit.xml` ;
- `hp_watch_face.xml` ;
- `layout/hp_logo.xml` et `hp_logo_vector.xml` ;
- `OriginalButtonImageRenderer` ;
- `DiamondDrawable` ;
- `PrimaryDiamond3DInstaller` ;
- `PauseJewelButton`.

### Widgets

- `widget_action_entry.xml`, `widget_action_pause.xml`, `widget_action_exit.xml` et leurs variantes `_new` ;
- `widget_bg.xml` ;
- `widget_center.xml` ;
- les deux anciennes variantes de cadran d'horloge ;
- les deux anciennes variantes d'aiguille des heures ;
- les deux anciennes variantes d'aiguille des minutes ;
- `widget_clock_transparent.xml` ;
- `widget_clock_xml.xml` ;
- `widget_frame_carbon_chrome.xml` ;
- `widget_green.xml` ;
- `widget_pause_orange.xml` ;
- `widget_red.xml` ;
- `widget_status_panel.xml` ;
- `widget_clock_luxury.webp` ;
- `widget_luxury_exact.webp` ;
- `widget_panel_exact.webp`.

Cette liste n'autorise aucune suppression d'un élément non listé sans nouvelle preuve. En particulier, les classes de l'éclairage dynamique, les renderers secondaires actifs et les icônes launcher pilotées par l'état sont explicitement protégés.

## Ressources de récupération

`LaunchActivity` lance explicitement `RecoveryActivityV2`, qui est le parcours de récupération actif. `RecoveryInitProvider` installe `CrashRecoveryManager` mais ne lance pas `RecoveryActivity`.

`RecoveryActivity` reste déclaré dans le manifeste, mais aucune voie de production identifiée (code, alias, provider, réflexion ou nom construit) ne le lance actuellement. Une déclaration de manifeste seule ne constitue pas un chemin d'exécution. Cette génération legacy doit donc être traitée comme **candidate de consolidation/suppression dédiée**, avec test du parcours `RecoveryActivityV2`, et non être supprimée au milieu d'un simple nettoyage graphique.

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
