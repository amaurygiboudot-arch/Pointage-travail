# HoraTrack — Audit céleste V2 : temps, UTC et continuité

## Périmètre

Ce document audite uniquement la source de temps utilisée par l’horloge astronomique et la continuité du suivi Soleil/Lune. Il ne modifie pas le moteur de temps métier, le pointage, la paie ou les règles juridiques.

## Référentiel temporel correct

`CelestialEngineV2` reçoit un instant Unix en millisecondes et le convertit en jour julien. Cet instant est absolu : il ne dépend pas du fuseau horaire affiché par Android. Il n’y a donc pas à ajouter ou retirer manuellement le décalage France/UTC dans l’éphéméride.

L’horloge analogique, elle, utilise volontairement l’heure locale du téléphone pour placer ses aiguilles. Les deux représentations sont compatibles : les aiguilles montrent l’heure civile locale alors que le moteur astronomique travaille sur l’instant absolu correspondant.

## Défaut trouvé — éphéméride rafraîchie seulement toutes les 30 secondes

Avant ce lot, `CelestialTrackerV2` utilisait un seul ticker de 30 secondes pour deux responsabilités différentes :

- recontrôler la localisation ;
- recalculer les positions astronomiques.

Entre deux ticks, les événements d’orientation pouvaient rafraîchir l’écran mais transportaient le même `CelestialSnapshotV2`. Le Soleil et la Lune étaient donc mathématiquement figés jusqu’au prochain recalcul, puis avançaient par petits sauts.

Ce comportement est discret à l’œil nu, mais il est contraire au principe d’un suivi céleste continu.

### Correction V2

Les cadences sont maintenant séparées :

- éphéméride Soleil/Lune : recalcul toutes les **1 seconde** tant qu’un consommateur céleste est actif ;
- relecture périodique de la meilleure localisation : toutes les **30 secondes** ;
- mises à jour GPS réelles : toujours appliquées immédiatement lorsqu’Android en fournit une ;
- orientation du téléphone : reste pilotée par les événements capteurs et son propre filtrage.

Le calcul astronomique à 1 Hz est très léger et évite de solliciter le GPS à 1 Hz.

## Défaut trouvé — fraîcheur GPS fondée sur l’horloge murale

La qualification d’une position comparait auparavant `System.currentTimeMillis()` avec `Location.time`. Cette différence peut être faussée si l’heure du téléphone est corrigée pendant l’exécution.

### Correction V2

Sur Android, l’âge d’une position est maintenant calculé en priorité avec :

- `Location.elapsedRealtimeNanos` pour l’instant monotone de la mesure GPS ;
- `SystemClock.elapsedRealtimeNanos()` pour l’instant monotone courant.

Cette base de temps ne sert pas à calculer la position du Soleil : elle sert uniquement à mesurer une **durée** de vieillissement de la position. Un fallback vers les timestamps muraux reste présent pour les positions où le temps monotone n’est pas exploitable.

`CelestialTrackingPolicyV2.classifyAge()` devient l’API canonique de qualification de fraîcheur ; l’ancienne API reste compatible pour les appelants existants.

## Horloge murale et précision absolue

Le moteur astronomique doit connaître une date et une heure réelles. Il continue donc d’utiliser l’horloge civile Android (`System.currentTimeMillis()`) comme instant astronomique. Remplacer cet instant par `elapsedRealtime` serait faux : une durée depuis le démarrage du téléphone n’est pas une date UTC.

Conséquence assumée : si l’utilisateur règle volontairement une mauvaise date/heure sur Android, le ciel calculé sera décalé. HoraTrack ne substitue pas silencieusement une autre heure au système, afin de ne pas créer une contradiction entre les aiguilles de l’horloge et le ciel affiché.

Une amélioration diagnostique future pourra comparer l’heure système à une horloge réseau/GNSS lorsqu’Android en fournit une, puis signaler un écart important. Ce diagnostic ne doit pas être confondu avec le moteur métier de temps.

## Tests

Les tests V2 couvrent maintenant :

- qualification directe d’un âge GPS monotone récent ;
- rejet d’un âge trop ancien ;
- rejet d’un âge anormalement futur ;
- compatibilité de l’ancienne API de qualification ;
- progression mesurable mais sans saut de la position solaire sur un intervalle de 10 secondes.

## État après audit

Le chemin temporel céleste devient :

```text
heure civile Android (instant Unix réel)
        |
        +--> aiguilles analogiques : affichage heure locale
        |
        +--> CelestialEngineV2 : instant absolu -> jour julien -> ciel réel

SystemClock.elapsedRealtime* (monotone)
        |
        +--> cadence des rafraîchissements
        +--> âge/fraîcheur de la localisation
```

Le découplage est volontaire : **l’heure réelle sert à l’astronomie ; le temps monotone sert à mesurer les durées**.
