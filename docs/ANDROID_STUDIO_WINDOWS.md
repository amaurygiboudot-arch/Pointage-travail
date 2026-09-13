# Ouvrir HoraTrack dans Android Studio sous Windows

## Prérequis

- Android Studio avec Android SDK, Platform SDK 36 et Android Emulator.
- JDK 17 sélectionné dans `Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK`.
- Windows Hypervisor Platform activé pour accélérer l'émulateur.

## Première ouverture

1. Dans Android Studio, choisir `Get from VCS`.
2. Utiliser `https://github.com/amaurygiboudot-arch/Pointage-travail.git`.
3. Ouvrir la racine du dépôt, jamais le dossier `app` seul.
4. Attendre la fin de `Gradle Sync` et accepter l'installation de Platform SDK 36 si elle est proposée.
5. Sélectionner la configuration Android du module `app`. Le module `app-v3` n'est pas la cible de lancement de HoraTrack V2.

Le wrapper du dépôt utilise Gradle 8.13, comme la CI HoraTrack. Sous Windows, un diagnostic peut être lancé depuis le terminal du projet avec :

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

## Émulateur conseillé pour ce PC

Créer un appareil dans `Tools > Device Manager` :

- profil : Pixel 4a ou téléphone moyen équivalent ;
- image : Android 16 / API 36, Google APIs, x86_64 ;
- processeurs : 2 ;
- RAM : 1536 Mo ;
- VM heap : 256 Mo ;
- stockage interne : 4 Go ;
- graphismes : Hardware ;
- cadre de l'appareil désactivé.

Avec 6 Go de RAM sur le PC, fermer le navigateur et les applications lourdes pendant l'émulation. Tester en priorité le build `debug`; les builds signés restent produits par GitHub Actions.
