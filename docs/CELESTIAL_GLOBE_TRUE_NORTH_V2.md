# Céleste — Terre centrée, nord réel et suppression des guides blancs

## Décision utilisateur du 25 septembre 2026

Base : `0bff9ef5e9226c12f82a4396c416ce28d7e15aa3`, après #433.

Demande confirmée : supprimer les arcs blancs autour de la Terre et le trait perçu comme trajectoire solaire ; la Terre reste au centre exact de l'horloge et pivote uniquement sur elle-même pour compenser l'orientation du téléphone. Amaury a accepté cet essai : « Ok on essaye ça puis on modifiera ».

Cette décision remplace, pour l'orientation du globe affiché, l'ancienne consigne « nord en haut de l'écran indépendamment du cap ». Elle ne change pas la géographie calculée ni le point central GPS. Nord géographique et haut de l'écran ne sont pas la même direction quand le téléphone pivote.

## Correctif ciblé

- Suppression des chemins de grille du dôme (parallèles, méridiens et ellipse d'horizon), Android et iOS. Les grandes courbes de la capture provenaient de cette grille ; le code des éphémérides et du mouvement solaire n'est pas supprimé.
- Suppression du contour clair du petit globe Android et de la grille/du contour blanc terrestre iOS. Sur iOS, la transition jour/nuit garde son ombrage doux, sans son trait net superposé. Le petit marqueur utilisateur et sa lisibilité restent conservés.
- Le dôme garde sa projection sphérique, son ombrage diffus, ses étoiles et leurs halos/scintillement. Ne pas confondre retrait de la grille et retour au disque plat.
- `CelestialGlobeOrientationV2` fournit la contre-rotation de présentation à partir du cap de rendu déjà qualifié par `CelestialHeadingPolicyV2`. Les adaptateurs réutilisent le même état de tracking que le ciel, sans deuxième boussole ni nouveau GPS.
- Android : `HpAnalogClockView` isole la rotation dans `save / rotate(angle,cx,cy) / restoreToCount` autour du rendu Terre uniquement. Même centre et même rayon que le pivot des aiguilles. Le bitmap géographique, son éclairage et son marqueur tournent ensemble ; le cache ne dépend toujours pas du cap. En pause, l'orientation est conservée avec le dernier globe ; aucun nouveau cap n'est acquis.
- iOS : le cadran transmet explicitement le cap de rendu au globe ; `rotationEffect` utilise `.center` avant le positionnement existant au centre du cadran. Pas d'animation implicite de tour complet à la jonction des angles. Le Soleil, la Lune et le cadre ne reçoivent pas cette contre-rotation supplémentaire.
- Un cap rejeté reste géré par la politique existante : mode Nord stable, et non fausse promesse d'orientation physique. Les adaptateurs Android/iOS conservent leur qualification actuelle ; ce lot ne change aucun seuil ou règle de capteur.

## Préservé

Centrage et taille de la Terre, modes Local/Monde, géographie/GPS, terminateur réel, positions et graphismes Soleil/Lune, horizon 0°, transitions canoniques, horloge/aiguilles, météo/nuages, règles de données fiables.

Tous utilisateurs, métiers/classes et appareils Android/iOS officiellement supportés ; aucun cas particulier de marque/modèle. Aucun changement de Salaire, Pointage, Firebase, permission, secret, workflow ou protection.

## Vérifications locales réellement exécutées

Environnement Linux, Kotlin 1.9/JVM et Swift 6.2.1 :
- compilation et exécution du nouveau calcul de contre-rotation dans les deux langages ;
- 14 401 angles appariés, de −720° à +720° : écart absolu maximal 0, tolérance 1e-12 ;
- 7 XCTest du noyau d'orientation : 0 échec, package isolé `CelestialV2Contract` ;
- 7 scénarios miroir exécutés en harnais Kotlin natif : succès. Il ne s'agit pas d'une exécution locale JUnit/Gradle ou Android.

Le dépôt reçoit les 7 tests JUnit et les 7 XCTest. Ils couvrent les caps cardinaux, le sens du nord sur un tour complet, le centre invariant, le rayon conservé, les tours complets/caps négatifs, les raccords 0/360° et 180°, et les entrées numériques invalides.

Tests/builds mobiles complets : à établir par la CI de ce commit. Rendu/boussole sur matériel : à tester par Amaury, pas déclaré validé. Aucun rapport d'agents simulé.

## Livraison de test et contrôle utilisateur

Le déclencheur DEV existant est mis à jour dans ce lot pour compiler, signer et publier APK+AAB vers `dev-latest` après fusion. La décision humaine de fusion pendant l'indisponibilité des agents reste applicable ; leurs résultats absents ou en échec ne deviennent pas PASS, et les contrôles techniques GitHub restent en place.

Vérifier le SOURCE_SHA et la nouvelle version de la release avant de proposer l'APK. Aucun store ni déploiement Firebase.

Test physique attendu : ouvrir Accueil, vérifier l'absence des grandes courbes blanches et du contour du globe, puis tourner lentement le téléphone d'un quart et d'un demi-tour. Le globe et le marqueur local restent au pivot, tandis que la géographie compense la rotation. Vérifier également le passage 359°/0°, le mode Monde, l'arrière-plan/reprise et le mode cap indisponible. Le cadran, les aiguilles et les astres doivent garder leur fonctionnement.
