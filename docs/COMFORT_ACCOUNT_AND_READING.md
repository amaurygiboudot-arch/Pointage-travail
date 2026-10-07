# Confort du compte et lecture

## Sauvegarde volontaire entre appareils

Les trois réglages portables du format `agkgmg.comfort` peuvent être sauvegardés dans le compte déjà connecté. L’écran n’effectue aucune lecture ou écriture réseau sans action de l’utilisateur. Il faut lire la version du serveur avant d’enregistrer. Le bouton de restauration affiche la version et les valeurs concernées ; la confirmation modifie seulement les trois réglages locaux.

Le document existant dans la collection autorisée est `users/{uid}/app_backup/comfort_shared_v1`. Les règles Firestore existantes imposent le propriétaire ; elles ne sont pas modifiées. Le contrat client exige cinq champs : `schemaVersion:1`, `revision` entier entre 1 et 1 milliard, `payload` au format commun, `deleted` booléen et `updatedAt` timestamp serveur. Une transaction compare la révision attendue, puis l’incrémente. Les conflits sont refusés et nécessitent une nouvelle lecture explicite.

La suppression conserve une révision avec `deleted:true` et un payload vide. Cela empêche un ancien appareil de réécrire silencieusement les préférences effacées. Les réglages déjà présents sur les appareils ne sont pas supprimés. Aucun brouillon, texte utilisateur, identifiant de module, salaire ou pointage ne figure dans le payload.

## Lecture vocale native

Le lecteur agrandi propose Lire/Arrêter sur demande. Android sélectionne une voix déclarée installée et sans besoin réseau par le moteur système ; l’absence de voix compatible produit un message, sans téléchargement ni repli distant. Le texte est borné et découpé sans perte ni coupure des caractères Unicode reconnus. iOS utilise une voix Apple française native disponible. La lecture s’arrête à la fermeture, au changement de compte et au passage en arrière-plan.

Il reste nécessaire de tester réellement les moteurs installés, les interruptions audio et TalkBack/VoiceOver sur appareils. Un moteur tiers Android est responsable du respect des capacités hors ligne qu’il déclare.

## Horaire de nuit

Le profil natif peut activer un horaire local : par défaut 22 h–7 h, désactivé initialement. Début inclus, fin exclue ; un intervalle traversant minuit est accepté. Un début égal à la fin est refusé. Le contexte manuel reste prioritaire. La vérification s’effectue au premier plan à la minute, sans alarme, service, réveil ou traitement en arrière-plan. Les changements d’heure/fuseau sont pris en compte par l’horloge locale.

Les anciens profils restent lisibles et reçoivent les valeurs par défaut si les nouveaux champs sont absents. Les valeurs présentes invalides sont rejetées. L’horaire reste une préférence native et ne fait pas partie des trois réglages transférés.

## Preuve émulateur locale

Le 7 octobre 2026, `npm --prefix firestore-rules-tests test` a exécuté les règles réelles dans l’émulateur Firestore du projet local `demo-horatrack` : 7 tests réussis. Les trois nouveaux scénarios vérifient accès du propriétaire/refus d’un autre compte ou invité, deux transactions concurrentes sur la même révision, et rejet d’une ancienne écriture après suppression versionnée. Aucune donnée de production utilisée. Ces tests exercent le serveur et des clients SDK simulés ; ils ne remplacent pas les parcours tactiles, audio et changements de session sur téléphones.
