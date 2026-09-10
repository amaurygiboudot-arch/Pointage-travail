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

**Limites assumées :** la réfraction réelle dépend de la pression, température, humidité et des couches d’air. HoraTrack ne connaît pas non plus l’horizon local masqué par bâtiments, arbres ou relief. Pour la Lune, le demi-diamètre varie légèrement avec la distance : le seuil fixe autour de -0,83° reste cohérent pour cette éphéméride légère mais n’est pas un calcul d’almanach professionnel.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Horizon_Refraction_Audit.md`.

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

## État actuel

Le lot céleste reste isolé dans la PR #155. La prochaine validation téléphone doit vérifier surtout le suivi 360°, la stabilité du cap, la direction réelle des astres et leur comportement près du lever/coucher. Le nettoyage `heroClockHands` et la parité widget restent volontairement séparés jusqu’à validation visuelle.
