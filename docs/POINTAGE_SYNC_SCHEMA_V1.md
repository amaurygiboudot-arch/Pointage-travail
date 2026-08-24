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
shiftType: String?
autoPauseMinutes: Int
manual: Boolean
companySlot: String?
zoneId: String?
zoneAddress: String?
createdAtEpochMs: Long
updatedAtEpochMs: Long
originPlatform: android | ios
revision: Long
pauses: [Pause]
```

`manual` au niveau **Session** indique qu'une session a été créée/saisie manuellement. Il est distinct de `Pause.manual`. `companySlot` conserve l'association éventuelle de la session avec l'entreprise/emplacement choisi par le flux Android ; une valeur inconnue d'une plateforme doit être préservée sans interprétation ni suppression.

`entryEpochMs` et `exitEpochMs` sont stockés en millisecondes UTC depuis l'époque Unix. L'affichage local utilise le fuseau du terminal ; les instants synchronisés ne doivent pas être convertis en chaînes locales.

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
- `Session.manual` et `Pause.manual` sont deux informations différentes et ne doivent jamais être fusionnées ou substituées ;
- les champs inconnus d'une version plus récente ne doivent pas être supprimés par un ancien client ;
- les pauses sont bornées à la session et fusionnées uniquement pour les calculs de durée, jamais pour supprimer leurs identités persistantes.

## Conflits

Une synchronisation future devra comparer `revision` et `updatedAtEpochMs`. Deux modifications concurrentes ne doivent pas être fusionnées silencieusement. Le protocole devra soit conserver les deux variantes pour résolution, soit appliquer une règle documentée et testée par type de champ.

## Sécurité

Une future collection distante devra être isolée par `userId`, protégée par des règles Firestore testées avec Emulator, et ne devenir source de vérité qu'après migration et tests de convergence Android/iOS.

Jusqu'à cette étape, **aucune synchronisation de pointages n'est active et le stockage local de chaque plateforme reste sa propre source de vérité**.
