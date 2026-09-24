# Céleste — Nuages V5 : noyau de densité, premier lot

Date : 25 septembre 2026 (Europe/Paris).
Base vérifiée : `67b41a1f076d521533f573b34c8a917620a77324`, après #426 (nuages V4).
Référence fonctionnelle : document utilisateur « HoraTrack — Céleste — Nuages réalistes V5 — Recherche météorologique + spécification de rendu », daté du 24/09/2026.

## Statut exact

Ce lot implémente et teste le **noyau portable** de la V5. Il ne constitue pas la livraison visuelle de toute la V5.
Les renderers Android/iOS utilisent encore la V4 : leurs chemins graphiques ne sont pas supprimés ici avant migration des consommateurs. Aucun nouveau fichier APK/AAB n'est publié par ce lot.

Ne pas reprendre #383 ni refaire #426 : poursuivre depuis ce noyau et les données low/mid/high déjà disponibles dans les deux clients météo.

## Implémenté

- `CloudAtmosphereStateV2` conserve couverture totale, couches low/mid/high optionnelles, brouillard et visibilité ; les étages absents ne deviennent pas zéro.
- `CelestialCloudAtmosphereV2.resolve` valide les entrées et refuse les données absentes, explicitement inutilisables ou numériquement invalides. Le futur appelant doit déterminer `usable` depuis les contrôles existants de fraîcheur, lieu et disponibilité : ce noyau ne lit ni horloge ni GPS.
- `CelestialCloudDensityV2` produit une densité procédurale continue par bruit multi-octaves, déformation douce et seuils progressifs. Aucun ovale, contour fermé ou silhouette de nuage n'est généré par ce noyau.
- Nuages hauts, moyens, bas et altitude non résolue possèdent des recettes graphiques distinctes, identiques en Kotlin et Swift.
- Le brouillard est un voile bas, indépendant d'une couverture totale non nulle.
- En profil d'altitude partiel, une couche connue à zéro ne doit pas effacer la couverture totale. Une représentation neutre non localisée complète visuellement les couches connues sans remplir les valeurs météo manquantes. Cette composition reste artistique, pas une déduction de l'altitude manquante.
- Le raccord 0/360° est périodique et le temps ne comporte pas de remise à zéro toutes les 90 minutes.
- Le nombre d'octaves peut varier de 1 à 6 sans retirer de couche mesurée. Aucune marque, aucun modèle ou métier n'intervient dans l'algorithme.

Les poids optiques, densités, vitesses et paramètres de turbulence sont des choix graphiques. Ils ne sont ni une mesure atmosphérique, ni une reconstruction des nuages réels, ni une mesure de vent en altitude. La proportion exacte de pixels couverts n'est pas une nouvelle observation météo.

## Vérifications locales réellement exécutées

Environnement isolé Linux, Kotlin 1.9/JVM et Swift 6.2.1 :

- compilation du noyau Kotlin et du noyau Swift ;
- exécution native des deux noyaux sur 22 680 échantillons appariés : différence absolue maximale observée 0, tolérance 1e-9 ;
- vérification de densité bornée, monotonie selon couverture, raccord périodique et continuité autour de l'ancien seuil de 90 minutes ;
- 10 tests XCTest du noyau, 0 échec, exécutés dans un package isolé portant le module `CelestialV2Contract` ;
- 10 tests JUnit miroir ajoutés au dépôt, à exécuter dans la CI Android : ce rapport ne prétend pas que JUnit/Gradle ont été exécutés localement.

Grille de parité : 4 étages × 6 couvertures (0/10/25/50/75/100 %) × 3 niveaux de détail (1/3/6 octaves) × 5 instants × 7 coordonnées verticales × 9 coordonnées horizontales. Les tests incluent huit références numériques communes pour détecter une divergence Kotlin/Swift.

Ces contrôles ne valent PAS build complet Android, build simulateur iOS, validation graphique sur téléphone, mesure de batterie ou revue multi-agents. Aucun état NOT_RUN n'est converti en PASS.

## Prochain raccord obligatoire, sans nouvelle refonte

1. Faire produire l'état nuage par `CelestialRenderStateFactoryV2`, à partir d'une météo fraîche appartenant au lieu qualifié du snapshot. Réutiliser la classification météo canonique ; ne pas dupliquer les codes fournisseur.
2. Remplacer réellement les formes V4 des deux renderers par des textures issues du noyau. Produire/cache les textures hors du travail lourd par frame ; invalider sur changement pertinent, dimension ou qualité.
3. Relier jour/crépuscule/nuit et atténuation Soleil/Lune/étoiles au propriétaire canonique, sans déplacer l'astronomie et sans appliquer deux fois une atténuation météo.
4. Lisser les transitions météo et respecter économie d'énergie, application non visible, pression mémoire et appareils supportés. Le vent reste une influence limitée uniquement lorsque les données utiles existent.
5. Exécuter les commandes de test/build existantes, puis les spécialistes requis, team_lead, QA et control_gate sur le SHA exact ; réaliser les captures/vidéos Android+iOS et fermer les anomalies visuelles avant validation finale.
6. Retirer les anciens helpers graphiques après migration prouvée ; conserver le canal unique `dev-latest` pour les futurs installateurs de test.

## Périmètre préservé

Aucune modification de Salaire V2, Pointage, règles GPS, Firebase, positions astronomiques, gouvernance ou chaîne de sécurité. Le chantier Salaire parallèle reste séparé.
La fusion éventuelle de ce noyau ne signifie jamais « V5 visuelle terminée ». Les gates du dépôt restent obligatoires ; aucune publication production/store n'est autorisée par ce document.
