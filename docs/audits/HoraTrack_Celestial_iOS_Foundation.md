# HoraTrack — socle Céleste V2 iOS

## Portée livrée

Ce lot introduit une première source canonique iOS pour Céleste sans modifier Android :

- calcul géométrique déterministe des positions topocentriques du Soleil et de la Lune ;
- distance et taille apparente relative des deux astres ;
- fraction éclairée, croissance/décroissance et angle du limbe lunaire ;
- état jour/nuit selon l'altitude solaire civile de -0,833° ;
- qualification fail-closed de la position GPS (permission, présence, âge, précision) ;
- qualification fail-closed du cap vrai (âge et précision `CLHeading`) avec attitude et fraîcheur `CoreMotion` obligatoires ;
- arbitrage empêchant une nouvelle position non qualifiée d'écraser une position encore valide ;
- écran Accueil iOS avec cadran topocentrique : azimut autour du cadran et altitude dans le rayon ;
- masquage des astres directionnels dès que le GPS, le cap vrai ou l'attitude sont non fiables ;
- tests Swift Package reproductibles pour l'astronomie et les politiques qualité.

Le moteur astronomique est isolé de Core Location, Core Motion et SwiftUI dans
`HPTravail/CelestialV2/Core`. La même implémentation est donc consommée par
l'application iOS et par les tests Swift Package ; il n'existe pas de second
calcul de Soleil/Lune dans la vue.

## Limites explicites

Ce lot est un socle sûr, pas encore une parité graphique et physique complète avec Android :

- pas encore de géométrie d'éclipse solaire ou lunaire côté iOS ;
- pas encore de rendu du terminateur lunaire ni de l'ombre terrestre ;
- pas encore de réfraction atmosphérique visuelle (les données restent géométriques) ;
- le globe iOS est une représentation vectorielle simple, sans la texture ni l'éclairage terrestre du rendu Android ;
- pas de widget iOS céleste ;
- le comportement du cap, de l'inclinaison, des changements d'orientation écran et des perturbations magnétiques doit encore être validé sur plusieurs appareils physiques ;
- l'absence de cap vrai fiable bloque volontairement le positionnement directionnel au lieu de revenir silencieusement au Nord magnétique.

## Validation

Les tests verrouillent notamment :

- nouvelle Lune du 17 février 2026 ;
- pleine Lune du 3 mars 2026 ;
- positions Soleil/Lune comparées aux références indépendantes déjà utilisées par le lot Android en Vendée et dans l'hémisphère sud ;
- continuité du mouvement solaire ;
- latitude invalide ;
- GPS récent/précis, ancien ou imprécis ;
- non-remplacement d'un GPS valide par une mesure plus récente mais mauvaise ;
- cap périmé ou imprécis.

Commande officielle à exécuter sur macOS/Xcode :

```bash
bash scripts/agent-toolbox.sh ios-tests
bash scripts/agent-toolbox.sh ios-build
```

Le conteneur Linux de réalisation ne possède ni Swift ni Xcode. La validation
de compilation iOS et les tests Swift doivent donc être exécutés par le workflow
macOS avant fusion. Une validation sur appareil réel reste obligatoire avant de
qualifier le capteur et le rendu de précis en conditions d'utilisation.
