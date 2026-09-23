# Céleste V2 — attribution des données astronomiques

## Bright Star Catalogue (BSC5P)

HoraTrack embarque un fichier compact dérivé du **Bright Star Catalogue, 5th Edition, preliminary** (Hoffleit & Warren, 1991), à partir des champs J2000 et de magnitude visuelle publiés par HEASARC/CDS.

- Catalogue de référence : NASA/HEASARC BSC5P.
- Identifiant conservé : numéro HR (Bright Star Number).
- Données embarquées : HR, ascension droite J2000, déclinaison J2000, magnitude visuelle.
- Aucune requête vers HEASARC, CDS ou SIMBAD n'est effectuée depuis l'application en production.
- La position GPS de l'utilisateur reste locale à l'appareil pour la projection du ciel.

## Tracés des constellations

Les tracés utilisent **ConstellationLines** de Marc van der Sluys (2005–2023).

- Source : https://github.com/MarcvdSluys/ConstellationLines
- DOI : 10.5281/zenodo.10397192
- Licence : **Creative Commons Attribution 4.0 International (CC BY 4.0)**.
- Les tracés référencent directement les numéros HR du BSC ; HoraTrack ne fabrique aucun point intermédiaire.

Les fichiers embarqués ont été compactés pour le mobile sans modifier l'identité HR ni les coordonnées astronomiques utilisées par le moteur Céleste V2.
