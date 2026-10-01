# Synchronisation des pointages iOS

La version iOS de HoraTrack utilise Firebase pour l'identité / le profil utilisateur, mais **ne synchronise pas actuellement l'historique de pointage avec Android**.

## État actuel

- les sessions de travail et les pauses iOS sont stockées localement sur l'iPhone ;
- les sessions Android restent dans le stockage local Android et dans les exports configurés côté Android ;
- se connecter avec le même compte Firebase sur Android et iOS **ne fusionne pas** et **ne recopie pas** les pointages ;
- aucune promesse de synchronisation multiplateforme des heures ne doit être affichée tant qu'un protocole de synchronisation dédié n'existe pas.

## Pour une future synchronisation

Une future implémentation devra faire l'objet d'une conception séparée avec au minimum : schéma Firestore versionné, règles d'accès par utilisateur, stratégie de résolution des conflits, identifiants stables de session/pause, migrations, fonctionnement hors ligne, tests Android/iOS équivalents et protection des données de pointage.

Jusqu'à cette implémentation, **le stockage local de chaque plateforme est la source de vérité de ses propres pointages**.
