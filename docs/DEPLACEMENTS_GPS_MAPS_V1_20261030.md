# AGKGMG / HoraTrack — Déplacements GPS + Maps (premier lot)

État : **candidat GitHub isolé, non testé sur appareil, non fusionné et non publié**.
Branche : `feature/deplacements-gps-maps-v1-20261030`, issue du candidat de la PR #689 (SHA `670be78942d82d4a2893b85e2de47d9c2977326f`).
Demande : rubrique Déplacements reliée au GPS et à une application cartographique, sans complexifier les deux actions ENTRÉE / SORTIE du pointage.

## Livré dans le code de la branche (pas encore chez les utilisateurs)

- Android : menu Paramètres → « Déplacements », écran indépendant, début/fin horodatés, journal isolé par compte, demande facultative de permission GPS, relevé ponctuel au début et à la fin, lien universel Google Maps avec confirmation explicite.
- iOS : menu Réglages → Pointage & lieux → « Déplacements », écran autonome, journal par compte via UserDefaults, relevé ponctuel Core Location, consultation facultative dans Google Maps.
- Un GPS inaccessible, une localisation imprécise (>150 m), trop ancienne (>180 s) ou manquante ne bloque pas l'enregistrement du départ/de l'arrivée. L'heure reste factuelle, la position reste **inconnue**, jamais `0,0` inventé.
- Les positions restent dans l'espace local du compte, séparées du journal de pointage et des calculateurs de paie. Ouvrir Maps est un choix explicite du conducteur : les coordonnées connues peuvent alors être transmises à Maps.
- Lien Google Maps universel sans clé API : https://developers.google.com/maps/documentation/urls/get-started
- Aucun calcul de kilométrage effectué à partir de l'itinéraire proposé : route théorique ≠ distance réellement parcourue.

## Compatibilité et limites

- La détection GPS automatique ENTRÉE / SORTIE du pointage **reste active** et n'a pas été modifiée par ce lot.
- Un départ de site en mission ne doit jamais être considéré comme une sortie du travail à cause de cette rubrique.
- Aucune session de travail, pause, indemnité ou règle Salaire V2 n'est écrite par ce module. L'association entreprise est faite uniquement si elle est non ambiguë ou attestée par une session existante.
- **Non livré dans cette V0.1** : collecte GPS continue, géométrie intégrale d'itinéraire, kilomètres parcourus certifiés, autonomie garantie en arrière-plan, import des trajets depuis Google Maps, export, synchronisation cloud, calcul des frais et liaison juridique automatique.
- L'interface ne suppose aucun métier, marque de téléphone, utilisateur ou véhicule particulier ; GPS facultatif et refus de permission supporté.
- Journal JSON versionné, compte identifié, conservation des anciens faits ; absence de données ≠ données corrompues, et les écritures doivent être relues. La migration/rotation à grande échelle et la politique de rétention restent à concevoir avant livraison commerciale.

## Contrôles à passer avant intégration

1. Kotlin/JUnit pour le codec et l'isolation; Android Gradle build, manifeste, permissions et ouverture Maps.
2. SwiftPM/Xcode pour le codec iOS et l'écran; vérification du fichier `project.yml` XcodeGen.
3. Cas d'appareil : permission accordée/refusée, GPS inaccessible, offline, changement de compte en cours de trajet, crash/reprise, voyage nocturne, plusieurs entreprises, Maps absent, plusieurs lieux.
4. Revue security_privacy et legal_compliance sur localisation, données personnelles, transmission explicite à Maps et droits de suppression/export.
5. Vérifier à l'identique la paie et le GPS automatique existants, revue `team_lead` → `qa_reviewer` → `control_gate` liée au SHA avant fusion.
6. Fusion et Google Play/TestFlight uniquement après PASS authentifiés. **Aucune publication automatique autorisée.**

## Point de reprise opérationnel

La branche locale EDUCTOR `refonte/pointage-zero-20261030` contient les précédents commits de refonte factuelle mais ne doit pas être écrasée. Un prototype Kotlin inachevé non commité a été signalé sur EDUCTOR le 30/10. Synchroniser via une nouvelle copie de travail ou une réconciliation explicite, jamais par reset destructif. Les documents maîtres Drive 01/02/03/08/09/10/11 restent les références décisionnelles et de preuves.
