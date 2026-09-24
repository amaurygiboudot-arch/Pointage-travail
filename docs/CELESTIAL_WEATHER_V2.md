# Céleste V2 — météo et couverture nuageuse

## Règle produit

La météo est une couche atmosphérique indépendante de l'astronomie.

- Soleil, Lune, étoiles et constellations conservent leurs positions calculées.
- La couverture nuageuse peut uniquement modifier leur visibilité.
- Une météo absente, périmée ou en erreur n'est jamais remplacée par une météo inventée.
- L'Accueil est le seul écran qui rend cette couche.

## Données utilisées

Le contexte météo canonique peut consommer :

- couverture nuageuse totale ;
- couverture basse / moyenne / haute ;
- code météo ;
- précipitations ;
- visibilité ;
- horodatage de récupération.

Chaque état externe est daté. Une donnée météo de plus de 45 minutes n'est plus utilisée pour le rendu.

## Confidentialité

La météo ne reçoit pas les coordonnées GPS exactes.

Avant requête, latitude et longitude sont arrondies à 0,01 degré, soit environ un kilomètre selon la latitude. Ce niveau suffit à la résolution météo visée sans transmettre la position exacte du téléphone.

Aucune base astronomique distante ne reçoit la position.

## Rendu

La couverture nuageuse module :

- densité de la couche de nuages ;
- visibilité des étoiles ;
- visibilité des constellations ;
- contraste du Soleil ;
- contraste de la Lune ;
- voile général en cas de forte couverture.

Les formes de nuages sont une représentation visuelle de la couverture fournie par le modèle météo. Elles ne prétendent pas être une image satellite ni la géométrie exacte des nuages au-dessus du téléphone.

## Performance

- météo réseau : au maximum environ une requête toutes les 15 minutes pour une même cellule ;
- backoff après erreur réseau ;
- cache local en mémoire ;
- nuages dessinés avec primitives légères ;
- aucune requête météo à chaque frame ;
- aucune dépendance de Pointage ou Salaire au fonctionnement météo.

## Fournisseur DEV

Les builds DEV peuvent recevoir l'endpoint Open-Meteo par variable de build.

L'endpoint n'est pas codé en dur comme vérité de production.

La version de production doit utiliser un endpoint explicitement configuré et disposant d'une licence compatible avec l'usage commercial de HoraTrack. Si aucun endpoint conforme n'est configuré, la couche météo reste désactivée.

Lorsque Open-Meteo est actif en DEV, l'attribution utilisateur « Données météo : Open-Meteo • CC BY 4.0 » est affichée dans les paramètres.

## Parité

Android et iOS appliquent la même règle :

1. météo fraîche disponible → couche atmosphérique réelle ;
2. météo indisponible → ciel astronomique uniquement ;
3. aucune météo inventée.
