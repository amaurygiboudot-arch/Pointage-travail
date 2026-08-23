# Diamond Designer — spécification de l'atelier visuel

## Objectif
Créer un mini logiciel de design intégré à une branche isolée de Pointage Travail afin de concevoir visuellement les boutons, cadres, fonds et éléments graphiques sans écrire de code.

Le designer doit produire un résultat reproductible : chaque réglage est enregistré numériquement et exportable sous forme de rapport/preset pour pouvoir être réinjecté exactement dans l'application principale.

## Principes
- Aucun changement du designer ne touche `main` automatiquement.
- Aperçu en temps réel.
- Tous les réglages restent réversibles.
- Undo / Redo.
- Réinitialisation globale ou par propriété.
- Valeurs numériques visibles en plus des curseurs.
- Sauvegarde de presets.
- Bibliothèque locale de composants visuels.

## Interface générale

### 1. Canvas central
Zone tactile libre avec grille optionnelle.

Manipulations directes :
- déplacer un élément au doigt ;
- redimensionner par poignées ;
- étirer horizontalement / verticalement ;
- conserver ou libérer le ratio ;
- rotation ;
- centrage automatique ;
- alignement horizontal / vertical ;
- duplication ;
- verrouillage ;
- masquer / afficher ;
- ordre avant / arrière ;
- magnétisme aux guides et à la grille ;
- zoom et déplacement du canvas.

### 2. Calques
Chaque objet est un calque indépendant :
- Fond
- Cadre
- Bouton
- Texte
- Icône
- Image
- Effet
- Lumière
- Reflet

Fonctions : renommer, verrouiller, masquer, dupliquer, supprimer et réordonner.

### 3. Bibliothèque
Catégories :
- Boutons
- Cadres
- Fonds
- Icônes
- Images
- Logos
- Presets diamant
- Presets lumière
- Presets effets

Chaque élément peut être ajouté au canvas par toucher ou glisser-déposer.

La bibliothèque doit pouvoir contenir les ressources déjà présentes dans le projet et de nouvelles ressources ajoutées ultérieurement.

### 4. Transformations générales
Pour tout élément sélectionné :
- X / Y
- largeur / hauteur
- échelle X / Y
- rotation
- opacité
- rayon des coins
- ratio verrouillé oui/non
- miroir horizontal / vertical
- pivot de transformation

## Réglages spécifiques du bouton diamant

### Géométrie
- Nombre de facettes — preset officiel 80 = 16 + 32 + 32
- Rayon de table / anneau central
- Rayon anneau intermédiaire
- Rayon anneau externe
- inclinaison de coupe par anneau
- variations de coupe par facette
- profondeur visuelle
- épaisseur de cerclage

### Bombé / loupe
- force du bombé (`lensStrength`)
- rayon d'action
- courbe non linéaire
- gain anneau central
- gain anneau intermédiaire
- gain anneau externe
- intensité radiale
- falloff bord

Important : le bombé est une transformation géométrique finale. Il ne crée aucune lumière ou reflet.

### Couleur
- palette globale
- couleur par anneau
- couleur par facette
- saturation
- luminosité de base
- contraste
- teinte
- profondeur des tons sombres
- couleur des hautes lumières

Presets : Vert Entrée, Orange Pause, Rouge Sortie.

### Translucidité
- opacité globale
- transparence de référence par anneau
- transparence individuelle par facette
- variation selon angle de coupe
- variation selon orientation géométrique

La couleur de base et la transparence de référence restent stables pendant les mouvements du téléphone.

### Lumière et reflets
- azimut lumière
- élévation
- intensité
- largeur spéculaire
- puissance spéculaire
- Fresnel
- réfraction
- réflexion interne
- fire / dispersion chromatique
- glint
- activation séparée de chaque effet

### Capteurs
- aperçu manuel par joystick 2D
- aperçu réel avec capteurs du téléphone
- pitch
- roll
- azimut
- sensibilité

Mode officiel : aucune dead-zone et aucune temporisation artificielle.

### Mémoire des 80 facettes
Chaque facette conserve :
- identifiant 0..79
- couleur de base
- translucidité de référence
- luminosité actuelle
- reflet actuel
- dernière orientation connue
- ring
- angle de coupe

## Cadres
Créer et modifier des cadres autour des boutons :
- cercle
- ellipse
- rectangle
- rectangle arrondi
- polygone
- cadre image
- cadre personnalisé

Réglages :
- largeur
- hauteur
- épaisseur
- couleur
- dégradé
- texture
- opacité
- relief
- ombre
- lueur
- rotation
- marge interne
- marge externe

## Fonds
- couleur unie
- dégradé
- image
- texture
- carbone
- transparence
- flou
- luminosité
- contraste
- saturation
- zoom
- position
- rotation

## Texte et icônes
- contenu
- police disponible dans le projet
- taille
- graisse
- couleur
- opacité
- contour
- ombre
- alignement
- rotation
- position

## Historique
- Undo
- Redo
- instantanés nommés
- comparaison Avant / Après
- retour au preset d'origine

## Presets et projets
Un projet sauvegarde tout le canvas :
- taille du canvas
- liste ordonnée des calques
- transformations
- propriétés graphiques
- réglages du moteur diamant
- sélection active

Formats prévus :
- preset JSON interne
- rapport texte lisible

## Export du rapport
Le bouton `COPIER LE RAPPORT` produit au minimum :
- dimensions
- positions
- rotations
- palette
- alpha de référence
- paramètres lumière
- paramètres reflets
- paramètres des 80 facettes
- bombé
- cadres
- fonds
- ordre des calques

Le rapport doit être suffisamment précis pour recréer le design sans approximation dans l'application principale.

## Sécurité de développement
Le designer reste sur `tool/diamond-designer` jusqu'à validation. Aucune fusion automatique vers `main`.
