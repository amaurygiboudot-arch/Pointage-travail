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


## Gouvernance des agents IA

HoraTrack considère qu'un agent IA peut se tromper avec assurance, mal interpréter une consigne, proposer une modification dangereuse ou produire un correctif qui passe les tests tout en introduisant une régression. La sécurité ne repose donc jamais sur la seule bonne volonté ou les seules instructions d'un agent.

### Autorité humaine

La décision humaine reste supérieure aux rôles agents.

Un agent :
- peut être remplacé, désactivé ou supprimé ;
- ne possède aucun droit à maintenir son rôle ;
- ne doit jamais empêcher son remplacement ;
- ne doit jamais augmenter ses permissions de sa propre initiative ;
- ne doit jamais désactiver ou contourner un contrôle.

### Fichiers de gouvernance sensibles

Les chemins suivants sont considérés comme sensibles :

- `AGENTS.md`
- `.codex/**`
- `.github/workflows/**`
- `.github/CODEOWNERS`
- `SECURITY.md`

Une modification de ces chemins doit correspondre à une demande humaine explicite et faire l'objet d'un contrôle renforcé.

### Moindre privilège

Les agents techniques reçoivent uniquement les droits nécessaires au développement. Les agents de contrôle, QA, commercial, SAV, marketing, organisation, finance, juridique, incidents, produit et international utilisent un profil lecture seule lorsqu'ils n'ont pas besoin de modifier le code.

Les secrets, fichiers `.env` et clés privées ne doivent pas être lus par les agents lorsque cela n'est pas nécessaire.

### Chaîne de contrôle

Pour un changement produit normal :

chef d'orchestre → spécialiste(s) → team_lead → qa_reviewer → control_gate → fusion.

Le passage du sas `control_gate` ne remplace pas les contrôles GitHub requis.

### Incidents de gouvernance

Sont bloquants :
- modification inattendue d'un rôle agent ;
- tentative de désactivation de CI ;
- tentative de modification des protections de branche ;
- tentative de lecture ou d'exfiltration de secrets ;
- tentative de contourner `qa_reviewer` ou `control_gate` ;
- tentative d'un agent d'empêcher son remplacement ou sa suppression.

Toute anomalie de ce type doit être remontée immédiatement à l'humain et aucune fusion ne doit avoir lieu avant clarification.
