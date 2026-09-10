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

**Correction V2 terminée :** `CelestialScreenGeometryV2` reconstruit les vecteurs réels Soleil/Lune dans le repère local Est–Nord–Zénith, projette la direction tangentielle Lune → Soleil sur la géométrie du cadran et fournit une direction écran normalisée. Le terminateur utilise maintenant cette direction réelle plutôt que la ligne décorative entre deux sprites.

Le même principe est utilisé pour orienter l’axe de l’ombre terrestre vers l’anti-Soleil pendant une éclipse.

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
- orientation écran de l’axe d’ombre calculée vers le vrai anti-Soleil.

### ÉLEVÉ — parallaxe lunaire absente

La Lune étant proche, sa position apparente dépend de la position de l’observateur sur Terre, surtout près de l’horizon.

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

**Correction V2 terminée :** `CelestialTrackerV2` est désormais l’unique couche Android d’acquisition pour ce sous-système. `SunIndicatorView` et `LightDirectionController` s’y abonnent sans enregistrer leurs propres capteurs.

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

### MOYEN — altitude des astres non encore utilisée pour leur position radiale

L’altitude réelle participe désormais à l’orientation locale du terminateur et de l’axe d’éclipse, mais le Soleil et la Lune restent placés sur l’anneau historique selon leur azimut.

Cela préserve le design validé pendant l’audit, mais ce n’est pas encore une carte complète du dôme céleste où horizon et zénith auraient des rayons différents.

**Reste à décider après validation visuelle :** conserver l’anneau azimutal comme choix de design ou passer à une projection de dôme utilisant aussi l’altitude pour la position de l’astre.

### MOYEN — deux vues d’horloge dans `activity_main.xml`

Le dépôt possède actuellement `heroClockPermanent` et `heroClockHands`, alors que plusieurs installateurs historiques n’utilisent pas le même identifiant.

**Reste à faire :** définir une horloge canonique, rediriger toutes les références vers elle, valider visuellement, puis supprimer la vue fantôme.

### FAIBLE / PARITÉ — widget Android

Le widget dessine son propre cadran, ses aiguilles et sa Terre via `WidgetVisualRenderer`. Il ne dessine pas actuellement Soleil/Lune ni la phase réelle.

Il reste volontairement séparé tant que le rendu principal V2 n’est pas totalement validé.

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
- proximité Soleil/Lune dans le ciel local pendant la nouvelle Lune ;
- rejet d’une latitude invalide ;
- position GPS récente/précise acceptée ;
- position GPS ancienne, imprécise ou sans précision refusée ;
- direction écran de l’éclairage lunaire selon azimut, altitude et rotation du cadran ;
- direction écran de l’axe anti-solaire lors d’une éclipse.

Références astronomiques utilisées pour choisir les cas astronomiques : U.S. Naval Observatory (phases) et NASA/GSFC (catalogue des éclipses).

## Fichiers principaux du lot

- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialEngineV2.kt` — moteur astronomique pur V2 ;
- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialTrackingPolicyV2.kt` — qualification fail-closed de la localisation ;
- `app/src/main/java/com/amaury/pointage/v2/CelestialTrackerV2.kt` — acquisition Android partagée GPS + orientation ;
- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialScreenGeometryV2.kt` — géométrie physique des directions d’éclairage sur l’écran ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialEngineV2Test.kt` — références astronomiques ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialTrackingPolicyV2Test.kt` — tests qualité GPS ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialScreenGeometryV2Test.kt` — tests d’orientation écran ;
- `app/src/main/java/com/amaury/pointage/v2/HoraTrackV2.kt` — couche `CELESTIAL` ;
- `app/src/main/java/com/amaury/pointage/v2/V2LegacyPolicy.kt` — domaine `CELESTIAL` ;
- `app/src/main/java/com/amaury/pointage/CelestialEphemeris.kt` — façade historique vers V2 ;
- `app/src/main/java/com/amaury/pointage/SunIndicatorView.kt` — rendu piloté par tracker et géométrie V2 ;
- `app/src/main/java/com/amaury/pointage/LightDirectionController.kt` — éclairage piloté par tracker V2 ;
- `app/src/main/java/com/amaury/pointage/CelestialLightingState.kt` — état lumineux partagé, avec invalidation explicite.

## État actuel

Le moteur astronomique, l’acquisition Android et l’orientation physique de la lumière sur la Lune sont maintenant centralisés dans V2. La phase lunaire, la géométrie des éclipses et la qualité de la position ne reposent plus sur les approximations historiques.

Il reste deux décisions/chantiers avant de considérer le suivi céleste principal comme totalement terminé :

1. valider visuellement si les astres doivent rester sur l’anneau azimutal ou adopter une vraie projection radiale par altitude ;
2. définir l’horloge canonique puis nettoyer `heroClockPermanent` / `heroClockHands` après validation visuelle.

La parité céleste du widget Android restera une étape séparée après validation du rendu principal.
