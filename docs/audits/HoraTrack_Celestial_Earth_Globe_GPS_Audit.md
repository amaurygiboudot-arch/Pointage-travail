# HoraTrack — Audit Terre centrale / globe GPS V2

## Décision utilisateur verrouillée

La Terre reste au centre de l'horloge. Elle n'est plus un PNG fixe : elle doit être une sphère géographique orientée automatiquement vers la position réelle de l'utilisateur.

Exemples attendus :
- utilisateur en France : la France est sur la face avant du globe ;
- utilisateur au Japon : le Japon est sur la face avant du globe ;
- le point GPS de l'utilisateur est exactement au centre du disque terrestre.

## Implémentation V2

- ajout de `EarthGlobeProjectionV2`, projection orthographique pure et testable ;
- le point `(latitude GPS, longitude GPS)` de l'observateur se projette en `(0,0)` avec profondeur maximale ;
- le Nord local reste vers le haut de la sphère ;
- l'antipode reste naturellement sur la face cachée ;
- ajout d'une texture géographique mondiale basse définition adaptée au petit globe, dérivée de Natural Earth (domaine public) ;
- `EarthGlobeRendererV2` reconstruit la face visible du globe à partir du GPS ;
- pays, côtes et frontières tournent réellement avec latitude/longitude au lieu de faire tourner un PNG fixe ;
- marqueur de position au centre du globe ;
- éclairage calculé sur la sphère à partir de l'azimut/altitude réels du Soleil, indépendamment du cap du téléphone ;
- le globe reste stable quand le téléphone tourne : le ciel dépend de la boussole, la géographie centrale dépend du GPS ;
- mise en cache du globe pour éviter une reconstruction à chaque frame des aiguilles ;
- actualisation de la position du globe toutes les 30 secondes ;
- si aucune localisation qualifiée n'est disponible, HoraTrack conserve temporairement l'ancien symbole Terre et n'invente pas une fausse position géographique.

## Tests

`EarthGlobeProjectionV2Test` verrouille notamment :
- position observateur exactement au centre ;
- Tokyo/Japon exactement face à l'utilisateur lorsque l'observateur est à Tokyo ;
- antipode caché ;
- Nord local vers le haut ;
- projection orthographique puis inverse sans dérive significative.

## Validation requise avant de poursuivre l'audit céleste

Ce lot doit être validé visuellement sur téléphone avant toute reprise du reste de l'audit céleste.

À vérifier sur l'APK :
1. en Vendée/France, l'Europe et la France sont bien sur la face avant ;
2. le marqueur est au centre de la Terre ;
3. le globe a bien une apparence sphérique et reste lisible à la taille réelle de l'horloge ;
4. tourner/incliner le téléphone ne fait pas tourner la géographie terrestre ;
5. le rendu ne dégrade pas la fluidité des aiguilles.

Tant que ces cinq points ne sont pas validés, le reste de l'audit céleste reste volontairement en attente.
