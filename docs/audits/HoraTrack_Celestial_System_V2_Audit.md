# HoraTrack — Audit ciblé horloge / suivi céleste → V2

## Périmètre

Cet audit concerne uniquement :

- l’horloge analogique et ses aiguilles ;
- la Terre centrale ;
- le Soleil et la Lune ;
- les positions astronomiques ;
- la phase lunaire et son terminateur ;
- l’ombre de la Terre pendant une éclipse lunaire ;
- le GPS, les capteurs d’orientation et l’éclairage naturel reliés à ce système ;
- le raccordement de ce sous-système à HoraTrack V2.

Sont volontairement hors périmètre : pointage métier, pauses, paie, conventions, paniers, droits, PDF et moteurs juridiques.

## Résultat de l’audit

### CRITIQUE — ancien calcul de position lunaire

Le solveur historique calculait les éléments orbitaux de la Lune mais ne reconstruisait pas correctement son plan orbital : l’inclinaison lunaire d’environ 5,1454° et le nœud orbital n’étaient pas appliqués dans le vecteur écliptique final utilisé pour la position.

Conséquence : azimut, altitude, phase apparente et détection d’éclipse pouvaient être fortement faux.

**Correction V2 :** le nouveau moteur `CelestialEngineV2` reconstruit le vecteur orbital avec nœud + inclinaison, applique les principales perturbations lunaires et corrige aussi la distance.

### CRITIQUE — phase lunaire historique

La fraction éclairée était déduite uniquement de la séparation angulaire Soleil/Lune issue des positions affichées. Elle ne calculait pas le véritable angle de phase du triangle Soleil–Lune–Terre et ne tenait pas compte des distances.

**Correction V2 :** calcul de l’angle de phase avec distance Soleil–Terre et distance Terre–Lune, puis `fraction = (1 + cos(anglePhase)) / 2`.

Le moteur expose aussi l’angle de position du limbe éclairé (`brightLimbPositionAngleDeg`).

### CRITIQUE — orientation historique de l’ombre lunaire

Le rendu orientait la partie éclairée de la Lune avec une simple ligne 2D entre l’image de la Lune et l’image du Soleil sur l’anneau du cadran. Comme l’altitude des deux astres n’était pas représentée sur cet anneau, l’ombre pouvait être orientée incorrectement dès que la différence Soleil/Lune était principalement verticale dans le ciel.

**Correction V2 terminée :** `CelestialScreenGeometryV2` reconstruit les vecteurs réels Soleil/Lune dans le repère local Est–Nord–Zénith, projette la direction tangentielle Lune → Soleil sur la géométrie du cadran et fournit une direction écran normalisée. Le terminateur utilise cette direction réelle plutôt qu’une ligne décorative entre deux sprites.

Le même principe oriente l’axe de l’ombre terrestre vers le vrai anti-Soleil pendant une éclipse.

### CRITIQUE — ombre terrestre / éclipses

L’ancienne détection utilisait un seuil empirique proche de 180° et dessinait une ombre d’environ un rayon lunaire.

Ce modèle n’est pas physique : à la distance de la Lune, l’ombre centrale de la Terre (umbra) est nettement plus grande que le disque lunaire et la pénombre doit être calculée séparément.

**Correction V2 :**

- calcul de l’axe anti-solaire ;
- distance du centre lunaire à l’axe de l’ombre ;
- rayon réel de l’umbra à la distance instantanée de la Lune ;
- rayon réel de la pénombre ;
- magnitude pénombrale ;
- magnitude ombrale ;
- classification `NONE / PENUMBRAL / PARTIAL / TOTAL` ;
- rendu de l’umbra et de la pénombre avec les rayons V2 au lieu d’un disque arbitraire ;
- orientation écran de l’axe d’ombre vers le vrai anti-Soleil.

### ÉLEVÉ — parallaxe lunaire absente

La Lune étant proche, sa position apparente dépend fortement de la position de l’observateur sur Terre, surtout près de l’horizon.

**Correction V2 :** calcul topocentrique de la Lune à partir de la latitude, longitude et altitude de l’observateur avant conversion en azimut/altitude.

### ÉLEVÉ — faux ciel sans GPS

Le rendu historique inventait des positions Soleil/Lune de secours à partir de l’heure lorsque le GPS était absent.

Cela contredit l’objectif de suivi céleste réel.

