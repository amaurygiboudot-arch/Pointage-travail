# HoraTrack — Audit ciblé horloge / suivi céleste → V2

## Périmètre

Cet audit concerne uniquement l’horloge analogique, la Terre centrale, le Soleil, la Lune, les positions astronomiques, la phase lunaire, les éclipses, le GPS, les capteurs d’orientation et l’éclairage naturel relié à ce système.

Sont volontairement hors périmètre : pointage métier, pauses, paie, conventions, paniers, droits, PDF et moteurs juridiques.

## Référentiel visuel validé

La décision de conception est maintenant explicite : **la Terre reste au centre de l’horloge**.

Ce choix ne prétend pas montrer le système solaire héliocentrique. L’horloge représente le **ciel apparent autour de l’observateur terrestre** :

- la Terre centrale représente l’observateur et son référentiel ;
- le Soleil et la Lune sont placés selon leur position topocentrique réelle ;
- l’azimut détermine leur angle autour du cadran ;
- l’altitude détermine leur distance au centre ;
- le cap du téléphone fait tourner la carte par rapport au Nord vrai ;
- un astre au-dessus de l’horizon reste représenté sur les 360° du cadran, même s’il se trouve dans la direction opposée au téléphone.

HoraTrack est donc une **carte céleste topocentrique 360° intégrée à une horloge**, pas un orrery héliocentrique et pas un viseur caméra/AR.

## Résultat de l’audit

### CRITIQUE — ancien calcul de position lunaire

Le solveur historique ne reconstruisait pas correctement le plan orbital lunaire : l’inclinaison d’environ 5,1454° et le nœud orbital n’étaient pas appliqués dans le vecteur écliptique final.

**Correction V2 :** `CelestialEngineV2` reconstruit le vecteur orbital avec nœud + inclinaison, applique les principales perturbations lunaires et corrige la distance.

### CRITIQUE — phase lunaire historique

La fraction éclairée était déduite d’une séparation simplifiée Soleil/Lune.

**Correction V2 :** calcul du véritable angle de phase Soleil–Lune–Terre avec les distances, puis fraction éclairée physique. Le moteur expose aussi le limbe éclairé.

### CRITIQUE — orientation du terminateur

L’ancien rendu orientait l’éclairage lunaire avec une simple ligne 2D entre les sprites.

**Correction V2 :** `CelestialScreenGeometryV2` reconstruit la vraie direction tangentielle Lune → Soleil dans le repère local Est / Nord vrai / Zénith puis la transpose dans le référentiel du cadran.

### CRITIQUE — ombre terrestre / éclipses lunaires

L’ancien système utilisait un seuil empirique proche de 180° et une ombre de taille arbitraire.

**Correction V2 :**

- axe anti-solaire réel ;
- rayon physique de l’umbra ;
- rayon physique de la pénombre ;
- magnitude pénombrale et ombrale ;
- classification `NONE / PENUMBRAL / PARTIAL / TOTAL` ;
- rendu de l’ombre selon les rayons calculés ;
- direction de l’ombre projetée vers le vrai anti-Soleil.

### ÉLEVÉ — parallaxe lunaire absente

La position apparente de la Lune dépend fortement de l’observateur.

**Correction V2 :** position topocentrique à partir de latitude, longitude et altitude de l’observateur.

### ÉLEVÉ — faux ciel sans GPS

Le rendu historique pouvait inventer Soleil/Lune à partir de l’heure.

**Correction V2 :** aucune position céleste n’est dessinée si la localisation n’est pas qualifiée.

### ÉLEVÉ — double calcul et double acquisition

La vue céleste et l’éclairage recalculaient chacun l’astronomie et ouvraient leurs propres GPS/capteurs.

**Correction V2 :** `CelestialTrackerV2` centralise localisation, capteurs, Nord vrai et snapshot astronomique. `SunIndicatorView` et `LightDirectionController` consomment le même état.

### ÉLEVÉ — qualité GPS

**Correction V2 :** `CelestialTrackingPolicyV2` fonctionne en fail-closed : permission absente, position absente, trop ancienne ou trop imprécise → pas de ciel présenté comme réel.

### ÉLEVÉ — Nord magnétique / Nord vrai

**Correction V2 :** le tracker utilise `GeomagneticField` avec la position de l’utilisateur pour appliquer la déclinaison magnétique avant le placement céleste.

### CRITIQUE — confusion carte céleste / viseur AR détectée pendant le test réel

