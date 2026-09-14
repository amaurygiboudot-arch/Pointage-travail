# HoraTrack — Audit céleste V2 : chaîne Nord réel → cadran

## Périmètre

Cet audit vérifie uniquement la transformation qui place un azimut astronomique sur le cadran : repère capteur Android, Nord magnétique, déclinaison, cap vrai du téléphone et angle final autour de la Terre centrale.

Le moteur astronomique Soleil/Lune, la paie, le pointage, le juridique et Firebase sont hors de ce contrôle.

## Invariant attendu

Pour HoraTrack, la Terre reste au centre et le haut du cadran représente le cap réel du téléphone.

La chaîne doit donc respecter exactement :

```text
repère Android magnétique
        |
        +--> correction de déclinaison
        v
repère local Est / Nord vrai / Zénith
        |
        +--> cap du haut de l'écran
        v
cap vrai stabilisé
        |
        +--> delta = azimut astre - cap
        v
cadran :
  0°   -> 12 h
 +90°  -> 3 h
 180°  -> 6 h
 -90°  -> 9 h
```

La convention est cohérente avec Android : l'azimut de boussole vaut 0° lorsque le haut de l'appareil pointe au Nord, 90° à l'Est, 180° au Sud et 270° à l'Ouest.

## Contrôle de la déclinaison

`GeomagneticField.getDeclination()` retourne un angle positif lorsque le champ magnétique est décalé vers l'Est par rapport au Nord vrai.

La transformation utilisée par `CelestialTrackerV2` :

```text
E_true = E_mag * cos(D) + N_mag * sin(D)
N_true = -E_mag * sin(D) + N_mag * cos(D)
```

est cohérente avec cette convention. Aucun signe inversé n'a été trouvé dans cette étape.

## Défaut trouvé — singularité déplacée sur le roulis

Le lot précédent avait volontairement abandonné l'azimut Euler brut près de la verticale et déduisait le cap du repère 3D.

Cependant, `headingFromFrame()` choisissait en priorité `Up × Right` dès que l'axe droit possédait la moindre projection horizontale.

Cette stratégie supprimait la singularité de pitch mais créait une autre singularité : lorsque le téléphone est roulé au-delà de 90° autour de son axe haut, l'axe droit traverse la verticale puis sa projection horizontale change de signe. `Up × Right` peut alors basculer de 180° alors que le haut physique du téléphone continue de viser exactement la même direction.

Conséquence possible : Soleil et Lune peuvent sauter de l'autre côté de la Terre centrale sous certaines postures inclinées/retournées.

## Correction V2

`headingFromFrame()` suit maintenant cette priorité :

1. si le tracker a déjà injecté un cap filtré, ce cap reste prioritaire ;
2. si le haut physique de l'écran possède une projection horizontale suffisante, son azimut direct définit 12 h ;
3. seulement lorsque le haut est presque vertical, `Up × Right` sert de prolongement horizontal de secours ;
4. un dernier fallback sur la normale évite uniquement les NaN sur un frame ancien ou incomplet.

Le seuil de bascule est volontairement limité à une petite zone autour de la verticale (`topHorizontal < 0,20`).

Cette approche suit la sémantique physique de l'écran dans les postures ordinaires tout en conservant la continuité au voisinage de la verticale.

## Tests ajoutés

`CelestialScreenGeometryV2Test` verrouille maintenant notamment :

- cap = azimut de l'astre → astre à 12 h ;
- azimut + 90° → 3 h ;
- azimut + 180° → 6 h ;
- azimut - 90° → 9 h ;
- continuité téléphone à plat → incliné → vertical ;
- inclinaison dans les deux sens sans retournement de 180° ;
- roulis supérieur à 90° avec haut physique toujours au Nord : le ciel reste au Nord et ne se retourne plus.

## Résultat

La chaîne mathématique après correction est cohérente degré par degré :

```text
azimut astronomique vrai
       -
cap téléphone vrai stabilisé
       =
angle relatif sur le cadran
```

Une grosse erreur restante observée sur téléphone ne devrait plus venir d'un signe Est/Ouest ou d'une inversion cardinale dans cette chaîne.

Les suspects restant à auditer en priorité sont alors la qualité réelle du magnétomètre/rotation vector, la calibration du capteur, les perturbations magnétiques locales et la façon dont HoraTrack réagit lorsque Android annonce une mauvaise précision de cap.

## Limite de plateforme

`GeomagneticField` dépend du modèle magnétique embarqué par la version Android. Android documente encore WMM-2020 sur certaines versions, modèle officiellement centré sur la période 2020–2025 mais annoncé comme restant exploitable quelques années après. Cela peut créer une petite erreur de déclinaison, mais pas expliquer à lui seul un décalage massif de plusieurs dizaines de degrés.
