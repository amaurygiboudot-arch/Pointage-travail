# HoraTrack — politique de nommage et de migration

## Nom produit canonique

**HoraTrack** est le nom visible canonique du produit sur Android et iOS.

Toute interface existante ou nouvelle, documentation utilisateur, notification, écran, libellé, titre visible, politique publique et nouvelle ressource visible doit utiliser `HoraTrack`.

Les seules exceptions sont les alias ou noms techniques explicitement conservés pour permettre aux anciennes installations de continuer à se mettre à jour ou à retrouver leurs données. Un nom historique ne doit pas rester visible simplement parce qu'il existait avant le renommage.

## Identifiants historiques à ne pas renommer sans migration

Les identifiants techniques déjà utilisés par des installations existantes restent stables tant qu'une migration dédiée n'est pas conçue et testée. Cela inclut notamment :

- l'`applicationId` Android `com.amaury.pointage` ;
- **l'identité de signature Android release actuellement utilisée par les APK distribués**, y compris le certificat/keystore ou une éventuelle lignée de signature explicitement supportée et testée ;
- le bundle identifier iOS existant ;
- les noms de `SharedPreferences` et leurs clés persistantes ;
- les authorities de providers dérivées de l'applicationId ;
- les topics/identifiants Firebase déjà utilisés ;
- les noms de fichiers ou dossiers servant de clés de compatibilité avec des versions déjà distribuées.

Un renommage cosmétique ne doit jamais modifier silencieusement ces identifiants. Conserver le seul nom de package Android ne suffit pas : une mise à jour APK doit également rester signée par une identité acceptée par l'installation existante et par le vérificateur de mise à jour de HoraTrack.

## Compatibilité des artefacts existants

Le fichier APK historique `HP-Travail.apk` reste accepté comme **alias technique de compatibilité** par le mécanisme de mise à jour tant que des versions déjà distribuées le recherchent. Ce nom ne doit pas être utilisé comme marque visible dans l'interface ; l'utilisateur voit `HoraTrack`.

Les dossiers ou fichiers déjà créés chez l'utilisateur ne sont pas renommés automatiquement lorsqu'un renommage risquerait de casser une référence persistante. Un éventuel renommage devra être idempotent, conserver les données et être couvert par des tests de migration.

## Règle pour le code et les contenus

- **Visible par l'utilisateur, existant ou nouveau** : `HoraTrack`.
- **Identifiant technique nouveau** : utiliser un nom `horatrack_*` quand il n'existe aucune contrainte de compatibilité.
- **Identifiant technique existant** : le conserver jusqu'à une migration dédiée.
- **Artefact historique requis pour les mises à jour** : peut conserver un alias technique ancien, mais cet alias n'est pas la marque du produit.
- Aucun changement de marque ne doit être mélangé à une modification fonctionnelle sans test de non-régression des mises à jour et des données.

## Migration future complète

Une migration complète des identifiants historiques exige au minimum :

1. inventaire de toutes les clés, topics, authorities, signatures, noms de fichiers et dossiers concernés ;
2. migration locale atomique et réexécutable ;
3. **compatibilité avec chaque schéma/version déjà distribuée qui peut installer directement la version courante**. Comme le mécanisme de mise à jour permet aujourd'hui à une ancienne version de sauter directement vers la dernière release, il ne suffit pas de tester seulement la dernière version historique ;
4. si certaines très anciennes versions ne doivent plus migrer directement, mise en place d'un mécanisme de mise à niveau par étapes explicitement imposé et testé avant de réduire cette matrice de compatibilité ;
5. conservation de l'identité de signature Android release, ou migration de lignée de signature explicitement supportée par Android **et** par `ApkUpdateVerifier`, avec tests réels d'upgrade ;
6. validation des mises à jour APK/AAB, Firebase et Google Play ;
7. validation iOS du bundle/provisioning si le bundle identifier devait changer ;
8. tests de restauration et de non-régression des données avant suppression des alias historiques.

Tant que ces conditions ne sont pas réunies, l'identité visible est HoraTrack et les identifiants historiques nécessaires à la compatibilité restent volontairement inchangés.