**Correction V2 :** `SunIndicatorView` ne dessine plus de faux Soleil/Lune lorsque la position n’est pas disponible ou qualifiée. L’horloge reste utilisable, mais le ciel réel est fail-closed.

### ÉLEVÉ — double source de calcul astronomique

`SunIndicatorView` et `LightDirectionController` recalculaient séparément Soleil/Lune.

**Correction V2 :** les deux consomment la même source mathématique `HoraTrackV2.celestial`.

### ÉLEVÉ — double acquisition GPS / capteurs

La vue céleste et le contrôleur d’éclairage avaient chacun leur propre lecture de la localisation et leur propre abonnement aux capteurs d’orientation.

**Correction V2 terminée :** `CelestialTrackerV2` est l’unique couche Android d’acquisition pour ce sous-système. `SunIndicatorView` et `LightDirectionController` s’y abonnent sans enregistrer leurs propres capteurs.

Le tracker :

- partage un seul flux d’orientation ;
- préfère `TYPE_ROTATION_VECTOR` ;
- utilise accéléromètre + magnétomètre en secours ;
- stabilise l’azimut ;
- rafraîchit le snapshot astronomique toutes les 30 secondes ;
- centralise la lecture de la dernière localisation connue ;
- arrête les capteurs lorsqu’il n’y a plus d’abonné.

### ÉLEVÉ — qualité/fraîcheur GPS non contrôlée

L’ancien système utilisait n’importe quelle `lastKnownLocation()` disponible, même ancienne ou très imprécise.

**Correction V2 terminée :** `CelestialTrackingPolicyV2` applique une politique fail-closed :

- permission absente → `NO_PERMISSION` ;
- aucune position → `UNAVAILABLE` ;
- position de plus de 10 minutes → `STALE` ;
- position sans précision connue ou précision > 2 km → `INACCURATE` ;
- seule une position `VALID` peut produire un ciel réel.

Une direction solaire devenue non fiable est explicitement effacée de `CelestialLightingState` afin d’éviter de conserver une ancienne direction comme si elle était actuelle.

### ÉLEVÉ — altitude réelle absente de la position graphique

Le système historique plaçait Soleil et Lune sur un anneau fixe : seul l’azimut changeait. Deux astres de même azimut mais à des altitudes très différentes se retrouvaient donc au même rayon.

**Correction V2 terminée :** `CelestialScreenGeometryV2.projectOnWatchDome()` encode maintenant l’altitude réelle dans le rayon du cadran :

- azimut réel → angle autour de la montre ;
- horizon civil → bord externe du dôme ;
- astre montant → déplacement progressif vers le centre ;
- zénith → rayon interne de 34 % du rayon d’horizon afin de préserver la Terre centrale et la lisibilité des aiguilles ;
- astre sous l’horizon civil → aucun sprite affiché.

Il s’agit volontairement d’un **compas céleste d’horloge**, pas d’un mode caméra/AR. Le rayon est comprimé près du zénith pour conserver le design de l’horloge, mais il est désormais monotone avec l’altitude réelle et le ciel sous l’horizon n’est plus représenté comme visible.

### MOYEN — deux vues d’horloge dans `activity_main.xml`

Le layout contient `heroClockPermanent` (vue réellement dimensionnée à 210 dp) et `heroClockHands` (1 × 1 dp, alpha 0). Les recherches de références montrent que la seconde vue n’est utilisée que par l’ancien contrôle de visibilité de `LuxuryUiInstaller` et par la liste de protection de `ThemeFrameStyler`.

**Décision V2 :** `heroClockPermanent` est l’horloge canonique visible. `ThemeFrameStyler` la protège désormais explicitement contre les styles génériques.

**Nettoyage différé volontairement :** `heroClockHands` reste physiquement dans le layout tant que le rendu n’a pas été validé sur appareil. On ne redirige pas encore la logique de visibilité de `LuxuryUiInstaller`, car cela pourrait modifier le comportement historique « permanent » de l’horloge sur les autres onglets. Après validation visuelle, la vue fantôme et le code mort associé pourront être supprimés dans un commit séparé et minimal.

### FAIBLE / PARITÉ — widget Android

Le widget dessine son propre cadran, ses aiguilles et sa Terre via `WidgetVisualRenderer`. Il ne dessine pas actuellement Soleil/Lune ni la phase réelle.

Il reste volontairement séparé tant que le rendu principal V2 n’est pas validé sur appareil.

## Architecture V2 actuelle

