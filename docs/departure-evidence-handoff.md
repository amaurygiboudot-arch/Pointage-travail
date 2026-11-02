# Reprise : observations de départ Android/iOS

Base : `ea7103c6b5dd2086eb4cda29637a90bb93c0feea`, HEAD détaché.
Diff diagnostic préexistant conservé. Aucun commit, push, fusion ou déploiement.
Ce document décrit un travail local, pas une version livrée.

## Règle métier

Un départ GPS ne ferme pas seul une session et ne qualifie pas du temps payé.
L'observation doit rester liée au compte, à l'entreprise, à la session et son
arrivée, ainsi qu'à la configuration des zones. Les retours et ambiguïtés
empêchent sa réutilisation. La fenêtre de retour existante reste applicable.
Les anciennes demandes de confirmation ne sont pas promues en preuves nouvelles.

Une sortie volontaire MANUAL/WIDGET peut reprendre l'heure observée admissible ;
sa provenance doit rester distincte de celle du capteur. Une observation
inexploitable ne doit jamais fournir une heure inventée.

La politique automatique est désactivée par défaut et isolée par
compte/entreprise. Elle exige une fin attendue explicite liée à la session,
une observation de départ Travail vérifiée et l'absence de retour, zone active,
pause ou déplacement/ambiguïté non résolu. Aucune journée de huit heures n'est
supposée. L'API de stockage/politique constitue le périmètre minimal ; ne pas
annoncer une interface d'activation complète sans validation de celle-ci.

## Validation locale

- `python scripts/validate_codex_agents.py` : PASS, 21 rôles configurés.
- `python scripts/test_agent_orchestration.py` : PASS, 11 tests.
- Politique pure Android : compilation Kotlin et JUnit exécutés avec les
  dépendances déjà présentes en cache, PASS (12 tests : 5 de politique,
  7 de codec/contexte). Ce contrôle vérifie ces fonctions pures, pas le stockage
  Android ni les callbacks GPS.
  Reproduction locale : `python build/departure-pure-tests/run.py` (outil local
  ignoré par Git, dépendant du cache Kotlin/JUnit existant). La CI doit utiliser
  la tâche Gradle officielle, qui inclut le test source versionné.
- Android : tentative via Git Bash et wrapper existant, tâche
  `:app:testDebugUnitTest` ciblée. Échec avant compilation dans les services
  natifs Gradle (`native-platform.dll`, chemin introuvable) : tests NOT_RUN.
  `gradlew.bat` est absent de ce checkout.
  Commande tentée : `& 'C:/Program Files/Git/bin/bash.exe' ./gradlew :app:testDebugUnitTest --tests '*GpsWorkStateCoordinator*' --stacktrace`.
- iOS : tests et builds NOT_RUN sur Windows ; SwiftPM/Xcode/macOS nécessaires.
- Le défaut de reprise d'entrée n'a pas été reproduit par exécution : le chemin
  `resolveEntryNow` reste inchangé. Une injection d'échec de stockage est requise
  avant correction, en distinguant une session déjà ouverte d'un refus temporaire.

## Périmètre livré et limites

Android utilise le pending/outbox GPS existant avec un marqueur de nouvelle
observation, et sa fin attendue canonique de session. iOS utilise la queue GPS
existante désormais isolée par compte, et une fin attendue absolue attachée à
l'identifiant de session et à son arrivée. Les demandes anciennes sont
neutralisées ; les origines historiques absentes restent inconnues.

Les boutons Android (activité, grand widget, quick widget) passent une origine
explicite. iOS raccorde la sortie manuelle et accepte l'origine WIDGET via la
même API ; aucun nouveau widget iOS n'est annoncé. L'état hors site est présenté
sans dialogue de fin de journée.

Une époque de surveillance Android est renouvelée au changement de compte via
le listener d'authentification. Elle invalide l'ancienne preuve après A→B→A,
même sans opération GPS sous B. Les historiques des comptes restent intacts.
iOS neutralise les départs lors de la reprise d'une surveillance d'un autre compte.

La livraison Android v2 conserve le dernier EXIT observé et toutes les zones de
l'épisode. Le délai repart de ce dernier EXIT, tandis que l'heure de clôture
reste celle du départ Travail admissible. Une entreprise ou un site incompatible
bloque cette reprise. Les livraisons v1 incomplètes ne sont pas promues en preuves.
iOS retire de la file canonique les départs invalidés par une arrivée ultérieure,
pour ne pas masquer cette arrivée derrière un événement désormais passif.