Le test vidéo sur téléphone a révélé un défaut conceptuel introduit lors du passage à la projection 3D : Soleil et Lune pouvaient **disparaître en tournant ou inclinant le téléphone** alors qu’ils restaient réellement au-dessus de l’horizon.

Cause : `projectInDeviceSky()` traitait le cadran comme une caméra et rejetait les astres situés derrière le plan physique de l’écran.

Ce comportement est correct pour un mode AR, mais incorrect pour l’horloge imaginée à l’origine.

**Décision validée : Terre au centre + carte céleste 360°.**

**Correction du lot actuel :**

- `projectEarthCenteredSky()` devient la projection canonique ;
- azimut réel → angle autour de la Terre ;
- altitude réelle → rayon ;
- le cap réel du téléphone tourne la carte ;
- un astre à l’opposé du cap reste visible sur le cadran ;
- `projectInDeviceSky()` est conservé comme compatibilité d’API mais adopte désormais cette sémantique 360° ;
- le repère 3D sert à déterminer un cap cohérent quelle que soit la posture du téléphone ;
- pitch/roll restent disponibles pour le relief et l’éclairage, mais ne doivent plus transformer l’horloge en viseur AR.

Le comportement « astre derrière le téléphone = disparition » est maintenant explicitement interdit par test.

### ÉLEVÉ — altitude réelle dans la position graphique

La projection canonique conserve le principe validé :

- horizon → bord externe ;
- altitude croissante → rapprochement du centre ;
- zénith → rayon interne de 34 % afin de préserver la Terre centrale et les aiguilles ;
- sous l’horizon civil → pas de sprite.

Cette compression du zénith est un choix graphique ; l’ordre physique des altitudes reste monotone.

### MOYEN — deux vues d’horloge dans `activity_main.xml`

Le layout contient encore `heroClockPermanent` et la vue fantôme `heroClockHands` 1×1.

`heroClockPermanent` est déjà identifiée comme l’horloge canonique. Le nettoyage de `heroClockHands` reste différé jusqu’à validation du nouveau mouvement céleste sur téléphone pour ne pas mélanger correction scientifique et nettoyage UI.

### FAIBLE / PARITÉ — widget Android

Le widget Android dessine encore son propre cadran sans Soleil/Lune V2. Cette parité restera un lot séparé après validation du rendu principal.

## Architecture V2 actuelle

```text
GPS + capteurs Android
        |
        v
CelestialTrackerV2
        |
        +--> localisation qualifiée fail-closed
        +--> Nord magnétique -> Nord vrai
        +--> cap / pitch / roll
        |
        +--> HoraTrackV2.celestial
        |       |
        |       +--> CelestialEngineV2
        |              +--> Soleil topocentrique
        |              +--> Lune topocentrique
        |              +--> phase réelle
        |              +--> éclipses lunaires
        |
        +--> CelestialScreenGeometryV2
                |
                +--> carte topocentrique 360° Terre au centre
                +--> azimut -> angle
                +--> altitude -> rayon
                +--> terminateur Lune -> Soleil
                +--> ombre Lune -> anti-Soleil
```

## Tests de référence

Les tests couvrent notamment :

- nouvelle Lune du 17 février 2026 ;
- pleine Lune du 3 mars 2026 ;
- éclipses lunaires totale, partielle et pénombrale de référence ;
- qualité/fraîcheur GPS ;
- horizon, altitude intermédiaire et zénith ;
- rotation du ciel selon le cap ;
- astre opposé au cap restant visible sur le cadran 360° ;
- téléphone à plat et téléphone vertical pour la déduction du cap ;
- direction du terminateur ;
- direction de l’axe anti-solaire.

## État actuel

Le moteur astronomique V2 est conservé. Le défaut de représentation découvert en test réel est corrigé dans le sens du concept d’origine : **Terre centrale, ciel apparent 360°, Soleil et Lune positionnés autour de l’observateur et non masqués par l’orientation avant/arrière de l’écran**.

La prochaine validation sur téléphone doit vérifier en priorité :

1. qu’en tournant le téléphone sur 360°, Soleil et Lune font le tour du cadran sans disparaître tant qu’ils sont au-dessus de l’horizon ;
2. que leur position angulaire correspond à la direction réelle ;
3. que l’altitude reste cohérente dans le rayon ;
4. que la phase lunaire conserve la bonne orientation ;
5. que le mouvement reste stable lorsque le téléphone passe d’une posture plutôt à plat à plutôt verticale.

Après cette validation seulement, le nettoyage `heroClockHands` pourra être fait séparément.
