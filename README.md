# HoraTrack

Projet Android principal compilé automatiquement par GitHub Actions.

## Application officielle

Le seul module Android officiel et livrable est `:app`.

Le dossier `app-v3/` est un **ancien prototype expérimental gelé** :

- il ne fait pas partie du produit HoraTrack actuel ;
- il est volontairement exclu de `settings.gradle.kts` ;
- il n’est pas compilé ni publié par la CI officielle ;
- aucune nouvelle fonctionnalité ne doit y être ajoutée ;
- toute reprise future de ce prototype devra commencer par une PR dédiée qui l’intègre explicitement au build et ajoute sa propre validation CI.

Cette séparation évite de confondre du code expérimental avec l’application réellement distribuée et évite d’alourdir les builds de production.

## Compilation
1. Ouvrir l’onglet Actions.
2. Ouvrir le workflow de compilation HoraTrack.
3. Lancer le workflow si nécessaire.
4. Télécharger l’artifact APK généré une fois le workflow terminé.

Le module `:app` est la source de vérité pour Android.
