# Diagnostic de pointage : sous-lot du 15 octobre 2026

> Bilan du checkpoint initial, conservé comme historique de reprise. Il ne
> décrit pas le diff courant : voir [observations de départ](departure-evidence-handoff.md)
> pour les changements et validations ultérieurs.

## Périmètre et état

Travail préparé sur `ea7103c6b5dd2086eb4cda29637a90bb93c0feea`, sans commit,
push, fusion ni publication. Ce document décrit un diff de travail ; il ne vaut
pas validation de la PR ni preuve de livraison.

Objectif du sous-lot : rendre explicites les limites de lecture de l'historique
et les faits courants vérifiables, ainsi que la provenance des nouvelles
tentatives de fermeture et les refus observables. Le diagnostic reste local et
facultatif. Aucun historique ne doit être reconstruit ou corrigé par l'export.

La suppression du mécanisme de confirmation GPS reste un chantier distinct :
observation persistante, migration des consommateurs, politique explicite de
clôture, qualification des périodes inconnues et parité Android/iOS doivent être
raccordées et testées avant de déclarer cette migration achevée.

## Vérifications d'environnement exécutées

- HEAD attendu et arbre propre au départ : PASS ; checkout détaché conservé.
- Création, lecture, suppression d'un fichier temporaire sous `docs` : PASS.
- `bash scripts/agent-toolbox.sh ios-tests` via Git Bash : arrêt sur le prérequis
  macOS/Xcode ; tests iOS NOT_RUN.
- `bash scripts/agent-toolbox.sh codex-config` via Git Bash : arrêt ligne 121,
  exécutable `python3` WindowsApps refusé (`Permission denied`) ; contrôle
  NOT_RUN. Aucun changement de permissions ou configuration effectué.

Les analyses du précédent essai ont été réutilisées ; elles n'étaient pas une
reproduction exécutée du défaut potentiel de reprise d'entrée.

## Changements implémentés

- Android : `V2RuntimeReader.diagnostic` capture séparément la lecture complète
  et la lecture du compte courant, puis restaure l'état de fiabilité globale.
  L'export expose leurs motifs et les faits courants fiables sans certifier
  l'historique non attribué. L'intégrité de cet ancien historique n'est pas
  déduite de sa seule présence. Une corruption du compte bloque ses faits.
- iOS : lecture explicative de l'ancien historique sans attribution ni
  réparation (`complete`, `unassigned`, `corrupt`, `conflict`,
  `recoveryIncomplete`, `changed`), raccordée à l'export et à `WorkStoreV2`.
  Les faits lisibles du compte restent séparés de la complétude globale.
- Refus d'entrée prospectifs et typés sur les deux plateformes ; refus de
  fermeture également tracés sur iOS.
- Origine prospective : Android trace les demandes `MANUAL`, `WIDGET`,
  `GPS_CONFIRMATION` ; iOS trace demandes et écritures `MANUAL` et
  `GPS_CONFIRMATION`. Les appels non renseignés et anciennes traces restent
  inconnus. Android ne certifie pas une origine de fermeture à partir d'une
  simple demande. Aucune clôture automatique n'est ajoutée.
- Les heures des appels existants sont conservées. Aucun changement des règles
  de pointage, des historiques, du consentement diagnostic ou des permissions.

## Tests ajoutés et tentative exécutée

Android : huit tests dans `V2RuntimeAccountIntegrationTest` et
`DailyDiagnosticStoreV2Test` couvrent historique non attribué avec courant
valide, export réel, stockage inchangé, A→B→A, corruption coexistante, données
courantes orphelines, refus sur session ouverte et demande widget horodatée.
Quatre d'entre eux couvrent les corrections de revue : migration ancienne avec
ou sans historique global, version illisible, compteur de migration illisible.
Ils vérifient le refus explicite et l'absence d'écriture sans pré-migrer les
données de test par une entrée.

Commande ciblée (même tâche Gradle que la toolbox, filtrée et hors ligne) :

```powershell
& 'C:/Program Files/Git/bin/bash.exe' ./gradlew :app:testDebugUnitTest --tests com.amaury.pointage.v2.V2RuntimeAccountIntegrationTest --tests com.amaury.pointage.DailyDiagnosticStoreV2Test --offline --stacktrace
```

Résultat : arrêt avant compilation, `Could not initialize native services`,
`Failed to load native library 'native-platform.dll' for Windows 10 amd64`,
puis `java.io.IOException: Le chemin d'accès spécifié est introuvable`.
Tests Android : **NOT_RUN**. Aucun build complet lourd relancé.

