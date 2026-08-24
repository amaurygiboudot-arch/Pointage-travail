# HoraTrack — schéma multiplateforme de pointage v1

Ce document définit le contrat de données **avant toute future synchronisation Android/iOS**. Il ne rend pas la synchronisation active.

## Version

`schemaVersion: 1`

Toute évolution incompatible devra créer une nouvelle version et une migration explicite. Un client ne doit jamais écraser silencieusement des champs qu'il ne comprend pas.

## Session

Chaque session synchronisable devra contenir au minimum :

```text
schemaVersion: Int
sessionId: UUID/String stable
userId: String
entryEpochMs: Long
exitEpochMs: Long?
shiftType: morning | day | afternoon | night | null
autoPauseMinutes: Int (0..240)
manual: Boolean
companySlot: Int?
zoneId: String?
zoneAddress: String?
createdAtEpochMs: Long
updatedAtEpochMs: Long
originPlatform: android | ios
revision: Long
pauses: [Pause]
```

`shiftType` utilise en v1 uniquement les littéraux canoniques `morning`, `day`, `afternoon` et `night` (ou `null` si absent). Une plateforme ne doit pas émettre de valeur localisée telle que `matin`. Une valeur inconnue provenant d'une version future doit néanmoins être conservée lors d'un round-trip sans être silencieusement remplacée ; elle ne devient pas pour autant une valeur v1 valide à produire.

`autoPauseMinutes` est un entier canonique borné à **0..240**. Toute donnée reçue hors de cette plage doit être normalisée avec la même borne avant calcul sur Android et iOS, afin que les deux plateformes produisent le même temps travaillé.

`manual` au niveau **Session** indique qu'une session a été créée/saisie manuellement. Il est distinct de `Pause.manual`. `companySlot` conserve l'identifiant numérique de l'entreprise/emplacement choisi par le flux Android (actuellement `1` ou `2`). Sa représentation canonique est un entier nullable ; une valeur numérique future inconnue doit être préservée telle quelle sans interprétation ni conversion en chaîne.

`entryEpochMs` et `exitEpochMs` sont stockés en millisecondes UTC depuis l'époque Unix. L'affichage local utilise le fuseau du terminal ; les instants synchronisés ne doivent pas être convertis en chaînes locales.

### Règle de déduction des pauses

`autoPauseMinutes` est un **plancher de déduction**, pas une pause supplémentaire à additionner aux pauses enregistrées. Pour une session donnée, les intervalles de pause sont d'abord bornés à la session puis fusionnés afin d'éviter tout double comptage. Après normalisation de `autoPauseMinutes` dans la plage `0..240`, la durée de pause retenue pour le calcul du temps travaillé est :

```text
normalizedAutoPauseMinutes = clamp(autoPauseMinutes, 0, 240)
pauseDeductedMs = max(mergedRecordedPauseMs, normalizedAutoPauseMinutes * 60_000)
workedMs = max(0, sessionDurationMs - pauseDeductedMs)
```

Android et iOS devront appliquer exactement cette règle. `autoPauseMinutes` ne doit donc jamais être ajouté à la durée des pauses enregistrées lorsqu'elles atteignent déjà ou dépassent ce plancher.

## Pause

```text
pauseId: UUID/String stable
startEpochMs: Long
endEpochMs: Long?
manual: Boolean
automatic: Boolean
createdAtEpochMs: Long
updatedAtEpochMs: Long
revision: Long
```

Les identifiants de session et de pause restent stables après modification afin de permettre les mises à jour et la résolution des conflits sans comparaison fragile de timestamps.

## Compatibilité Android / iOS

Avant activation d'une synchronisation :

- Android doit préserver les champs de session `shiftType`, `autoPauseMinutes`, `manual`, `companySlot`, `zoneId` et `zoneAddress`, ainsi que les champs de pause `manual` et `automatic` ;
- iOS doit pouvoir décoder et réécrire ces champs même si son interface ne les exploite pas encore ;
- `shiftType` émis en v1 est limité à `morning`, `day`, `afternoon`, `night` ou `null` ;
- `autoPauseMinutes` est normalisé à `0..240` avant tout calcul sur les deux plateformes ;
- `companySlot` est un entier nullable sur les deux plateformes afin de rester compatible avec les JSON Android existants ;
- `Session.manual` et `Pause.manual` sont deux informations différentes et ne doivent jamais être fusionnées ou substituées ;
- les deux plateformes doivent calculer la pause déduite avec `max(pauses fusionnées, autoPauseMinutes normalisé)` et jamais en additionnant les deux ;
- les champs inconnus d'une version plus récente ne doivent pas être supprimés par un ancien client ;
- les pauses sont bornées à la session et fusionnées uniquement pour les calculs de durée, jamais pour supprimer leurs identités persistantes.

## Conflits

Une synchronisation future devra comparer `revision` et `updatedAtEpochMs`. Deux modifications concurrentes ne doivent pas être fusionnées silencieusement. Le protocole devra soit conserver les deux variantes pour résolution, soit appliquer une règle documentée et testée par type de champ.

## Sécurité

Une future collection distante devra être isolée par `userId`, protégée par des règles Firestore testées avec Emulator, et ne devenir source de vérité qu'après migration et tests de convergence Android/iOS.

Jusqu'à cette étape, **aucune synchronisation de pointages n'est active et le stockage local de chaque plateforme reste sa propre source de vérité**.
