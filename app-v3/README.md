# Prototype Android V3

Ce module est un prototype visuel autonome. Il ne fournit volontairement aucun
calcul astronomique et ne demande aucune permission de localisation.

La seule source de vérité Céleste Android est le moteur
`app/src/main/java/com/amaury/pointage/v2/engine/CelestialEngineV2.kt`, consommé
par l'application principale avec sa politique de qualité GPS et de cap.

Le prototype ne devra réactiver un affichage solaire ou lunaire qu'après
extraction de ce moteur dans une bibliothèque Android partagée. Recopier un
solveur dans `app-v3` est interdit, car les deux implémentations pourraient
diverger silencieusement.

`V3LightController` reste actif uniquement pour l'éclairage décoratif des
boutons en fonction de l'orientation de l'appareil ; il ne représente pas la
position d'un astre.
