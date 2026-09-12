# HoraTrack — Audit qualité localisation du suivi céleste V2

## Périmètre

Audit limité à la localisation utilisée par l’horloge céleste : fraîcheur, précision horizontale, arbitrage entre providers Android et conséquences sur la position du Soleil/de la Lune. Aucun changement pointage, paie, juridique ou Firebase.

## Question initiale : 2 km sont-ils trop permissifs ?

À première vue, `MAX_LOCATION_ACCURACY_METERS = 2000 m` semble très large. Pour une carte céleste, il faut cependant convertir cette erreur linéaire en erreur angulaire locale.

Avec un rayon terrestre d’environ 6 371 km :

`2000 / 6 371 000 rad ≈ 0,018°`

Même dans le pire cas géométrique simple, une erreur horizontale de 2 km ne peut donc pas expliquer un Soleil décalé de plusieurs degrés sur le cadran. Cette contribution est aussi inférieure à l’erreur maximale déjà mesurée du solveur lunaire léger (~0,0605°) et reste sous la résolution visuelle utile de l’horloge.

**Conclusion : le seuil de 2 km n’est pas resserré arbitrairement.** Une localisation plus précise reste évidemment préférable, mais la cause d’une grosse erreur visuelle doit être recherchée ailleurs avant d’accuser ce seuil.

## Défaut réel trouvé : le provider le plus récent pouvait écraser une bonne position

Le tracker choisissait auparavant essentiellement la mesure au timestamp le plus récent. Avec plusieurs providers Android actifs, une mesure réseau récente mais hors tolérance pouvait remplacer une position GPS encore valide et précise.

Conséquence possible :

- GPS valide disponible ;
- arrivée d’une mesure plus récente mais très imprécise ;
- cette mesure devenait la position courante ;
- la politique fail-closed la classait ensuite `INACCURATE` ;
- le ciel pouvait disparaître alors qu’une position valide était encore disponible.

### Correction

`CelestialTrackingPolicyV2.shouldReplaceLocation()` arbitre désormais les mesures :

- une position `VALID` est toujours préférée à une position non qualifiée ;
- une mesure récente mais hors tolérance ne peut plus écraser une position encore `VALID` ;
- entre deux positions valides, la plus fraîche gagne ;
- à âge égal, la meilleure précision horizontale départage ;
- si aucune position n’est valide, la mesure la plus exploitable reste conservée afin que le diagnostic de qualité reste cohérent.

Le même arbitrage est utilisé pour :

- les mises à jour live ;
- la combinaison live + last-known ;
- le choix entre les différents last-known providers.

## Robustesse d’enregistrement des providers

L’ancien code entourait toute la boucle `requestLocationUpdates()` d’un seul `runCatching`. Une exception sur un provider pouvait donc interrompre l’enregistrement des providers suivants.

Chaque provider est maintenant enregistré dans son propre bloc protégé. Un provider défaillant n’empêche plus GPS/réseau restant de continuer à alimenter le suivi.

## Fraîcheur maximale

La position était acceptée jusqu’à 10 minutes. Avec une acquisition active demandée toutes les 30 secondes, 10 minutes représentent déjà de nombreuses mises à jour manquées.

Le seuil fail-closed est ramené à **5 minutes**. Le but n’est pas d’améliorer artificiellement la précision du GPS, mais d’éviter qu’une ancienne position soit encore présentée comme la position locale réelle lorsque l’utilisateur a pu se déplacer.

L’âge continue d’être mesuré en priorité avec `Location.elapsedRealtimeNanos` et `SystemClock.elapsedRealtimeNanos`, donc indépendamment des corrections de l’horloge murale Android.

## Ce que cet audit permet d’écarter

Une erreur GPS de quelques centaines de mètres, ou même proche de 2 km, ne peut pas produire à elle seule un décalage de plusieurs degrés du Soleil sur le cadran. Après cette correction, si le déplacement apparent reste franchement faux sur téléphone, les suspects prioritaires restent :

1. boussole/magnétomètre perturbé ;
2. qualité du cap fournie par Android ;
3. chaîne Nord magnétique → Nord vrai ;
4. transformation du cap vers le cadran ;
5. comportement spécifique des axes du capteur sur l’appareil testé.

## Tests ajoutés

`CelestialTrackingPolicyV2Test` couvre désormais notamment :

- position récente et précise valide ;
- position trop ancienne ;
- timestamp futur anormal ;
- précision inconnue ;
- mesure récente mais hors tolérance qui ne doit pas remplacer une bonne position ;
- position fraîche valide qui remplace une ancienne position devenue périmée ;
- arbitrage entre deux positions valides ;
- départage par précision à âge égal.

## État après audit

La localisation n’est plus choisie sur la seule récence. Le tracker privilégie d’abord la **qualification**, puis la fraîcheur. Le seuil de précision de 2 km est conservé car son impact astronomique angulaire est déjà faible ; la fraîcheur maximale passe en revanche de 10 à 5 minutes et l’arbitrage multi-provider est corrigé.
