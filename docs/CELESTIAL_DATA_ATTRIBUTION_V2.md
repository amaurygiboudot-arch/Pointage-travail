# Céleste V2 — attribution des données astronomiques

## Bright Star Catalogue (BSC5P)

HoraTrack embarque un fichier compact dérivé du **Bright Star Catalogue, 5th Edition, preliminary** (Hoffleit & Warren, 1991), à partir des champs J2000 et de magnitude visuelle publiés par HEASARC/CDS.

- Catalogue de référence : NASA/HEASARC BSC5P.
- Copie de travail utilisée pour la génération : `frostoven/BSC5P-JSON`, branche `primary`, révision `1018c1d32c85cd02d7bbdade5726bc828f5388bd`, fichier original fixe `original_bsc5p/catalog` (blob `6a5fe242dd2d7f3014871ec81ba40032d888772a`).
- Identifiant conservé : numéro HR (Bright Star Number).
- Données embarquées : HR, ascension droite J2000, déclinaison J2000, magnitude visuelle.
- Aucune requête vers HEASARC, CDS ou SIMBAD n'est effectuée depuis l'application en production.
- La position GPS de l'utilisateur reste locale à l'appareil pour la projection du ciel.

## Tracés des constellations

Les tracés utilisent **ConstellationLines** de Marc van der Sluys (2005–2023).

- Source : `MarcvdSluys/ConstellationLines`, révision `1505f7acccd8dee279affed224a46281d26f544c`, fichier `ConstellationLines.csv`.
- DOI : 10.5281/zenodo.10397192
- Licence : **Creative Commons Attribution 4.0 International (CC BY 4.0)**.
- Les tracés référencent directement les numéros HR du BSC ; HoraTrack ne fabrique aucun point intermédiaire.

Les fichiers embarqués ont été compactés pour le mobile sans modifier l'identité HR ni les coordonnées astronomiques utilisées par le moteur Céleste V2.

## Transformation HoraTrack

La génération mobile est volontairement minimale et reproductible :

- BSC5P : lecture des champs fixes HR, RA J2000, Dec J2000 et Vmag ; conversion RA heures/minutes/secondes en degrés ; Dec degrés/minutes/secondes en degrés signés ; exclusion des 14 entrées historiques sans coordonnées stellaires exploitables ; résultat : **9 096 entrées**.
- Constellations : conservation de l'ordre des numéros HR de chaque chemin de `ConstellationLines.csv` ; les lignes multiples d'une même constellation restent distinctes ; résultat : **90 chemins couvrant les 88 constellations**.
- Contrôle génération : chaque numéro HR utilisé par un tracé est présent dans le catalogue embarqué ; **0 référence manquante** lors de la génération du lot V2.
- Les deux plateformes embarquent les mêmes données sources ; aucune plateforme ne possède un catalogue métier différent.
