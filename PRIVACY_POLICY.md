# Politique de confidentialité — HoraTrack

Dernière mise à jour : 24 août 2026

HoraTrack est une application de pointage personnel permettant d'enregistrer des heures de travail, de produire des rapports PDF et, si l'utilisateur l'active, d'automatiser les entrées et sorties à l'aide de zones géographiques configurées par l'utilisateur.

## Données utilisées

### Localisation

HoraTrack peut demander l'accès à la localisation précise et à la localisation en arrière-plan lorsque l'utilisateur active le pointage automatique par zone GPS.

Cette autorisation sert uniquement à détecter l'entrée ou la sortie des lieux de travail enregistrés par l'utilisateur afin de déclencher le pointage automatique. La localisation en arrière-plan est nécessaire pour cette fonction lorsque l'application n'est pas visible à l'écran.

HoraTrack n'utilise pas la localisation à des fins publicitaires, de profilage ou de suivi commercial.

L'application utilise les services de localisation Android et Google Play Services pour la fonction de géorepérage lorsque ces services sont disponibles sur l'appareil.

### Adresses et lieux de travail

Les noms des lieux, adresses, coordonnées géographiques, rayons de zone GPS, noms de contacts et numéros de téléphone saisis par l'utilisateur sont conservés dans le stockage privé de l'application sur l'appareil.

### Données de pointage

Les heures d'entrée, de sortie, durées travaillées et lieux associés sont enregistrés localement sur l'appareil afin d'afficher l'historique et de calculer les heures de travail.

### Données salariales

Le taux horaire, la convention collective choisie et la date d'entrée dans l'entreprise sont enregistrés localement afin de réaliser les estimations demandées par l'utilisateur.

## Export PDF vers Google Drive

L'export PDF vers Google Drive est facultatif. Il n'est activé que si l'utilisateur choisit lui-même un dossier avec le sélecteur de fichiers Android. HoraTrack utilise alors cette autorisation uniquement pour écrire les rapports PDF dans le dossier choisi.

Ces fichiers PDF sont des **archives lisibles et des rapports**, pas une sauvegarde restaurable de la base de données de l'application. Ils ne permettent pas, à eux seuls, de reconstruire automatiquement l'historique dans HoraTrack après une réinstallation ou un changement de téléphone.

HoraTrack ne demande pas un accès général au contenu du compte Google Drive de l'utilisateur.

## Contacts et SMS

L'utilisateur peut enregistrer manuellement un nom et un numéro de téléphone pour un lieu de travail. HoraTrack ne lit pas le carnet d'adresses du téléphone.

Lorsqu'une notification propose de prévenir un contact à l'arrivée, HoraTrack prépare un message dans l'application SMS choisie par l'utilisateur. Le message n'est pas envoyé automatiquement : l'utilisateur conserve la validation finale.

## Rapports PDF

Les rapports PDF sont générés à partir des données de pointage de l'utilisateur. L'utilisateur peut les prévisualiser puis choisir où les enregistrer. Si l'export Drive a été configuré, des copies peuvent être écrites dans le dossier explicitement sélectionné par l'utilisateur.

## Partage et vente de données

HoraTrack ne contient pas de publicité et ne vend pas les données personnelles de l'utilisateur.

L'application ne dispose pas d'un serveur exploité par le développeur destiné à recevoir l'historique de pointage, les adresses, les coordonnées GPS, les informations salariales ou les numéros de téléphone de l'utilisateur. Les services Firebase éventuellement utilisés par des fonctions distinctes (compte, appareils, feedback, notifications) sont soumis à leurs règles et ne constituent pas une synchronisation automatique de l'historique de pointage.

Certaines fonctions reposent sur des services du système Android ou de Google Play Services. Leur traitement est soumis aux règles applicables à ces services sur l'appareil.

## Sécurité et politique de sauvegarde Android

Les données principales sont conservées dans l'espace privé de l'application. Les sauvegardes Android automatiques sont volontairement désactivées afin d'éviter qu'une copie non maîtrisée de données de pointage, localisation ou salaire soit transférée par le mécanisme général de sauvegarde du système.

Cette protection a une contrepartie importante : **la désinstallation, l'effacement des données ou la perte du téléphone peut entraîner la perte de l'historique local** tant qu'un véritable export structuré, chiffré et réimportable n'est pas proposé.

Les exports PDF Drive ne remplacent pas cette sauvegarde structurée. Une future fonction de restauration devra utiliser un format versionné, chiffré et explicitement réimportable.

Les connexions HTTP non chiffrées sont interdites par la configuration de l'application.

## Permissions Android

Selon les fonctions activées, HoraTrack peut demander :

- localisation approximative ou précise ;
- localisation en arrière-plan pour le pointage automatique par zone GPS ;
- notifications pour les alertes de pointage ou de contact ;
- accès Internet pour certains services nécessaires au fonctionnement, notamment le catalogue des conventions collectives et les services en ligne utilisés par le système.

L'utilisateur peut refuser ou retirer les autorisations depuis les paramètres Android. Le pointage manuel reste disponible lorsque la localisation automatique n'est pas autorisée.

## Conservation et suppression

Les données locales sont conservées tant que l'utilisateur garde l'application et son historique, ou jusqu'à ce qu'il les supprime via les fonctions disponibles ou en supprimant les données de l'application depuis Android.

Les fichiers PDF enregistrés en dehors du stockage privé de l'application, notamment dans Google Drive, restent sous le contrôle de l'utilisateur et doivent être supprimés depuis l'emplacement où ils ont été enregistrés.

## Enfants

HoraTrack n'est pas conçu spécifiquement pour les enfants et ne propose pas de fonctionnalités sociales ou publicitaires destinées aux mineurs.

## Contact

Responsable de l'application : Amaury Giboudot.

Pour toute question concernant la confidentialité ou l'exercice de droits relatifs aux données, le canal de contact public indiqué sur la fiche Google Play de HoraTrack devra être utilisé.

## Évolutions

Cette politique peut être mise à jour lorsque les fonctionnalités ou les exigences réglementaires évoluent. La date de dernière mise à jour est indiquée en haut de cette page.
