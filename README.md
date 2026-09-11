# PointageTravail

Projet Android prêt à être compilé automatiquement par GitHub Actions.

## Compilation
1. Importer tout le contenu de ce dossier dans un dépôt GitHub.
2. Ouvrir l'onglet Actions.
3. Ouvrir "Compiler PointageTravail APK".
4. Appuyer sur "Run workflow".
5. Une fois terminé, télécharger l'artifact "PointageTravail-APK".
6. Décompresser le ZIP téléchargé : il contient PointageTravail.apk.

## Firebase App Check

- Les builds `debug` utilisent exclusivement le provider Firebase App Check **Debug**.
- Les builds `release` utilisent **Play Integrity**.
- Le canal d'installation détecté et l'état de l'attestation sont enregistrés localement dans `app_check_status` pour le diagnostic, sans stocker le jeton App Check.
- Un échec App Check ne doit jamais empêcher le pointage ou la lecture des données locales.
- **Ne pas activer l'enforcement App Check dans Firebase Console** tant que les métriques n'ont pas été validées séparément sur les trois canaux supportés : Google Play, GitHub/sideload et Firebase App Distribution.
- Si l'enforcement est activé ultérieurement, il doit être précédé d'un test Auth + Firestore sur chacun de ces canaux et d'un plan de retour arrière.

Build relancé après restauration de MainActivity.

Dernière relance APK : 2026-08-21 22:51 Europe/Paris.
