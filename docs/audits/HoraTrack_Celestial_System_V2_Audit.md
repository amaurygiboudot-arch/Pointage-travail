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

Le moteur expose aussi l’angle de position du limbe éclairé (`brightLimbPositionAngleDeg`) pour une future projection d’écran totalement physique.

### CRITIQUE — ombre terrestre / éclipses

L’ancienne détection utilisait un seuil empirique proche de 180° et dessinait une ombre d’environ un rayon lunaire.

Ce modèle n’est pas physique : à la distance de la Lune, l’ombre centrale de la Terre (umbra) est en réalité nettement plus grande que le disque lunaire, et la pénombre doit être calculée séparément.

**Correction V2 :**

- calcul de l’axe anti-solaire ;
- distance du centre lunaire à l’axe de l’ombre ;
- rayon réel de l’umbra à la distance instantanée de la Lune ;
- rayon réel de la pénombre ;
- magnitude pénombrale ;
- magnitude ombrale ;
- classification `NONE / PENUMBRAL / PARTIAL / TOTAL` ;
- rendu de l’umbra et de la pénombre avec les rayons V2 au lieu d’un disque arbitraire.

### ÉLEVÉ — parallaxe lunaire absente

La Lune étant proche, sa position apparente dépend fortement de la position de l’observateur sur Terre, surtout près de l’horizon.

**Correction V2 :** calcul topocentrique de la Lune à partir de la latitude, longitude et altitude de l’observateur avant conversion en azimut/altitude.

### ÉLEVÉ — faux ciel sans GPS

Le rendu historique inventait des positions Soleil/Lune de secours à partir de l’heure lorsque le GPS était absent.

Cela contredit l’objectif de suivi céleste réel.

**Correction V2 :** `SunIndicatorView` ne dessine plus de faux Soleil/Lune lorsque la position n’est pas disponible. L’horloge reste utilisable, mais le ciel réel est fail-closed.

### ÉLEVÉ — double source de calcul astronomique

`SunIndicatorView` et `LightDirectionController` recalculaient séparément Soleil/Lune.

**Correction actuelle :** les deux passent maintenant par `HoraTrackV2.celestial`, donc les mathématiques ont une source unique.

**Reste à faire :** centraliser aussi l’acquisition GPS + capteurs dans un tracker V2 unique pour éviter deux abonnements Android parallèles.

### MOYEN — inclinaison du téléphone encore inutilisée pour la position autour du cadran

`devicePitch` est lu mais la projection circulaire actuelle utilise seulement l’azimut. Le Soleil et la Lune suivent donc correctement la direction horizontale autour du cadran, mais pas encore une projection complète du dôme céleste.

**Décision de cette première intégration :** conserver la géométrie visuelle validée pour ne pas déplacer brutalement les éléments.

**Étape suivante recommandée :** ajouter une projection V2 optionnelle azimut + altitude + pitch/roll, testable sans casser le mode circulaire historique.

### MOYEN — orientation exacte du terminateur à l’écran

V2 calcule maintenant le véritable angle astronomique du limbe éclairé. Le rendu actuel garde encore l’orientation visuelle vers la position du Soleil affichée autour du cadran.

La fraction éclairée est désormais réelle, mais la transformation finale de l’angle astronomique vers les axes physiques de l’écran devra utiliser la matrice complète d’orientation du téléphone pour être rigoureusement exacte.

### MOYEN — qualité/fraîcheur de la dernière position GPS

Le système utilise encore `getLastKnownLocation()` sans politique V2 explicite d’âge maximal ni de précision minimale.

**Reste à faire :** un `CelestialTrackerV2` devra qualifier la position (`fresh/stale/too inaccurate`) et refuser une position trop ancienne ou trop imprécise.

### MOYEN — deux vues d’horloge dans `activity_main.xml`

Le dépôt possède actuellement `heroClockPermanent` et `heroClockHands`, alors que plusieurs installateurs historiques n’utilisent pas le même identifiant.

**Reste à faire :** définir une horloge canonique avant suppression de l’ancienne vue, puis vérifier toutes les références.

### FAIBLE / PARITÉ — widget Android

Le widget dessine son propre cadran, ses aiguilles et sa Terre via `WidgetVisualRenderer`. Il ne dessine pas actuellement le Soleil/Lune ni la phase réelle.

Il n’a donc pas été étendu dans ce lot. Si la parité céleste du widget devient un objectif produit, il devra consommer un snapshot V2 lui aussi.

## Architecture V2 mise en place

```text
GPS + heure
   |
   v
HoraTrackV2.celestial
   |
   +--> CelestialEngineV2
   |      +--> Soleil géocentrique
   |      +--> Lune orbitale corrigée
   |      +--> parallaxe topocentrique
   |      +--> phase réelle
   |      +--> angle du limbe éclairé
   |      +--> umbra / pénombre / magnitude éclipse
   |
   +--> SunIndicatorView (rendu)
   |
   +--> LightDirectionController (éclairage UI)

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
- rejet d’une latitude invalide.

Références astronomiques utilisées pour choisir ces cas : U.S. Naval Observatory (phases) et NASA/GSFC (catalogue des éclipses).

## Fichiers modifiés / ajoutés dans le lot

- `app/src/main/java/com/amaury/pointage/v2/engine/CelestialEngineV2.kt` — nouveau moteur pur V2 ;
- `app/src/test/java/com/amaury/pointage/v2/engine/CelestialEngineV2Test.kt` — références de non-régression ;
- `app/src/main/java/com/amaury/pointage/v2/HoraTrackV2.kt` — couche `CELESTIAL` + moteur enregistré ;
- `app/src/main/java/com/amaury/pointage/v2/V2LegacyPolicy.kt` — domaine `CELESTIAL` ;
- `app/src/main/java/com/amaury/pointage/CelestialEphemeris.kt` — façade historique vers V2 ;
- `app/src/main/java/com/amaury/pointage/SunIndicatorView.kt` — rendu piloté par snapshot V2 + vraie géométrie d’éclipse ;
- `app/src/main/java/com/amaury/pointage/LightDirectionController.kt` — éclairage naturel piloté par le même moteur V2.

## État à la fin de ce lot

Le moteur astronomique est maintenant intégré à l’architecture V2 et devient la source mathématique unique. La phase de Lune et la géométrie des éclipses ne reposent plus sur l’ancien calcul approximatif.

Il reste volontairement trois chantiers avant de considérer le suivi céleste V2 comme totalement terminé :

1. tracker Android V2 unique pour GPS + capteurs + politique de fraîcheur ;
2. projection écran 3D utilisant altitude + pitch/roll et l’angle exact du limbe éclairé ;
3. nettoyage de la double vue d’horloge après validation visuelle et tests de régression.
