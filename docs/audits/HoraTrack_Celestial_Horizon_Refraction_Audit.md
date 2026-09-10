# HoraTrack — Audit céleste V2 : horizon, lever/coucher et réfraction

## Périmètre

Ce document complète l'audit céleste uniquement. Aucun changement pointage, paie, juridique, Firebase ou logique métier.

## Défaut identifié

La carte 360° utilisait directement l'altitude géométrique calculée par l'éphéméride pour placer Soleil et Lune sur le rayon du cadran. Près de l'horizon, cela ne correspond pas exactement à ce que voit l'observateur : l'atmosphère relève optiquement les astres.

Le code appelait aussi `CIVIL_HORIZON_DEG` la valeur `-0.833°`. Ce nom était scientifiquement incorrect : `-0.833°` n'est pas le crépuscule civil. C'est la valeur standard utilisée pour le centre géométrique du Soleil au lever/coucher, soit environ 50 minutes d'arc sous un horizon plat et dégagé.

Ces 50 minutes d'arc correspondent approximativement à :

- 34' de réfraction atmosphérique moyenne à l'horizon ;
- 16' de demi-diamètre solaire apparent.

## Correction V2

### 1. Réfraction séparée de l'éphéméride

`AtmosphericRefractionV2` calcule une correction d'altitude apparente avec les formules par morceaux du NOAA Solar Calculator.

Cette correction est utilisée uniquement pour la position graphique dans le cadran. Les calculs astronomiques restent fondés sur l'altitude géométrique : phase lunaire, éclipses, coordonnées topocentriques et état jour/nuit ne sont pas contaminés par un artifice de rendu.

### 2. Horizon renommé correctement

La projection utilise maintenant `STANDARD_DISK_HORIZON_DEG = -50/60°`.

L'ancien nom `CIVIL_HORIZON_DEG` est conservé temporairement comme alias déprécié afin de ne pas casser d'éventuels appelants, mais il ne doit plus être utilisé comme terme scientifique.

### 3. Position radiale apparente

Pour un astre proche de l'horizon :

- l'azimut reste l'azimut topocentrique réel ;
- l'altitude géométrique est corrigée pour la réfraction uniquement au moment de la projection ;
- un astre à 0° géométrique est donc dessiné légèrement au-dessus du bord géométrique, comme il apparaît réellement ;
- un astre légèrement sous l'horizon géométrique peut encore apparaître au bord du cadran si la réfraction standard le relève.

### 4. Limites assumées

La réfraction réelle varie avec la température, la pression, l'humidité et les couches d'air. Le modèle est donc une approximation standard, pas une promesse à la minute d'arc près.

HoraTrack ne connaît pas non plus l'horizon local réel : bâtiment, arbre, colline, falaise ou relief. Un astre peut être géométriquement et atmosphériquement visible tout en étant physiquement masqué par le paysage.

Pour la Lune, le seuil exact du bord visible varie légèrement avec son demi-diamètre apparent. Comme la position lunaire V2 est déjà topocentrique, le seuil standard de disque autour de `-0.83°` est cohérent pour le rendu léger, mais une future précision de niveau éphéméride professionnelle pourrait utiliser le rayon angulaire lunaire instantané au lieu d'une constante.

## Tests ajoutés

Les tests verrouillent notamment :

- environ 0,48° de correction à l'horizon géométrique dans le modèle NOAA ;
- diminution rapide de la réfraction avec l'altitude ;
- absence de correction au zénith ;
- absence d'extrapolation artificielle loin sous l'horizon ;
- déplacement radial apparent près de l'horizon ;
- visibilité juste au-dessus du seuil standard et masquage juste en dessous.

## État après correction

La carte céleste reste Terre au centre et 360°. Cette correction ne change ni le référentiel ni les orbites : elle améliore seulement la correspondance entre l'altitude astronomique calculée et la position que l'utilisateur perçoit réellement près du lever et du coucher.
