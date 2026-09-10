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
- arbitrage multi-provider corrigé : une mesure récente mais non qualifiée ne peut plus écraser une position encore valide ;
- fraîcheur maximale de localisation ramenée de 10 à 5 minutes ;
- cap filtré unique partagé par tous les rendus, sans bascule artificielle entre téléphone à plat et vertical ;
- chaîne cardinale verrouillée : cap = 12 h, +90° = 3 h, +180° = 6 h, -90° = 9 h ;
- extraction du cap corrigée pour éviter un retournement de 180° lors d’un fort roulis ;
- qualité du cap désormais fail-closed : erreur connue >15°, état Android non fiable ou cap périmé bloquent le rendu directionnel ;
- carte 360° : un astre ne disparaît plus simplement parce qu’il est derrière le téléphone ;
- proximité Soleil/Lune séparée d’une vraie éclipse solaire grâce à `SolarEclipseGeometryV2` ;
- réfraction atmosphérique standard appliquée à la position graphique près de l’horizon ;
- éphéméride rafraîchie chaque seconde indépendamment de la cadence GPS ;
- âge de la localisation calculé en priorité sur l’horloge monotone Android ;
- précision brute du solveur contrôlée contre une éphéméride indépendante haute précision sur 960 positions Soleil/Lune.

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

### ÉLEVÉ — positions célestes figées par pas de 30 secondes

Le tracker utilisait auparavant la même cadence de 30 secondes pour recontrôler le GPS et pour recalculer les éphémérides. Les capteurs pouvaient faire tourner la carte entre deux ticks, mais Soleil/Lune restaient issus du même snapshot astronomique jusqu’au tick suivant.

**Correction V2 :**

- recalcul Soleil/Lune toutes les **1 seconde** tant que le système céleste est observé ;
- relecture périodique des providers de localisation maintenue à **30 secondes** ;
- une nouvelle mesure GPS reçue d’Android est toujours appliquée immédiatement ;
- la cadence d’orientation reste indépendante et pilotée par les capteurs.

Cela supprime le mouvement temporel par petits sauts sans transformer le GPS en acquisition à 1 Hz.

### ÉLEVÉ — fraîcheur GPS dépendante d’une horloge murale modifiable

La qualité GPS comparait `System.currentTimeMillis()` à `Location.time`. Une correction manuelle ou réseau de l’heure pouvait donc faire paraître une position artificiellement vieille ou future.

**Correction V2 :**

- `CelestialTrackingPolicyV2.classifyAge()` qualifie désormais directement un âge ;
- sur Android, cet âge vient en priorité de `Location.elapsedRealtimeNanos` comparé à `SystemClock.elapsedRealtimeNanos()` ;
- le fallback mural n’est conservé que lorsqu’une position ne fournit pas de référence monotone exploitable ;
- les cadences du ticker continuent également d’utiliser une base monotone.

L’éphéméride, elle, doit garder une vraie date UTC : elle continue donc d’utiliser l’instant Unix du téléphone. Le temps monotone sert uniquement aux **durées**, jamais comme date astronomique.

**Limite assumée :** si l’utilisateur règle une date/heure système réellement fausse, les aiguilles et le ciel seront faux ensemble. HoraTrack ne substitue pas silencieusement une autre horloge pour éviter une incohérence visuelle. Un futur diagnostic réseau/GNSS pourra signaler un écart important.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Time_Audit.md`.

### ÉLEVÉ — arbitrage de localisation basé uniquement sur la récence

L’audit de localisation a montré que le vrai risque n’était pas principalement le seuil horizontal de 2 km. Une erreur de 2 km correspond à environ **0,018°** sur la sphère terrestre : elle est trop petite pour expliquer à elle seule un Soleil décalé de plusieurs degrés sur le cadran.

Le défaut réel était l’arbitrage entre providers : une nouvelle mesure plus récente pouvait remplacer une bonne position simplement parce que son timestamp était supérieur, même si cette nouvelle mesure était ensuite classée `INACCURATE`.

**Correction V2 :**

- ajout de `CelestialTrackingPolicyV2.shouldReplaceLocation()` ;
- une position `VALID` reste prioritaire sur une mesure non qualifiée ;
- entre deux positions valides, la plus fraîche gagne ;
- à âge égal, la meilleure précision horizontale départage ;
- même arbitrage pour live, live + last-known et les différents last-known providers ;
- une exception d’un provider pendant `requestLocationUpdates()` n’empêche plus l’enregistrement des autres providers ;
- fraîcheur maximale réduite de **10 à 5 minutes**.

Le seuil de **2 km** est volontairement conservé : le resserrer arbitrairement aurait surtout augmenté les disparitions du ciel en localisation approximative sans résoudre les décalages de plusieurs degrés constatables visuellement.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Location_Quality_Audit.md`.