```text
Android GPS + capteurs
        |
        v
CelestialTrackerV2
        |
        +--> politique qualité GPS fail-closed
        |
        +--> HoraTrackV2.celestial
        |       |
        |       +--> CelestialEngineV2
        |              +--> Soleil
        |              +--> Lune orbitale corrigée
        |              +--> parallaxe topocentrique
        |              +--> phase réelle
        |              +--> angle du limbe éclairé
        |              +--> umbra / pénombre / magnitude éclipse
        |
        +--> CelestialScreenGeometryV2
        |       +--> azimut réel autour du cadran
        |       +--> altitude réelle dans le rayon du dôme
        |       +--> vraie direction Lune -> Soleil
        |       +--> vraie direction Lune -> anti-Soleil
        |
        +--> SunIndicatorView (rendu uniquement)
        |
        +--> LightDirectionController (éclairage UI uniquement)

Ancien CelestialEphemeris
        |
        +--> façade de compatibilité --> HoraTrackV2.celestial
```

## Cas de référence verrouillés par tests

Les tests V2 couvrent notamment :

- nouvelle Lune du 17 février 2026 vers 12:01 UTC : disque presque 0 % éclairé ;
- pleine Lune du 3 mars 2026 vers 11:38 UTC : disque presque 100 % éclairé ;
- éclipse lunaire totale du 3 mars 2026 ;
- éclipse lunaire partielle du 28 août 2026 ;
- éclipse pénombrale du 20 février 2027 ;
- proximité Soleil/Lune pendant la nouvelle Lune ;
- rejet d’une latitude invalide ;
- position GPS récente/précise acceptée ;
- position GPS ancienne, imprécise ou sans précision refusée ;
- direction écran de l’éclairage lunaire selon azimut et altitude ;
- direction écran de l’axe anti-solaire lors d’une éclipse ;
- horizon placé sur le bord du dôme ;
- altitude intermédiaire rapprochant progressivement l’astre du centre ;
- zénith au rayon interne protégé ;
- astre sous l’horizon civil non dessiné ;
- rotation du téléphone faisant tourner le compas céleste.

Références astronomiques utilisées pour choisir les cas astronomiques : U.S. Naval Observatory (phases) et NASA/GSFC (catalogue des éclipses).

## Fichiers principaux du lot

- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialEngineV2.kt` — moteur astronomique pur V2 ;
- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialTrackingPolicyV2.kt` — qualification fail-closed de la localisation ;
- `app/src/main/java/com/amaury/pointage/v2/CelestialTrackerV2.kt` — acquisition Android partagée GPS + orientation ;
- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialScreenGeometryV2.kt` — projection altitude/azimut + directions physiques d’éclairage ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialEngineV2Test.kt` — références astronomiques ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialTrackingPolicyV2Test.kt` — tests qualité GPS ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialScreenGeometryV2Test.kt` — tests projection/orientation écran ;
- `app/src/main/java/com/amaury/pointage/v2/HoraTrackV2.kt` — couche `CELESTIAL` ;
- `app/src/main/java/com/amaury/pointage/v2/V2LegacyPolicy.kt` — domaine `CELESTIAL` ;
- `app/src/main/java/com/amaury/pointage/CelestialEphemeris.kt` — façade historique vers V2 ;
- `app/src/main/java/com/amaury/pointage/SunIndicatorView.kt` — rendu piloté par tracker et géométrie V2 ;
- `app/src/main/java/com/amaury/pointage/LightDirectionController.kt` — éclairage piloté par tracker V2 ;
- `app/src/main/java/com/amaury/pointage/CelestialLightingState.kt` — état lumineux partagé avec invalidation explicite ;
- `app/src/main/java/com/amaury/pointage/ThemeFrameStyler.kt` — protection explicite de l’horloge canonique.

## État actuel

Le moteur astronomique, l’acquisition Android, la position altitude/azimut des astres et l’orientation physique de la lumière sur la Lune sont maintenant centralisés dans V2. La phase lunaire, la géométrie des éclipses et la qualité de la position ne reposent plus sur les approximations historiques.

Avant de considérer le rendu principal définitivement validé, il reste une validation **sur appareil réel** : vérifier visuellement le déplacement radial Soleil/Lune sur une journée, le comportement près de l’horizon, la phase/ombre lunaire et le maintien de l’horloge canonique sur les différents onglets.

Après cette validation seulement :

1. supprimer `heroClockHands` et le code mort qui le cible ;
2. décider séparément si le widget Android doit obtenir la même parité céleste V2.
