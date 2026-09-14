# HoraTrack — Audit ciblé boussole / Nord vrai du suivi céleste V2

## Périmètre

Ce document concerne uniquement le cap utilisé pour orienter la carte céleste 360° autour de la Terre centrale : capteurs Android, rotation d'écran, Nord magnétique, déclinaison magnétique, Nord vrai, stabilité et précision annoncée du cap.

Aucun changement de pointage, paie, juridique, Firebase ou logique métier n'est inclus.

## Référentiel confirmé

L'horloge reste une carte topocentrique 360° avec la Terre au centre. La boussole ne calcule pas la position astronomique du Soleil ou de la Lune : elle sert uniquement à faire tourner correctement cette carte par rapport au Nord vrai.

Une erreur de boussole peut donc déplacer tout le ciel sur le cadran sans que les éphémérides Soleil/Lune soient fausses.

## Référence Android utilisée

`TYPE_ROTATION_VECTOR` fournit un repère dont l'axe Nord est lié au Nord magnétique. HoraTrack applique ensuite `GeomagneticField` avec la position et la date courantes pour convertir ce repère vers le Nord vrai.

Quand le capteur le fournit, `SensorEvent.values[4]` contient une estimation de la précision du cap en radians. V2 la convertit maintenant en degrés et l'expose en `headingAccuracyDeg`.

## Défauts trouvés et corrections

### 1. Cap filtré calculé mais contourné par le rendu

Le tracker calculait déjà `deviceAzimuthDeg` avec un filtre, mais la vue Soleil/Lune et l'éclairage utilisaient `projectInDeviceSky(frame)`. `headingFromFrame()` redérivait alors un cap directement depuis les axes bruts du frame.

Le filtre existait donc sans piloter réellement tous les rendus.

**Correction :**

- `CelestialDeviceFrameV2` transporte `stabilizedHeadingDeg` ;
- le tracker injecte le cap filtré dans ce champ ;
- `headingFromFrame()` utilise ce cap en priorité ;
- le calcul brut depuis les axes reste uniquement un secours pour les anciens appelants et les tests.

Soleil, Lune, terminateur, ombre d'éclipse et éclairage utilisent ainsi le même cap stabilisé.

### 2. Zone morte trop grande

Le filtre historique acceptait jusqu'à `2,5°` de variation sans déplacer la carte. Pour une horloge censée correspondre visuellement au vrai ciel, plusieurs degrés de retard sont perceptibles.

**Correction :** zone morte ramenée à `0,40°`.

### 3. Lissage fixe trop lent pendant une vraie rotation

Le facteur unique `0,35` stabilisait les petites oscillations mais pouvait laisser le ciel en retard lorsque l'utilisateur tournait volontairement le téléphone.

**Correction :** lissage adaptatif :

- petit écart → filtrage fort contre le bruit ;
- écart moyen → rattrapage plus rapide ;
- grande rotation → rattrapage très rapide.

### 4. Azimut Euler fragile près d'une posture verticale

Le précédent tracker utilisait `SensorManager.getOrientation(...)[0]` comme azimut avant filtrage. Cet angle Euler est pratique mais devient une mauvaise référence de cap lorsque la posture approche certaines configurations verticales : il peut devenir numériquement instable alors que le téléphone n'a pas réellement changé de direction horizontale.

**Correction :** le cap brut n'est plus tiré de l'angle Euler. V2 :

1. construit le repère écran complet en Est / Nord vrai / Zénith ;
2. déduit le cap horizontal à partir de la géométrie du frame (`Zénith × axe droit écran`) ;
3. applique ensuite le filtre adaptatif ;
4. réinjecte ce cap stabilisé dans le frame partagé.

Pitch et roll continuent d'utiliser les angles d'orientation pour le relief, mais ils ne définissent plus à eux seuls le cap de la carte céleste.

### 5. Ancien cap conservé entre deux sessions

Le filtre n'était pas réinitialisé quand le dernier consommateur se désabonnait. Une nouvelle ouverture pouvait donc repartir quelques instants d'un ancien cap mémorisé.

**Correction :** remise à zéro du filtre, du dernier cap émis et de l'état de précision lorsque l'acquisition s'arrête.

### 6. Précision de cap ignorée

La précision fournie par le rotation vector n'était pas exploitée.

**Correction :** `CelestialTrackerV2.State.headingAccuracyDeg` expose désormais cette estimation lorsqu'elle est disponible.

Elle n'est volontairement pas utilisée pour masquer Soleil/Lune : une mauvaise calibration de boussole ne doit pas faire disparaître les astres d'une carte 360°. Cette donnée sert d'abord au diagnostic.

## Limite physique restante

Une boussole de smartphone peut être perturbée par des champs magnétiques locaux : coque aimantée, support voiture, haut-parleur, acier, moteur électrique, structure métallique, etc. Aucun calcul d'éphémérides ne peut supprimer ce biais physique.

Si le prochain test téléphone montre encore un décalage constant de plusieurs degrés, il faudra comparer en priorité `headingAccuracyDeg` et faire un test loin de toute source métallique/magnétique avant de modifier le moteur astronomique.

## Validation téléphone attendue

Le prochain APK doit être testé de préférence à l'extérieur :

1. téléphone presque à plat, faire un tour lent de 360° ;
2. vérifier qu'aucun astre ne disparaît s'il est au-dessus de l'horizon ;
3. vérifier que Soleil/Lune restent stables lorsque le téléphone est immobile ;
4. tourner rapidement de 90° puis s'arrêter : le ciel doit rattraper sans longue traîne ;
5. incliner progressivement le téléphone presque à la verticale sans retournement de 180° ;
6. si l'alignement réel reste faux, relever la précision de cap avant de remettre en cause la position astronomique.

## État

Correction code intégrée dans la branche `fix/v2-celestial-earth-centered-360` / PR #155. Validation CI et téléphone à confirmer sur le dernier commit avant fusion.
