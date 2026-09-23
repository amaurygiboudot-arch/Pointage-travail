# HoraTrack V2 — Fond de ciel réel / constellations de l'Accueil

## But utilisateur

Sur **Accueil uniquement**, l'arrière-plan doit représenter le ciel réellement présent au-dessus de l'utilisateur au même instant.

Règle produit non négociable :

> Ce que l'utilisateur voit sur le téléphone doit correspondre au ciel qu'il peut voir en levant la tête depuis son point GPS réel.

Ce lot ne doit jamais transformer les constellations en décoration fixe.

## Entrées physiques

Le rendu dépend exclusivement de données réelles et qualifiées :

- latitude / longitude GPS qualifiées par le tracker céleste ;
- date et heure Unix réelles ;
- Nord vrai, pas Nord magnétique présenté comme vrai ;
- orientation qualifiée du téléphone ;
- catalogue stellaire équatorial J2000 ;
- position géométrique du Soleil pour atténuer les étoiles avec la lumière du ciel.

Si le GPS ou l'orientation n'est pas qualifié, l'application ne doit pas prétendre que le fond est aligné sur le ciel réel.

## Source de données stellaire

Source retenue pour le catalogue d'étoiles brillantes :

- NASA / HEASARC — Bright Star Catalog (BSC5P) ;
- environ 9 110 entrées ;
- étoiles jusqu'à environ magnitude visuelle 6,5 ;
- positions équatoriales J2000 disponibles ;
- magnitude visuelle disponible ;
- catalogue public référencé par HEASARC.

Référence publique :
https://heasarc.gsfc.nasa.gov/W3Browse/catalog/bsc5p.html

Métadonnées de licence / accès :
https://catalog.data.gov/dataset/bright-star-catalog

Le catalogue final doit être **pré-généré et embarqué dans l'application**. Aucune requête réseau HEASARC ne doit être nécessaire pour afficher le ciel en production.

## Géométrie V2

Les moteurs purs introduits dans ce lot font :

1. précession J2000 -> date courante ;
2. temps sidéral local depuis heure Unix + longitude ;
3. ascension droite / déclinaison -> azimut / altitude locale ;
4. réfraction atmosphérique uniquement pour la position graphique ;
5. rejet des objets sous l'horizon apparent ;
6. Android et iOS : projection dans le repère physique réel de l'écran, avec prise en compte de l'inclinaison/roulis et rejet des étoiles derrière le téléphone ;
7. iOS n'active cette couche physique que lorsque Core Motion fournit un repère vrai nord + vertical qualifié ; aucun repère magnétique/arbitraire n'est promu silencieusement en nord vrai ;
8. extinction progressive du fond stellaire en fonction de l'altitude réelle du Soleil.

Aucune position d'étoile ne doit être inventée ou ajustée visuellement pour « faire joli ».

## Constellations

L'IAU définit les 88 constellations comme des régions du ciel. Les figures en « bâtons » reliant les étoiles ne sont pas une géométrie astronomique unique universelle.

HoraTrack doit donc séparer :

- **réalité astronomique** : positions des étoiles et appartenance aux constellations ;
- **habillage graphique facultatif** : traits reliant certaines étoiles.

Une carte de segments ne doit être intégrée que si sa provenance et son droit d'utilisation sont clairement documentés. Aucun algorithme ne doit inventer automatiquement des segments qui pourraient représenter une fausse constellation.

## Rendu Accueil

Le rendu final prévu :

- couche derrière tous les contenus de l'Accueil ;
- jamais affichée dans Aujourd'hui, Historique, Salaire, Analyses ou Paramètres ;
- petites étoiles dont taille/opacité dépend de la magnitude ;
- constellations réellement au-dessus de l'horizon ;
- labels discrets uniquement si la lisibilité reste bonne ;
- disparition progressive en plein jour ;
- apparition progressive au crépuscule ;
- aucune gêne pour le contraste des textes et commandes ;
- respect du masquage automatique des onglets Accueil.

## Confidentialité

- les coordonnées GPS restent traitées localement pour le rendu ;
- aucune coordonnée brute ne doit être envoyée à un service de catalogue ;
- aucune télémétrie de ciel ne doit contenir longitude/latitude précises ;
- le catalogue est embarqué pour supprimer le besoin d'une requête réseau à chaque rendu.

## Performance / batterie

Objectif :

- calcul du ciel stellaire à fréquence basse et bornée ;
- réutiliser le ticker céleste existant ;
- ne pas faire 9 110 transformations à chaque frame graphique ;
- cache des positions équatoriales précessées par tranche temporelle raisonnable ;
- projection écran uniquement des étoiles candidates visibles ;
- aucun nouveau service de localisation ;
- aucun nouveau capteur : réutiliser le tracker céleste existant.

## Agents requis

### celestial_system
Valider :
- temps sidéral ;
- précession ;
- conversion équatorial -> horizontal ;
- horizon et réfraction ;
- cohérence Soleil / ciel stellaire ;
- absence de faux ciel.

### ui_ux
Valider :
- discrétion du fond ;
- contraste ;
- non-interférence avec les commandes ;
- comportement avec les onglets masqués ;
- lisibilité petits/grands écrans.

### mobile_platforms
Valider :
- repères capteurs Android/iOS ;
- rotation portrait/paysage ;
- pitch/roll ;
- orientation physique cohérente sur marques et modèles différents ;
- comportement sans capteur ou avec capteur non fiable.

### performance_battery
Valider :
- cadence de calcul ;
- allocations ;
- mémoire du catalogue ;
- impact batterie/GPU ;
- cache et invalidations.

### security_privacy
Valider :
- absence d'envoi GPS ;
- source catalogue embarquée ;
- absence de nouvelle surface réseau ;
- logs sans position précise.

### team_lead -> qa_reviewer -> control_gate
Ne PASS qu'après preuves des spécialistes ci-dessus sur le HEAD exact.

## Critères de sortie

Le lot complet n'est validé que si :

- une étoile testée à une date/position connues tombe au bon azimut/altitude ;
- une étoile derrière le téléphone n'est pas reflétée devant ;
- le fond ne s'affiche pas si le ciel directionnel n'est pas qualifié ;
- les étoiles sous l'horizon sont absentes ;
- l'opacité tend vers zéro avec le jour ;
- Android et iOS passent leurs tests/builds ;
- le catalogue embarqué a une provenance et une licence documentées ;
- la revue multi-agents réelle est attachée au HEAD de la PR.
