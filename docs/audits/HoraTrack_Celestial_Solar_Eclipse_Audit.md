# HoraTrack — Audit céleste V2 — proximité Soleil/Lune et éclipses solaires

## Périmètre

Ce complément reste strictement limité à l'horloge céleste V2. Il traite le cas où les symboles Soleil et Lune deviennent très proches sur le cadran Terre-centré.

## Défaut identifié

Les symboles graphiques de l'horloge sont volontairement beaucoup plus grands que les diamètres angulaires réels du Soleil et de la Lune. Une simple nouvelle Lune peut donc produire un chevauchement visuel des PNG alors que les deux disques physiques ne se recouvrent pas depuis la position de l'utilisateur.

Une proximité graphique ne doit jamais être interprétée comme une éclipse solaire.

La nouvelle Lune n'est qu'une condition nécessaire à une éclipse solaire, pas une condition suffisante. Le plan orbital lunaire est incliné : la plupart des nouvelles Lunes passent au-dessus ou au-dessous du disque solaire apparent.

## Correction V2 ajoutée

`SolarEclipseGeometryV2` calcule maintenant une géométrie d'occultation indépendante de la taille décorative des sprites :

- séparation angulaire topocentrique Soleil/Lune ;
- rayon angulaire apparent du Soleil à partir de sa distance ;
- rayon angulaire apparent de la Lune à partir de sa distance ;
- aire réelle d'intersection des deux disques ;
- fraction de surface solaire occultée ;
- classification `NONE / PARTIAL / ANNULAR / TOTAL`.

Règles :

- séparation >= somme des rayons → `NONE` ;
- recouvrement sans inclusion complète → `PARTIAL` ;
- Lune entièrement dans le Soleil et plus petite → `ANNULAR` ;
- Lune couvrant entièrement le Soleil et au moins aussi grande → `TOTAL`.

## Verrou de non-fausse-éclipse

Un test de référence utilise une nouvelle Lune avec Soleil et Lune proches dans le ciel mais hors bande locale d'éclipse. Le résultat attendu est explicitement `NONE`.

Des tests géométriques synthétiques verrouillent également les quatre classes d'occultation et la fraction de surface solaire masquée.

## Limite de précision assumée

Le calcul permet de distinguer physiquement une proximité d'un recouvrement et d'éviter une fausse éclipse graphique. Cependant `CelestialEngineV2` reste un moteur astronomique léger, non une éphéméride JPL/DE. Il ne faut donc pas utiliser cette couche pour annoncer des secondes de contact d'éclipse ou une limite géographique de totalité au niveau kilométrique.

Pour le rendu de l'horloge, l'objectif est :

1. ne jamais simuler une occultation uniquement parce que les gros sprites se touchent ;
2. ne dessiner une occultation solaire que si `SolarEclipseGeometryV2` indique un recouvrement physique ;
3. garder les centres Soleil/Lune à leur vraie position sur la carte 360° ;
4. conserver la Terre au centre comme référentiel de l'observateur.

## Fichiers

- `app/src/main/java/com/amaury/pointage/v2/engine/SolarEclipseGeometryV2.kt`
- `app/src/test/java/com/amaury/pointage/v2/engine/SolarEclipseGeometryV2Test.kt`

## Suite de l'audit

La géométrie physique est maintenant séparée de la taille décorative des icônes. Le prochain raccordement doit appliquer cette information dans `SunIndicatorView` : les sprites surdimensionnés ne doivent jamais produire seuls un masque Soleil/Lune ; lors d'une vraie éclipse, le masque doit utiliser le rapport des rayons angulaires réels et la séparation réelle.
