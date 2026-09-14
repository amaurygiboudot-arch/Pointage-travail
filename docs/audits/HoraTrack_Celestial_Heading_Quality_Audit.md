# HoraTrack — Audit céleste V2 : qualité réelle du cap

## Périmètre

Cet audit concerne uniquement la confiance accordée à la boussole/orientation qui fait tourner la carte céleste 360° autour de la Terre. Il ne modifie ni le pointage, ni la paie, ni le juridique, ni Firebase.

## Défaut trouvé

`CelestialTrackerV2` exposait déjà `headingAccuracyDeg` lorsque `TYPE_ROTATION_VECTOR` fournissait une estimation d'incertitude, mais cette information était purement diagnostique : elle n'entrait pas dans `hasRealSky` et les rendus continuaient à utiliser le cap même si Android le déclarait non fiable.

Autre ambiguïté : `onAccuracyChanged(... UNRELIABLE)` remplaçait l'incertitude numérique par `null`. Or `null` peut aussi signifier qu'un téléphone fournit une orientation exploitable sans publier d'incertitude numérique. Les deux cas étaient donc impossibles à distinguer.

Enfin, aucun âge maximal n'était appliqué au dernier repère d'orientation. Si les événements capteurs s'arrêtaient, un ancien cap pouvait théoriquement rester présenté comme courant.

## Correction V2

Ajout de `CelestialHeadingPolicyV2`, politique pure Kotlin et testable, avec les états :

- `VALID` : cap récent et incertitude connue <= 15° ;
- `UNKNOWN_ACCURACY` : cap récent, pas d'incertitude numérique disponible, aucune alerte explicite du capteur ;
- `INACCURATE` : incertitude connue > 15° ;
- `UNRELIABLE` : Android signale explicitement le capteur comme non fiable ;
- `STALE` : aucun repère d'orientation frais depuis plus de 5 s ;
- `UNAVAILABLE` : aucun repère d'orientation disponible.

Le seuil de 15° est un **seuil qualité HoraTrack**, choisi pour empêcher qu'un ciel manifestement décalé soit présenté comme précis. Ce n'est pas une constante imposée par Android.

`UNKNOWN_ACCURACY` reste exploitable pour ne pas exclure les appareils dont le capteur de rotation ne fournit pas le cinquième champ d'incertitude. En revanche `INACCURATE`, `UNRELIABLE`, `STALE` et `UNAVAILABLE` ferment le rendu directionnel.

## Acquisition Android

`CelestialTrackerV2` conserve maintenant séparément :

- l'incertitude numérique `headingAccuracyDeg` quand elle existe ;
- l'état explicite `SENSOR_STATUS_UNRELIABLE` ;
- l'instant monotone du dernier repère d'orientation produit.

Le fallback accéléromètre + magnétomètre surveille lui aussi l'état de précision du magnétomètre. Ainsi un appareil dépourvu de `TYPE_ROTATION_VECTOR` ne contourne pas la politique de qualité.

## Conséquence sur le rendu

`State.hasRealSky` exige désormais une localisation valide, un repère d'orientation et un cap qualifié exploitable.

`SunIndicatorView` n'affiche plus Soleil/Lune avec une direction que le système sait mauvaise ou périmée. Le calcul jour/nuit continue cependant à utiliser l'éphéméride et le GPS : une mauvaise boussole ne rend pas l'heure astronomique fausse.

`LightDirectionController` applique la même règle. Si le cap n'est pas exploitable, la direction de relief repasse sur son angle neutre de secours et la direction solaire partagée est effacée, tandis que l'intensité et l'élévation peuvent rester issues de l'éphéméride.

## Tests

`CelestialHeadingPolicyV2Test` verrouille :

- absence d'orientation -> `UNAVAILABLE` ;
- cap récent et précis -> `VALID` ;
- précision numérique absente sans alerte -> `UNKNOWN_ACCURACY` exploitable ;
- alerte explicite Android -> `UNRELIABLE` bloqué ;
- incertitude au-delà du seuil -> `INACCURATE` bloqué ;
- cap vieux de plus de 5 s -> `STALE` bloqué ;
- âge impossible négatif -> `STALE` ;
- limite de 15° incluse dans l'état valide.

## Limites physiques

Aucune politique logicielle ne peut corriger un champ magnétique local déformé par une coque aimantée, une voiture, une machine, un haut-parleur ou une structure métallique. Elle peut seulement éviter de présenter comme exacte une mesure qu'Android sait déjà mauvaise.

Une prochaine amélioration UX pourra afficher un diagnostic discret « boussole à recalibrer » lorsque l'état est `INACCURATE` ou `UNRELIABLE`, avec une consigne de calibration. Cette aide visuelle est volontairement séparée du présent correctif afin de ne pas masquer la validation du moteur.
