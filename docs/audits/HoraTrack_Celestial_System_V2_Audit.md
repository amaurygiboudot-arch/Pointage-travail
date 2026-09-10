# HoraTrack — Audit ciblé horloge / suivi céleste → V2

## Périmètre

Cet audit concerne uniquement l’horloge analogique, la Terre centrale, le Soleil, la Lune, les positions astronomiques, la phase lunaire, les éclipses, le GPS, les capteurs d’orientation et l’éclairage naturel relié à ce système.

Sont volontairement hors périmètre : pointage métier, pauses, paie, conventions, paniers, droits, PDF et moteurs juridiques.

## Référentiel visuel validé

La Terre reste au centre. HoraTrack représente le ciel apparent topocentrique à 360° autour de l’observateur terrestre, pas un système solaire héliocentrique et pas un viseur AR.

## Résultat consolidé

- calcul lunaire V2 corrigé : plan orbital, nœud, inclinaison, perturbations et parallaxe topocentrique ;
- phase lunaire physique et terminateur dirigé vers le vrai Soleil ;
- éclipses lunaires avec umbra/pénombre physiques ;
- GPS fail-closed, acquisition capteurs centralisée et Nord magnétique corrigé vers le Nord vrai ;
- cap filtré unique partagé par tous les rendus, sans bascule artificielle entre téléphone à plat et vertical ;
- carte 360° : un astre ne disparaît plus simplement parce qu’il est derrière le téléphone ;
- proximité Soleil/Lune séparée d’une vraie éclipse solaire grâce à `SolarEclipseGeometryV2` ;
- `headingAccuracyDeg` disponible pour diagnostiquer une boussole perturbée.

### ÉLEVÉ — horizon géométrique confondu avec lever/coucher apparent

L’altitude géométrique était utilisée directement pour placer les astres sur le rayon du cadran. Près de l’horizon, l’atmosphère relève optiquement Soleil et Lune : la position graphique pouvait donc être trop basse.

La constante `-0.833°` était aussi appelée à tort `CIVIL_HORIZON_DEG`. Cette valeur n’est pas le crépuscule civil : elle correspond au seuil standard du centre solaire au lever/coucher, environ 50 minutes d’arc sous un horizon plat et dégagé, soit environ 34' de réfraction moyenne + 16' de demi-diamètre solaire.

**Correction V2 :**

- ajout de `AtmosphericRefractionV2` pour corriger uniquement l’altitude graphique ;
- formules par morceaux du modèle NOAA pour une atmosphère standard ;
- nouveau nom `STANDARD_DISK_HORIZON_DEG = -50/60°` ;
- ancien nom conservé uniquement comme alias déprécié ;
- altitude apparente utilisée pour le rayon du cadran ;
- tests ajoutés près de l’horizon, au zénith et autour du seuil de visibilité.

Pour la Lune, la position V2 est déjà topocentrique. Le seuil exact varie légèrement avec son demi-diamètre apparent, mais une valeur voisine de `-(34' + demi-diamètre lunaire)` reste proche de la constante pratique `-0,83°`. Le moteur conserve cette approximation commune Soleil/Lune tant que l’éphéméride reste volontairement légère.

**Limites assumées :** la réfraction réelle dépend de la pression, température, humidité et des couches d’air. HoraTrack ne connaît pas non plus l’horizon local masqué par bâtiments, arbres ou relief. Le rendu ne doit donc jamais être présenté comme une prédiction professionnelle du premier/dernier rayon à la seconde près.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Horizon_Refraction_Audit.md`.

### ÉLEVÉ — altitude dans la position graphique

La projection canonique conserve le principe validé : horizon vers le bord externe, altitude croissante vers le centre, zénith comprimé à 34 % du rayon pour préserver Terre/aiguilles. La position radiale utilise désormais l’**altitude apparente corrigée de la réfraction** plutôt que l’altitude géométrique brute près de l’horizon.

### MOYEN — deux vues d’horloge

`activity_main.xml` contient encore `heroClockPermanent` et la vue fantôme `heroClockHands` 1×1. `heroClockPermanent` reste l’horloge canonique. Nettoyage différé jusqu’à validation visuelle finale.

### FAIBLE / PARITÉ — widget Android

Le widget Android dessine encore son propre cadran sans Soleil/Lune V2. Lot séparé après validation du rendu principal.

## Architecture V2

```text
GPS + capteurs Android
        |
        v
CelestialTrackerV2
        +--> localisation qualifiée
        +--> Nord vrai + cap filtré
        +--> HoraTrackV2.celestial / CelestialEngineV2
        +--> AtmosphericRefractionV2
        +--> CelestialScreenGeometryV2
                +--> carte 360° Terre au centre
                +--> azimut -> angle
                +--> altitude apparente -> rayon
                +--> terminateur et ombres physiques
```

## Tests de référence

Les tests couvrent maintenant notamment : nouvelle Lune et pleine Lune de référence, éclipses lunaires, qualité GPS, ciel 360°, astre opposé au cap, posture à plat/inclinée/verticale, cap stabilisé prioritaire, proximité Soleil/Lune sans fausse éclipse, éclipses solaires géométriques, réfraction près de l’horizon, seuil standard du disque, altitude intermédiaire, zénith, terminateur et axe anti-solaire.

## État actuel

Le moteur céleste V2 est maintenant organisé autour de quatre responsabilités séparées : éphéméride géométrique, acquisition GPS/capteurs, correction optique d’altitude pour le rendu et projection 360° Terre au centre.

La prochaine validation téléphone doit vérifier surtout :

1. que Soleil/Lune ne disparaissent plus pendant un tour 360° tant qu’ils sont dans la fenêtre de visibilité ;
2. que la direction angulaire correspond au ciel réel ;
3. que le cap reste stable sans retard excessif ;
4. que près du lever/coucher l’astre ne semble plus artificiellement trop bas ;
5. que la phase et les ombres restent orientées correctement.

Après validation visuelle, le nettoyage `heroClockHands` pourra être traité séparément. Le widget céleste restera ensuite un lot de parité distinct.
