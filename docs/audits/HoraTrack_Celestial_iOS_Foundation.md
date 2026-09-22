# HoraTrack — Céleste V2 iOS

## Portée livrée

Ce lot fournit la source canonique iOS de Céleste et sa parité fonctionnelle avec le moteur Android :

- calcul géométrique déterministe des positions topocentriques du Soleil et de la Lune ;
- distance et taille apparente relative des deux astres ;
- fraction éclairée, croissance/décroissance et angle du limbe lunaire ;
- géométrie physique des éclipses lunaires (pénombre, ombre, magnitudes et classification) ;
- géométrie topocentrique des éclipses solaires (partielle, annulaire et totale) ;
- réfraction atmosphérique moyenne appliquée uniquement à la projection visuelle ;
- état jour/nuit selon l'altitude solaire civile de -0,833° ;
- qualification fail-closed de la position GPS (permission, présence, âge, précision) ;
- qualification fail-closed du cap vrai (âge et précision `CLHeading`) avec attitude et fraîcheur `CoreMotion` obligatoires ;
- arbitrage empêchant une nouvelle position non qualifiée d'écraser une position encore valide ;
- écran Accueil iOS avec cadran topocentrique : azimut autour du cadran et altitude dans le rayon ;
- globe orthographique centré sur le GPS, continents hors ligne, éclairage solaire et terminateur jour/nuit ;
- phase lunaire orientée, ombre terrestre pendant une éclipse et occultation solaire avec diamètres apparents réels ;
- indication explicite quand une éclipse calculée est sous l'horizon local ;
- masquage des astres directionnels dès que le GPS, le cap vrai ou l'attitude sont non fiables ;
- action accessible vers Réglages lorsque l'autorisation de localisation est refusée ;
- tests Swift Package reproductibles pour l'astronomie et les politiques qualité.

Le moteur astronomique est isolé de Core Location, Core Motion et SwiftUI dans
`HPTravail/CelestialV2/Core`. La même implémentation est donc consommée par
l'application iOS et par les tests Swift Package ; il n'existe pas de second
calcul de Soleil/Lune dans la vue.

## Limites explicites

- Le comportement du cap, de l'inclinaison, des changements d'orientation écran et des perturbations magnétiques doit encore être validé sur plusieurs appareils physiques.
- L'absence de cap vrai fiable bloque volontairement le positionnement directionnel au lieu de revenir silencieusement au Nord magnétique.
- Le globe reste vectoriel et entièrement hors ligne ; il ne dépend d'aucune texture ou carte réseau.
- Aucun widget Céleste iOS n'est déclaré : le dépôt ne contient ni cible WidgetKit ni exigence produit associée. La parité widget demeure une décision produit séparée, comme sur Android.

## Validation

Les tests verrouillent notamment :

- nouvelle Lune du 17 février 2026 ;
- pleine Lune du 3 mars 2026 ;
- positions Soleil/Lune comparées aux références indépendantes déjà utilisées par le lot Android en Vendée et dans l'hémisphère sud ;
- éclipse solaire totale du 12 août 2026 au maximum NASA/GSFC ;
- éclipses lunaires totale, partielle et pénombrale, avec rejet du faux positif de nouvelle Lune ;
- réfraction atmosphérique et visibilité sous l'horizon ;
- quatre combinaisons pôles/ligne de changement de date et continuité entre +180° et -180° ;
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
de compilation iOS et les tests Swift sont donc exécutés par le workflow macOS
avant fusion. Une validation sur appareil réel reste obligatoire avant de
qualifier le capteur et le rendu de précis en conditions d'utilisation.
