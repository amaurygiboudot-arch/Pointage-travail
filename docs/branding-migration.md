# HoraTrack — politique de nommage et de migration

## Nom produit canonique

**HoraTrack** est le nom visible canonique du produit sur Android et iOS.

Toute nouvelle interface, documentation utilisateur, notification, titre de release et nouvelle ressource visible doit utiliser `HoraTrack`.

## Identifiants historiques à ne pas renommer sans migration

Les identifiants techniques déjà utilisés par des installations existantes restent stables tant qu'une migration dédiée n'est pas conçue et testée. Cela inclut notamment :

- l'`applicationId` Android `com.amaury.pointage` ;
- le bundle identifier iOS existant ;
- les noms de `SharedPreferences` et leurs clés persistantes ;
- les authorities de providers dérivées de l'applicationId ;
- les topics/identifiants Firebase déjà utilisés ;
- les noms de fichiers ou dossiers servant de clés de compatibilité avec des versions déjà distribuées.

Un renommage cosmétique ne doit jamais modifier silencieusement ces identifiants, car cela pourrait casser les mises à jour, les données locales, les notifications, les sauvegardes ou l'authentification.

## Compatibilité des artefacts existants

Le fichier APK historique `HP-Travail.apk` reste accepté comme nom de compatibilité par le mécanisme de mise à jour tant que les anciennes versions de l'application le recherchent. Les nouvelles interfaces doivent cependant afficher `HoraTrack`.

Les dossiers ou fichiers déjà créés chez l'utilisateur ne sont pas renommés automatiquement. Un éventuel renommage devra être idempotent, conserver les données et être couvert par des tests de migration.

## Règle pour le code nouveau

- **Visible par l'utilisateur** : `HoraTrack`.
- **Identifiant technique nouveau** : utiliser un nom `horatrack_*` quand il n'existe aucune contrainte de compatibilité.
- **Identifiant technique existant** : le conserver jusqu'à une migration dédiée.
- Aucun changement de marque ne doit être mélangé à une modification fonctionnelle sans test de non-régression des mises à jour et des données.

## Migration future complète

Une migration complète des identifiants historiques exige au minimum :

1. inventaire des clés, topics, authorities, noms de fichiers et dossiers concernés ;
2. migration locale atomique et réexécutable ;
3. compatibilité ascendante avec au moins la dernière version distribuée sous l'ancien nom ;
4. validation des mises à jour APK/AAB, Firebase et Google Play ;
5. validation iOS du bundle/provisioning si le bundle identifier devait changer ;
6. tests de restauration des données avant suppression des alias historiques.

Tant que ces conditions ne sont pas réunies, l'identité visible est HoraTrack et les identifiants historiques nécessaires à la compatibilité restent volontairement inchangés.
