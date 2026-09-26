# Céleste — reprise après vidéo : rebond de l'Accueil et décalage solaire

Date : 25 septembre 2026. Base vérifiée : `2bb9acfddb43b42e6d3f24807174c4d5e56c6638`, après #435.

## Observation et portée de la preuve

Amaury signale que le sautillement persiste et que la trajectoire du Soleil paraît incorrecte. La vidéo fournie dure environ 15 secondes ; 358 images vidéo ont été décodées localement. La séquence contient des gestes, des déplacements/étirements du cadran et des déplacements du Soleil par rapport à la Terre. Aucune vidéo ni capture privée n'est ajoutée au dépôt.

La vidéo ne donne pas le numéro de version installé, les coordonnées GPS qualifiées, le cap ou l'altitude solaire calculée. Elle n'autorise donc pas à déclarer l'éphéméride fausse, ni à attribuer chaque mouvement à une seule cause. Le déplacement astronomique, la réorientation selon le cap et un déplacement de mise en page sont trois phénomènes distincts.

Le correctif #435 (GONE -> INVISIBLE) reste utile mais ne traitait pas le défilement et le rebond du ScrollView lui-même.

## Mécanismes retrouvés dans le code

- L'Accueil Android est contenu dans `ThemedBackgroundScrollView`, qui conservait les gestes de défilement et l'overscroll du framework.
- `CelestialHomePanel` imposait une hauteur depuis la taille physique de l'écran (88 %, minimum 320 dp), en plus des onglets et des marges. Il pouvait dépasser la fenêtre réellement disponible.
- Le cadran et `SunIndicatorView` enregistrent leurs dessins indépendamment et recalculent leur ancrage à partir de leur position dans la fenêtre. Le déplacement d'un parent entre deux rafraîchissements peut donc décaler temporairement les astres par rapport au cadran.

Ce lot traite ces mécanismes dans le conteneur, sans déplacer artificiellement le Soleil pour le faire correspondre à une image.

## Correctif

1. En mode Accueil seulement, désactiver drag, fling et overscroll du conteneur et maintenir son décalage à zéro. Les clics des onglets et du message de récupération GPS continuent d'aller aux enfants ; le toucher de l'Activity réaffiche toujours la navigation.
2. Hors Accueil, rendre au ScrollView ses interactions, sa barre de défilement et son mode d'overscroll initiaux. Les écrans métier ne deviennent pas fixes.
3. Mesurer le panneau céleste depuis la fenêtre disponible moins les espaces réellement occupés, onglets invisibles compris. Supprimer les minima liés à l'écran physique qui créaient du débordement. Aucun cas par marque/modèle.
4. Lors d'une vraie redistribution du panneau (taille/insets), invalider ensemble l'horloge et l'overlay Soleil/Lune pour qu'ils réenregistrent leurs dessins dans la même passe de disposition.

Éphémérides, positions/cap qualifiés, seuil d'horizon 0°, fondu, projection sphérique, Terre centrée et contre-rotation nord, taille relative des symboles, graphismes, météo et étoiles : inchangés.

## Android / iOS

Le défaut de conteneur démontré est celui de l'adaptateur Android. Il n'est pas copié dans une nouvelle règle astronomique commune. Le cadran iOS possède déjà ses astres dans le même `ZStack` ; ce lot ajoute des tests Swift de non-régression de la projection commune sans supprimer le défilement utile de ses détails d'éphémérides. Cela ne vaut pas validation matérielle iOS.

## Tests et limites

- La copie locale initiale de `ThemedBackgroundScrollView.kt` correspond au blob `e73d958fab18fc0fbcd20d112b21ad0aac7dabdf`.
- Compilation et harnais Kotlin natif de la politique de fenêtre : **52 004 assertions réussies**, sur offsets, fenêtres et bornes entières. Ce n'est pas un test Android instrumenté.
- Ajout de 5 JUnit de politique de fenêtre, 3 JUnit de repère solaire et 2 XCTest de repère solaire. Leur exécution complète reste à établir en CI sur le nouveau SHA.
- Les tests solaires vérifient la courbe projetée à altitude constante lorsque le cap change, l'invariance du décalage Soleil/Terre lors d'une translation commune et le faible déplacement dans un scénario temporel synthétique de 15 secondes. Ils ne comparent pas le téléphone d'Amaury à une éphéméride externe et ne valident pas ses capteurs.
- Aucun build Android, test instrumenté, benchmark ou essai sur appareil n'est prétendu exécuté dans le conteneur local.

## Livraison et reprise

Le déclencheur DEV existant est inclus. Après tests techniques et fusion autorisée, contrôler réellement version, SOURCE_SHA, signature et publication dans `dev-latest`. Aucun changement de workflow/protection/secret et aucun déploiement Firebase/store. La revue agents non exécutée reste non validée, selon la décision humaine déjà consignée.

Essai : refaire les gestes de la vidéo, laisser les onglets disparaître, les rappeler, puis tourner doucement le téléphone. Le cadran ne doit plus être défilé/étiré par le doigt ; le Soleil ne doit plus recevoir un décalage de défilement indépendant de la Terre. Vérifier aussi clic GPS, changement d'onglet, défilement dans Historique/Paramètres, reprise et petite fenêtre.

Le signalement « trajectoire du Soleil » reste à qualifier après ce correctif d'affichage. Si un écart persiste sans geste de défilement, comparer heure/date, coordonnées et cap réellement utilisés avec le résultat projeté avant de toucher aux calculs. Ne pas considérer ce signalement clos sur la seule réussite des tests.

Références techniques consultées :
- Android Developers, Animate a scroll gesture : https://developer.android.com/develop/ui/views/touch-and-input/gestures/scroll
- Android ScrollView : https://developer.android.com/reference/android/widget/ScrollView