### CONTRÔLE HAUTE PRIORITÉ — précision astronomique brute

Le solveur Soleil/Lune a été contrôlé séparément du rendu et de la boussole afin de déterminer si une grosse erreur visible pouvait encore venir de l’éphéméride elle-même.

Une comparaison indépendante contre **Swiss Ephemeris 2.10.03 en topocentrique** a été exécutée sur une grille 2026 comprenant Aizenay/Vendée, Londres, Sydney, Quito et Tromsø, les 1er et 15 de chaque mois à 00:00, 06:00, 12:00 et 18:00 UTC : **480 instants, soit 960 positions d’astre**.

Erreur angulaire globale maximale observée :

- Soleil : **0,0118°** ;
- Lune : **0,0605°**.

Erreurs moyennes : environ **0,0054°** pour le Soleil et **0,0222°** pour la Lune. Le contrôle séparé de phase lunaire a montré un écart maximal d’environ **0,037 point de pourcentage** sur la fraction éclairée.

**Conclusion : aucun défaut majeur du solveur astronomique V2 n’a été trouvé.** À l’échelle de l’horloge, cette précision est largement suffisante. Une erreur visible de plusieurs degrés doit désormais être recherchée d’abord dans le cap téléphone, sa calibration, le Nord vrai, la projection ou le rendu plutôt que dans la position astronomique brute.

Des valeurs de référence sont maintenant verrouillées dans `CelestialEngineV2Test` pour la Vendée, Sydney, Quito et Tromsø. Tolérances de non-régression : 0,03° Soleil et 0,08° Lune sur azimut/altitude.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Ephemeris_Accuracy_Audit.md`.

### ÉLEVÉ — chaîne Nord vrai → cap → cadran sensible au roulis

Le contrôle degré par degré de la chaîne de rendu a confirmé la convention cardinale : lorsque l’azimut de l’astre égale le cap du téléphone, il est à 12 h ; +90° va à 3 h ; +180° à 6 h ; -90° à 9 h. Aucun signe Est/Ouest inversé n’a été trouvé dans cette projection.

La correction de déclinaison magnétique est également cohérente avec la convention Android : une déclinaison positive signifie que le Nord magnétique est tourné vers l’Est par rapport au Nord vrai.

**Défaut trouvé :** `headingFromFrame()` choisissait presque toujours `Up × Right` pour reconstruire le cap. Cette technique résiste bien au passage du téléphone à la verticale, mais lors d’un roulis supérieur à 90° l’axe droit peut traverser la verticale et inverser sa projection horizontale. Le ciel pouvait alors se retourner artificiellement de 180° alors que le haut physique du téléphone gardait la même direction.

**Correction V2 :**

- le cap stabilisé injecté par le tracker reste prioritaire ;
- en posture ordinaire, le haut physique de l’écran définit directement la direction 12 h ;
- seulement lorsque ce haut devient presque vertical, `Up × Right` sert de prolongement de secours ;
- tests cardinaux et test de roulis > 90° ajoutés.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Heading_Chain_Audit.md`.

### ÉLEVÉ — boussole non fiable encore présentée comme ciel réel

`headingAccuracyDeg` existait déjà mais n'était qu'une information diagnostique. Le rendu Soleil/Lune et le relief pouvaient continuer à suivre un cap alors qu'Android le déclarait explicitement non fiable. En plus, l'ancien code transformait cet état en `null`, valeur qui peut aussi signifier qu'un appareil ne fournit simplement pas d'incertitude numérique.

**Correction V2 :**