La clôture automatique n'est pas activée dans les parcours produit : les API
exigent une résolution explicite des déplacements/ambiguïtés et leur valeur
par défaut est fausse. Aucun appel produit ne fournit artificiellement cette
preuve. Il reste à raccorder une source canonique de cette résolution et une
interface de configuration avant d'annoncer l'automatisme disponible.

Tests d'intégration ajoutés mais NOT_RUN : sortie manuelle/widget reprenant
l'observation, provenance conservée au rechargement, doublon, politique
compte/entreprise et migration d'anciennes demandes. Les tests purs Swift
couvrent notamment délai de retour, politique, fin absente, compte/session/
configuration, pause, retour, replay et employeur. Les essais réels de callbacks,
redémarrage, panne disque et changement de compte restent à exécuter.

## Revues exécutées

`time_engine` (Android) et `mobile_platforms` (iOS) ont réellement analysé et
implémenté sur des périmètres distincts. Les revues spécialisées supplémentaires
`legal_compliance`, `ui_ux`, `performance_battery` et `analytics_data` ont été
exécutées en lecture seule selon le routage du diff de travail.

Findings corrigés : provenance de l'heure GPS ajoutée au canonique Android ;
origine et observation ajoutées aux exports avec whitelist ; entreprise iOS
absente/vide refusée ; observation Android invalidée après changement de compte ;
bannière d'accueil distincte des erreurs GPS, avec date et heure.

Prévalidation finale `team_lead` : PASS statique limité après relecture des
corrections. `git diff --check` : PASS, y compris contrôle des nouveaux fichiers.
La première QA indépendante a rendu FAIL statique : une observation de départ
iOS masquait l'arrivée suivante dans la file ; Android ne vérifiait pas toutes
les entreprises d'un épisode multizone et attendait le délai depuis l'heure
Travail plutôt que depuis le dernier EXIT. Ces points ont été corrigés puis
relus par `team_lead` et QA. Le recontrôle QA a relevé un P2 de mise à jour :
les anciennes livraisons v1 inéligibles pouvaient saturer l'outbox active.
La correction archive les v1 strictement reconnues du compte courant dans la
même écriture que la nouvelle livraison, sans promotion en preuve ni clôture ;
les données étrangères/illisibles restent intactes. `team_lead` a relu ce
dernier delta avec PASS statique limité. La QA finale a rendu PASS statique
limité après recontrôle des quatre findings et des 42 empreintes de fichiers,
sans écart.
Aucun PASS global, build ou mesure de performance n'est acquis.

`control_gate` réellement exécuté ensuite : **FAIL — FUSION AUTORISÉE : NON**.
Aucun nouveau défaut de code bloquant identifié ; quatre blocages de validation :
Android (tests intégrés/build), iOS (Swift/build macOS), parcours de fiabilité
sur environnement compatible/appareil, CI et rapport officiel lié au SHA exact.
Le minimum autorisé (politique/stockage/API sans activation produit) est couvert
statiquement. Code préparé pour une future étape commit/CI, pas validé pour livraison.

Prochaine action : rétablir Gradle puis exécuter `v2-tests` et `android-build` ;
exécuter `ios-tests` et `ios-build` sur macOS ; valider retours, multizones,
A→B→A, redémarrage/replay et panne de stockage. Corriger les éventuels échecs,
puis obtenir la CI et la revue officielle du SHA après un commit/PR autorisé
dans une autre étape. Aucun commit n'est effectué par ce lot.

À mesurer sur appareil : coût des lectures/replays du bandeau et des commits de
l'époque de surveillance sur les chemins UI. Aucun nouveau timer GPS n'est
ajouté ; l'absence de finding statique ne constitue pas une mesure de batterie.

Les commandes officielles de reprise sont `v2-tests`, `android-build`,
`ios-tests` et `ios-build` via `scripts/agent-toolbox.sh`, dans les environnements
compatibles. La preuve de revue liée à un SHA de PR exige ultérieurement la
chaîne officielle ; `agent-review` n'est pas lancé ici car il publie un rapport.

L'inventaire local des 42 fichiers de code/tests modifiés et leurs empreintes
figure dans `build/reports/departure-evidence-final/manifest.json` (non versionné).
SHA-256 de ce manifeste :
`b3641223c0fa6683b7933bc5925956cade272a8736cef7267fc3f279c7b19d2f`.
Les trois documents concernés sont le présent bilan, `daily-pointage-diagnostic.md`
et le bilan historique `diagnostic-subset-20261015.md`. Ce manifeste ne vaut
pas rapport d'approbation lié à une PR.
