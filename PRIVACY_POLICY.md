# Politique de confidentialité — HoraTrack

Dernière mise à jour : 26 août 2026

HoraTrack est une application de pointage personnel permettant d'enregistrer des heures de travail, de gérer des pauses, de produire des rapports, d'estimer certains éléments de rémunération et, si l'utilisateur l'active, d'automatiser des entrées et sorties à l'aide de zones géographiques configurées par l'utilisateur.

## Principe général

HoraTrack fonctionne d'abord localement sur l'appareil. Les fonctions de sauvegarde cloud nécessitent un compte Google connecté à l'application. Lorsque cette sauvegarde est active, certaines données utilisateur sont copiées dans Firebase/Cloud Firestore afin de permettre leur récupération après une réinstallation ou sur un autre appareil connecté au même compte.

Les données cloud sont rangées sous l'identifiant Firebase du compte connecté. Les règles Firestore de l'application limitent l'accès aux historiques et sauvegardes complètes au propriétaire de ce compte.

## Données utilisées

### Données de pointage

Les heures d'entrée, de sortie, pauses, durées travaillées, indications de saisie manuelle et lieux associés sont enregistrés localement afin d'afficher l'historique et d'effectuer les calculs demandés.

Lorsque l'utilisateur est connecté à son compte Google et que la synchronisation cloud est disponible, une copie de l'historique de pointage est enregistrée dans Cloud Firestore afin de permettre la synchronisation et la restauration.

### Localisation et lieux de travail

HoraTrack peut demander l'accès à la localisation précise et, uniquement pour le pointage automatique en arrière-plan, à la localisation en arrière-plan.

La localisation sert à détecter l'entrée ou la sortie de zones de travail configurées ou confirmées par l'utilisateur. HoraTrack n'utilise pas la localisation à des fins publicitaires, de profilage commercial ou de vente de données.

Les noms des lieux, adresses, coordonnées géographiques, rayons de zone GPS et associations avec les entreprises sont conservés localement. Lorsqu'une sauvegarde complète du compte est active, ces réglages peuvent aussi être sauvegardés dans Cloud Firestore afin d'être restaurés après réinstallation.

### Données salariales et entreprises

Le taux horaire, les informations d'entreprise saisies ou recherchées, la convention collective choisie, la date d'entrée et les autres réglages nécessaires aux estimations sont conservés localement. Ils peuvent également faire partie de la sauvegarde complète Cloud Firestore lorsque l'utilisateur est connecté.

Les recherches d'entreprise peuvent interroger des services publics français, notamment l'API Recherche d'entreprises et Légifrance. Les données saisies dans ces recherches sont transmises aux services concernés uniquement pour exécuter la recherche demandée.

### Réglages et personnalisation

Les préférences d'affichage, thèmes, réglages du widget, programmations de pause, paramètres fonctionnels et autres préférences utilisateur sont conservés localement. Une copie peut être enregistrée dans Cloud Firestore pour restauration.

Un fond d'écran personnalisé choisi par l'utilisateur peut être inclus dans la sauvegarde cloud. Il est découpé en plusieurs documents techniques pour respecter les limites du service.

### Données techniques d'appareil

Lorsqu'un compte Google est connecté, HoraTrack peut enregistrer des informations techniques nécessaires au registre des installations : identifiant aléatoire d'installation, fabricant et modèle de l'appareil, version Android, version de l'application et date de dernière activité. Cet identifiant est créé par l'application et n'est pas l'identifiant matériel permanent du téléphone.

## Compte Google, Firebase et Cloud Firestore

La connexion Google utilise Firebase Authentication. Cloud Firestore est utilisé pour la synchronisation de l'historique et la sauvegarde/restauration des données utilisateur.

Les jetons d'authentification Google/Firebase, clés de sécurité et autorisations Android propres à l'appareil ne sont pas inclus dans la sauvegarde utilisateur. Après une réinstallation, ces éléments sont recréés ou redemandés par Android et Google.

Firebase App Check / Play Integrity peut être utilisé pour vérifier que les requêtes proviennent d'une installation légitime de l'application.

## Google Drive

La sauvegarde Google Drive est distincte de la sauvegarde Firestore. Elle est facultative et sert principalement à enregistrer des rapports PDF dans un dossier choisi explicitement avec le sélecteur de fichiers Android.

HoraTrack ne demande pas un accès général à l'ensemble du contenu du compte Google Drive. Après une désinstallation, Android peut demander à l'utilisateur d'autoriser de nouveau le dossier choisi.

## Contacts et SMS

