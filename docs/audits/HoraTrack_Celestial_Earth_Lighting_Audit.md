# HoraTrack — Audit V2 éclairage de la Terre centrale

## Périmètre

Audit limité à la relation entre le Soleil astronomique V2, la carte céleste 360° et l’éclairage de la Terre dessinée au centre de l’horloge.

## Défaut trouvé

Le Soleil graphique est volontairement masqué lorsque son centre passe sous le seuil de visibilité du disque près de l’horizon. `LightDirectionController` réutilisait cette projection graphique pour alimenter `CelestialLightingState.sunDirX/Y`.

Conséquence : dès que le Soleil passait sous l’horizon, la projection devenait `null`, la direction solaire partagée était effacée et `HpAnalogClockView` retombait sur une direction fixe de secours. La Terre centrale cessait donc d’être orientée par le vrai Soleil précisément pendant la nuit.

C’était une confusion entre deux notions différentes :

- **visibilité locale du sprite Soleil** : dépend de l’horizon ;
- **direction physique du Soleil par rapport à l’observateur** : existe 24 h/24, y compris quand le Soleil est sous l’horizon.

## Correction V2

`LightDirectionController` calcule maintenant la direction solaire terrestre à partir de l’azimut réel du Soleil et du cap qualifié du téléphone, sans passer par la fonction qui masque les astres sous l’horizon.

La convention reste identique à la carte céleste :

- Soleil dans le cap = direction vers 12 h ;
- +90° = droite ;
- +180° = bas ;
- -90° = gauche.

La direction n’est publiée que lorsque le snapshot céleste et le cap sont qualifiés. Si GPS ou boussole ne permettent plus de prétendre connaître une direction réelle, `CelestialLightingState.clearSunDirection()` reste le comportement fail-closed.

## Ce qui ne change pas

- le sprite Soleil reste masqué sous son seuil astronomique de visibilité ;
- la Lune reste le corps actif de l’éclairage d’interface la nuit ;
- les calculs de phase et d’éclipse restent indépendants ;
- aucune donnée de paie, pointage, droit ou Firebase n’est touchée.

## Limite visuelle assumée

La Terre centrale est un symbole graphique de l’observateur terrestre, pas un globe 3D texturé dont longitude, rotation terrestre et caméra spatiale seraient physiquement simulées. La correction garantit que son relief directionnel ne reçoit plus une fausse direction fixe la nuit ; elle ne transforme pas ce PNG en moteur de rendu planétaire 3D.

## Validation attendue sur téléphone

Lors d’un test du soir ou de nuit, tourner le téléphone doit maintenant faire tourner de façon continue la direction d’éclairage de la Terre centrale selon l’azimut réel du Soleil sous l’horizon, sans retour brutal à une direction arbitraire.