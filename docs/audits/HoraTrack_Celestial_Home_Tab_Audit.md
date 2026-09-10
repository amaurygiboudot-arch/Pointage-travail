# HoraTrack — audit écran Accueil céleste

Date : 2026-09-10

## Décision produit validée

Le système céleste ne doit plus rester comprimé au-dessus de tous les écrans métier.

HoraTrack dispose désormais d'un onglet **ACCUEIL**, placé à gauche de **AUJOURD'HUI**, réservé à l'horloge céleste : cadran analogique, Terre GPS, Soleil, Lune, phases et éclipses.

Les écrans Aujourd'hui, Historique, Analyses, Salaire et Paramètres récupèrent l'espace auparavant occupé en permanence par l'horloge.

## Adaptation à l'écran

Le conteneur `CelestialHomePanel` n'utilise plus une hauteur fixe de 250 dp.

Sa hauteur est calculée à partir de la largeur réellement disponible, avec des bornes adaptées à la hauteur de l'écran. Sur téléphone, le cadran peut ainsi utiliser presque toute la largeur utile sans être étiré ni rogné. Sur écran plus grand, sa taille reste plafonnée pour conserver une composition lisible.

`heroClockPermanent` et `sunIndicator` occupent maintenant tout ce conteneur responsive. Ils conservent donc exactement le même repère de dessin pour la Terre, le Soleil et la Lune.

## Navigation

- `home` devient la destination par défaut lorsqu'aucun onglet n'a encore été mémorisé ;
- l'onglet Accueil est restauré après reprise de l'activité ;
- sélectionner Accueil affiche uniquement le système céleste et masque le contenu de pointage ;
- sélectionner un autre onglet masque le panneau céleste ;
- Salaire masque également explicitement le panneau céleste et réaffiche son propre contenu ;
- la sélection visuelle des six onglets est synchronisée avec les thèmes et la taille de texte.

## Taille de la barre de navigation

Le passage de cinq à six onglets réduit l'espace horizontal disponible. Les libellés de navigation utilisent donc une typographie condensée à 10,5 sp avant application de l'échelle utilisateur, avec un padding horizontal réduit. Le but est de conserver les six destinations sur une seule ligne sans supprimer de fonction.

## Invariants célestes conservés

Cette modification ne change pas les calculs astronomiques, la projection 360°, la qualité GPS/boussole, les phases, les éclipses ni l'orientation de la Terre GPS. Elle change uniquement la place de ce système dans l'interface et l'espace disponible pour son rendu.

## Validation attendue sur téléphone

La validation visuelle doit vérifier :

1. que l'onglet Accueil est bien à gauche d'Aujourd'hui ;
2. que l'horloge est nettement plus grande et reste entièrement visible ;
3. que les six onglets restent lisibles ;
4. que la Terre, le Soleil et la Lune restent correctement superposés au cadran ;
5. que l'horloge disparaît bien des écrans Aujourd'hui/Historique/Analyses/Salaire/Paramètres ;
6. qu'un retour sur Accueil restaure immédiatement le système céleste.

Aucune fusion de la PR ne doit être faite avant cette validation visuelle.
