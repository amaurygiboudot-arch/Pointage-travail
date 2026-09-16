# HoraTrack — Audit céleste V2 : précision brute de l’éphéméride

## Périmètre

Cet audit vérifie uniquement la précision astronomique brute de `DefaultCelestialEngineV2` : azimut et altitude géométriques du Soleil et de la Lune, plus la phase lunaire. Il ne traite pas ici la boussole Android, le dessin, la réfraction, les sprites ou la logique métier HoraTrack.

## Référence indépendante

Les valeurs V2 ont été comparées hors application à **Swiss Ephemeris 2.10.03** en mode topocentrique. Swiss Ephemeris est fondée sur une compression de l’éphéméride JPL DE431 et constitue ici un oracle indépendant du code HoraTrack.

La comparaison utilise l’altitude géométrique vraie, sans appliquer la correction graphique `AtmosphericRefractionV2`, afin de comparer la même grandeur physique.

## Grille de contrôle

Échantillon systématique 2026 :

- 5 positions : Vendée/Aizenay, Londres, Sydney, Quito, Tromsø ;
- 12 mois ;
- jours 1 et 15 ;
- 00:00, 06:00, 12:00 et 18:00 UTC ;
- soit **480 instants** et **960 positions d’astre** comparées (Soleil + Lune).

## Résultats

Erreur angulaire globale sur la sphère céleste :

| Corps | erreur moyenne | erreur médiane | erreur maximale observée |
| --- | ---: | ---: | ---: |
| Soleil | 0,0054° | 0,0050° | 0,0118° |
| Lune | 0,0222° | 0,0227° | 0,0605° |

À titre d’échelle : 0,0605° correspond à environ **3,6 minutes d’arc**. Pour l’usage HoraTrack, cette erreur est très inférieure aux erreurs courantes que peuvent produire le magnétomètre, l’environnement métallique, la calibration du téléphone ou une représentation graphique volontairement agrandie des astres.

### Phase lunaire

Sur un contrôle séparé réparti sur 2026, l’écart maximal observé sur la fraction éclairée de la Lune est d’environ **0,00037** en valeur 0–1, soit environ **0,037 point de pourcentage**. L’écart maximal d’élongation observé est d’environ **0,054°**.

## Cas de régression verrouillés dans les tests

Des points de référence indépendants sont maintenant codés dans `CelestialEngineV2Test` avec des tolérances volontairement plus larges que les écarts mesurés afin d’éviter des tests fragiles :

- Vendée, 10 septembre 2026 12:00 UTC ;
- Sydney, solstice de juin 2026 ;
- Quito, 3 mars 2026 ;
- Tromsø, solstice de décembre 2026.

Tolérances de régression :

- Soleil : 0,03° en azimut et altitude ;
- Lune : 0,08° en azimut et altitude.

Ces tests ne transforment pas Swiss Ephemeris en dépendance de l’application : les valeurs de référence sont figées dans les tests JVM.

## Conclusion de l’audit

**Aucun défaut majeur de position astronomique brute n’a été trouvé dans le moteur V2 actuel.**

Le nouveau solveur lunaire, sa parallaxe topocentrique et le calcul solaire atteignent une précision largement suffisante pour une horloge de téléphone. Une grosse erreur visible de placement du Soleil — plusieurs degrés ou un mauvais quadrant — ne doit donc plus être attribuée en premier lieu à l’éphéméride.

Pour le rendu réel sur téléphone, les sources d’erreur dominantes restantes sont plutôt :

1. le cap fourni par les capteurs et sa calibration ;
2. la correction Nord magnétique → Nord vrai ;
3. la géométrie de projection du cadran ;
4. les règles de visibilité/horizon et la réfraction ;
5. la taille graphique volontairement non angulaire des sprites Soleil/Lune.

## Limite assumée

HoraTrack n’est pas un moteur JPL/DE embarqué. Le solveur V2 reste volontairement léger. Si un futur besoin exige une précision sub-minute d’arc, des contacts d’éclipse professionnels ou de l’astrométrie scientifique, il faudra remplacer ce solveur par une vraie éphéméride haute précision et gérer ses données, tailles et mises à jour.