- ajout de `CelestialHeadingPolicyV2` ;
- états distincts `VALID`, `UNKNOWN_ACCURACY`, `INACCURATE`, `UNRELIABLE`, `STALE`, `UNAVAILABLE` ;
- seuil qualité HoraTrack : précision connue au-delà de **15°** = direction bloquée ;
- état Android `SENSOR_STATUS_UNRELIABLE` = direction bloquée ;
- dernier repère d'orientation vieux de plus de **5 s** = direction bloquée ;
- absence d'incertitude numérique sans alerte Android = `UNKNOWN_ACCURACY`, encore exploitable pour compatibilité ;
- le fallback magnétomètre est soumis à la même surveillance ;
- `SunIndicatorView` n'affiche plus une direction céleste que le système sait mauvaise ;
- `LightDirectionController` repasse sur son angle neutre et efface la direction solaire partagée lorsque le cap n'est pas exploitable ;
- le jour/nuit et l'altitude astronomique restent calculables car ils ne dépendent pas de la boussole.

Cette règle peut donc faire disparaître temporairement Soleil/Lune **uniquement lorsqu'une direction crédible n'est plus disponible**. C'est volontaire et différent de l'ancien bug : un astre ne disparaît plus parce qu'il est derrière le téléphone.

Audit détaillé : `docs/audits/HoraTrack_Celestial_Heading_Quality_Audit.md`.

### MOYEN — deux vues d’horloge

`activity_main.xml` contient encore `heroClockPermanent` et la vue fantôme `heroClockHands` 1×1. `heroClockPermanent` reste l’horloge canonique. Nettoyage différé jusqu’à validation visuelle finale.

### FAIBLE / PARITÉ — widget Android

Le widget Android dessine encore son propre cadran sans Soleil/Lune V2. Lot séparé après validation du rendu principal.

## Architecture V2

```text
heure civile Android + GPS + capteurs Android
        |
        v
CelestialTrackerV2
        +--> âge GPS monotone / localisation qualifiée
        +--> arbitrage multi-provider fail-closed
        +--> Nord vrai + cap filtré
        +--> CelestialHeadingPolicyV2 / qualité du cap
        +--> ticker astronomique 1 s
        +--> recheck localisation 30 s
        +--> HoraTrackV2.celestial / CelestialEngineV2
        +--> AtmosphericRefractionV2
        +--> CelestialScreenGeometryV2
                +--> carte 360° Terre au centre
                +--> azimut -> angle
                +--> altitude apparente -> rayon
                +--> terminateur et ombres physiques
```

## Tests de référence

Les tests couvrent maintenant notamment : nouvelle Lune et pleine Lune de référence, progression temporelle sur 10 secondes, positions Soleil/Lune comparées à une référence indépendante sur plusieurs latitudes, éclipses lunaires, qualité GPS et âge monotone, arbitrage entre providers, mesure imprécise ne remplaçant pas une position valide, qualité/fraîcheur du cap, ciel 360°, astre opposé au cap, posture à plat/inclinée/verticale, roulis au-delà de 90°, chaîne cardinale complète, cap stabilisé prioritaire, proximité Soleil/Lune sans fausse éclipse, éclipses solaires géométriques, réfraction près de l’horizon, seuil standard du disque, altitude intermédiaire, zénith, terminateur et axe anti-solaire.

## État actuel

Le moteur céleste V2 est maintenant organisé autour de responsabilités séparées : éphéméride géométrique, acquisition GPS/capteurs, arbitrage et qualification de localisation, qualification du cap, temps de rafraîchissement, correction optique d'altitude pour le rendu et projection 360° Terre au centre.

La prochaine validation téléphone doit vérifier surtout :

1. que Soleil/Lune ne disparaissent plus pendant un tour 360° lorsque le cap reste qualifié ;
2. que la direction angulaire correspond au ciel réel ;
3. que le cap reste stable sans retard excessif, y compris lorsque le téléphone est fortement incliné ou roulé ;
4. qu'une boussole volontairement perturbée ne continue plus à déplacer le ciel comme si la mesure était fiable ;
5. qu'une mesure réseau médiocre n'efface plus une bonne position GPS encore valide ;
6. que le mouvement céleste ne présente plus de petits sauts temporels de 30 secondes ;
7. que près du lever/coucher l’astre ne semble plus artificiellement trop bas ;
8. que la phase et les ombres restent orientées correctement.

Après validation visuelle, le nettoyage `heroClockHands` pourra être traité séparément. Le widget céleste restera ensuite un lot de parité distinct.
