# Sécurité — HP Travail

HP Travail est conçu pour limiter les permissions au strict nécessaire et pour produire des APK de distribution signés.

## Principes

- Aucun mot de passe ni clé de signature ne doit être stocké dans le dépôt.
- Les APK de distribution doivent être générés en mode `release` et signés avec la même clé privée conservée hors du dépôt.
- Les connexions réseau en clair HTTP sont interdites ; seules les connexions HTTPS sont autorisées.
- Les sauvegardes Android automatiques de données applicatives sont désactivées.
- L'application ne demande pas la permission d'installer d'autres applications.
- L'application ne demande pas la permission d'envoyer silencieusement des SMS.
- Les permissions de localisation et notifications sont utilisées uniquement pour les fonctions de pointage GPS et d'alerte à l'arrivée.
- Les composants Android non destinés aux autres applications sont non exportés.
- Le code release est réduit et obfusqué avec R8.

## Signature

La clé de signature release doit être conservée uniquement dans un emplacement privé. Dans GitHub Actions, elle doit être fournie via les secrets :

- `POINTAGE_KEYSTORE_B64`
- `POINTAGE_KEYSTORE_PASSWORD`
- `POINTAGE_KEY_ALIAS`
- `POINTAGE_KEY_PASSWORD`

Ne jamais publier le fichier keystore ni ces valeurs.

## Vérification avant distribution

Chaque build release vérifie la signature de l'APK et publie également son empreinte SHA-256. L'empreinte permet de vérifier que le fichier téléchargé n'a pas été modifié après compilation.

## Signalement

Toute copie non autorisée, redistribution ou modification destinée à être distribuée est interdite par la licence propriétaire du projet.