L'utilisateur peut enregistrer manuellement un nom et un numéro de téléphone pour un lieu de travail. HoraTrack ne lit pas automatiquement le carnet d'adresses du téléphone.

Lorsqu'une notification propose de prévenir un contact à l'arrivée, HoraTrack prépare un message dans l'application SMS choisie par l'utilisateur. Le message n'est pas envoyé automatiquement : l'utilisateur conserve la validation finale.

Les noms et numéros enregistrés dans les réglages peuvent faire partie de la sauvegarde complète du compte lorsque celle-ci est active.

## Rapports PDF

Les rapports PDF sont générés à partir des données de pointage. L'utilisateur peut choisir où les enregistrer. Si Google Drive a été configuré, des copies peuvent être écrites dans le dossier explicitement sélectionné.

## Rapports techniques et suggestions

HoraTrack peut utiliser Sentry pour recevoir des rapports techniques. Les rapports automatiques de crash ne sont envoyés que si l'utilisateur les a autorisés dans les réglages. L'application configure Sentry pour ne pas envoyer les informations personnelles par défaut et nettoie notamment les informations d'utilisateur et de requête avant envoi.

Une suggestion saisie volontairement dans la boîte à idées peut être transmise au service technique utilisé par l'application avec des informations techniques limitées, telles que la version de l'application, la version Android et le modèle de l'appareil.

## Mises à jour et notifications

HoraTrack peut contacter GitHub pour vérifier l'existence d'une nouvelle version lorsque le canal de mise à jour directe est utilisé. Firebase Cloud Messaging peut également être utilisé pour envoyer une notification indiquant qu'une nouvelle version est disponible.

## Partage et vente de données

HoraTrack ne contient pas de publicité et ne vend pas les données personnelles de l'utilisateur.

Les données peuvent être traitées par les prestataires techniques nécessaires aux fonctions choisies : Google/Firebase pour l'authentification, Cloud Firestore, App Check et les notifications ; Google Drive pour les rapports choisis par l'utilisateur ; Sentry pour les rapports techniques autorisés ; GitHub pour le canal de mise à jour directe ; et les services publics interrogés lors d'une recherche d'entreprise.

## Sécurité

Les données locales principales sont conservées dans l'espace privé de l'application. Les sauvegardes Android automatiques génériques sont désactivées afin d'éviter une restauration incontrôlée ; HoraTrack utilise son propre mécanisme de sauvegarde lié au compte lorsque celui-ci est disponible.

Les connexions HTTP non chiffrées sont interdites par la configuration réseau de l'application. Les mises à jour APK directes sont vérifiées par empreinte SHA-256, nom de package et certificat de signature avant installation.

Les règles Cloud Firestore limitent les collections d'historique et de sauvegarde complète au compte authentifié correspondant.

## Permissions Android

Selon les fonctions utilisées, HoraTrack peut demander :

- localisation approximative ou précise ;
- localisation en arrière-plan lorsque le pointage automatique par zone est activé ;
- notifications pour les alertes de pointage, pauses, contacts ou mises à jour ;
- accès Internet pour les services en ligne ;
- autorisation d'installation de packages pour le canal de mise à jour APK directe, lorsque ce canal est utilisé hors Google Play.

L'utilisateur peut refuser ou retirer les autorisations depuis les paramètres Android. Le pointage manuel doit rester disponible sans localisation.

## Conservation et suppression

Les données locales sont conservées jusqu'à leur suppression dans l'application, l'effacement des données Android ou la désinstallation.

Les copies Cloud Firestore sont conservées pour permettre la synchronisation et la restauration tant qu'elles ne sont pas supprimées par les fonctions prévues ou par une procédure de suppression de compte/données. La suppression locale d'un pointage synchronisé est répercutée sur sa copie cloud par le moteur de synchronisation.

Les fichiers PDF enregistrés en dehors du stockage privé, notamment dans Google Drive, restent sous le contrôle de l'utilisateur et doivent être supprimés depuis leur emplacement de stockage.

## Enfants

HoraTrack n'est pas conçu spécifiquement pour les enfants et ne propose pas de publicité ciblée destinée aux mineurs.

## Contact

Responsable de l'application : Amaury Giboudot.

Pour toute question concernant la confidentialité ou l'exercice de droits relatifs aux données, le canal de contact public indiqué sur la fiche Google Play de HoraTrack devra être utilisé.

## Évolutions

Cette politique est mise à jour lorsque les fonctionnalités, traitements de données ou exigences réglementaires évoluent. La date de dernière mise à jour figure en haut de ce document.