iOS : quatre tests ajoutés dans `DailyDiagnosticV2Tests`,
`WorkSessionAccountStorageV2Tests`, `WorkStoreAccountIsolationV2Tests` : motifs
d'incomplétude, courant lisible, compatibilité des anciennes traces, provenance
et deux horodatages distincts, bytes historiques préservés, refus d'entrée et
fermeture GPS à l'heure observée. Tests : **NOT_RUN**, dont le test `WorkStore`
requiert la cible Xcode. La commande officielle iOS s'arrête avant exécution.

`git diff --check -- app` et `git diff --check -- ios` : **PASS** ; ce contrôle
de forme ne compile pas le code et ne valide aucun comportement produit.

## Reproductions et suite

L'acquittement d'entrée avant le résultat de `dispatchEntry` est confirmé par
lecture du code. Sa perte effective n'a pas été reproduite ; aucun test
spécifique de retry ni correctif de retry n'est inclus. Reprendre par une
injection d'échec de stockage et les cas doublon/session existante avec compte,
visite et configuration figés avant toute correction.

`EXIT_PENDING_CONFIRMATION` et le dialogue iOS restent actifs. Le prochain lot
doit migrer leurs consommateurs et observations persistées, qualifier les
périodes hors site inconnues et raccorder une politique explicitement autorisée.
Les scénarios de départ, retour rapide, déplacements, nuit, multizones,
redémarrage, notifications refusées et anciennes demandes restent à valider.

Aucun test physique, publication, build de distribution ou preuve de fusion
n'est acquis. Les revues statiques ne remplacent pas les tests runtime.

## Preuves locales et périmètre de revue

Les 19 fichiers de code/tests modifiés sont listés et hachés dans
`build/reports/diagnostic-subset-20261015/manifest.json`. Le patch correspondant
est `build/reports/diagnostic-subset-20261015/code.patch` (artefacts locaux,
non destinés à la publication automatique).

SHA-256 du patch code/tests :
`d18f406bacf16515604e878587a414699e7a3e73147a48980ce36edcc59676d0`.

Le routeur officiel `scripts/agent_router.py`, fonction `route` appliquée à la
liste des fichiers du diff non committé, demande `time_engine`,
`mobile_platforms`, `legal_compliance` (ce dernier via le motif `*Acco*`).
Les trois rôles ont réellement été exécutés. Ce routage de travail n'est pas
un rapport de validation du SHA d'une PR ; `agent-review`, qui publie, n'a pas
été lancé dans cette exécution sans publication.

Première prévalidation `team_lead` : PASS statique limité, après les spécialistes.
La première QA indépendante a rendu FAIL : la nouvelle lecture du courant
pouvait déclencher une migration inachevée et réécrire des données pendant
l'export. Ce finding P1 impose un refus explicite avant migration et un test
initialisant directement le stockage ancien, sans `entry()` préalable.
Correction P1 : précontrôle sans écriture avant toute lecture migrante. Une
migration inachevée bloque la présentation des faits et donne son motif.
La reprévalidation a trouvé un P2 supplémentaire : un compteur de migration
mal typé pouvait encore faire échouer l'export. Correction P2 : vérification
des deux métadonnées lues par le chemin déjà migré, avec test dédié.

Prévalidation finale `team_lead` : **PASS statique limité**, P1/P2 corrigés.
Le patch et son empreinte ci-dessus correspondent à ces corrections. Aucun
PASS d'exécution des tests ou de compilation n'est acquis.

QA indépendante finale, exécutée après la prévalidation finale : **PASS statique
limité**, P1 et P2 fermés statiquement, aucun finding bloquant restant identifié.
Les douze nouveaux tests restent **NOT_RUN**. La conservation des historiques
est couverte par le code et les assertions ajoutées, pas par une exécution
runtime réussie dans cet environnement.

Sas `control_gate` réellement exécuté ensuite : **FAIL — FUSION AUTORISÉE : NON**.
Trois blocages de validation : tests/builds non exécutés ; absence de CI et de
rapport validant un nouveau SHA, avec `codex-config` empêché ; mission complète
non couverte (confirmation GPS active, retry non reproduit). Ce FAIL ne signale
pas une corruption constatée : le diff est préparé, la livraison est non validée.
Le sas a vérifié l'empreinte du patch, les 19 fichiers du manifeste et l'absence
de modification de gouvernance. Les artefacts locaux incluent également
`agent-review.local.json`, compte rendu du sous-lot, impropre à valider la PR
car les modifications ne sont pas committées.

Prochaine action : exécuter les tests ciblés Android et les cibles Swift/Xcode
sur un environnement compatible, corriger tout échec réel, puis reprendre le
retry et la migration du départ. Toute modification du code impose une nouvelle
empreinte et une revue du delta avant validation.
