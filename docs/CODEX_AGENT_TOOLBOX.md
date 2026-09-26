# HoraTrack — Atelier / outillage des agents Codex

## Objectif

Donner à chaque agent uniquement les outils nécessaires à son métier, avec le minimum de privilèges.

## Outils communs

Les commandes locales sont centralisées dans `scripts/agent-toolbox.sh`.

| Commande | Usage |
| --- | --- |
| `bash scripts/agent-toolbox.sh codex-config` | Valider le câblage des agents |
| `bash scripts/agent-toolbox.sh agent-route --base origin/main --head HEAD --pretty` | Router le diff vers les spécialistes requis |
| `bash scripts/agent-toolbox.sh agent-review origin/main HEAD` | Exécuter spécialiste(s) → team_lead → QA → control_gate et publier le rapport PR |
| `bash scripts/agent-toolbox.sh v2-tests` | Tests unitaires Android V2 |
| `bash scripts/agent-toolbox.sh android-build` | Build Android debug |
| `bash scripts/agent-toolbox.sh play-build` | APK + AAB Google Play |
| `bash scripts/agent-toolbox.sh functions-tests` | Tests Firebase Functions |
| `bash scripts/agent-toolbox.sh update-architecture` | Vérifier l'architecture de mise à jour |
| `bash scripts/agent-toolbox.sh ios-build` | Build iOS simulateur sur macOS |
| `bash scripts/agent-toolbox.sh ios-tests` | Tests Swift sur macOS |
| `bash scripts/agent-toolbox.sh technical` | Contrôle technique standard |

## Orchestration vérifiable

Le routeur `scripts/agent_router.py` inspecte les fichiers modifiés et sélectionne les spécialistes pertinents. Une PR Céleste qui touche par exemple le globe, une View et du lifecycle Android peut donc requérir simultanément `celestial_system`, `ui_ux`, `mobile_platforms` et `performance_battery`.

La commande `agent-review` lance une session Codex non interactive, exige les sous-agents nommés, puis impose `team_lead → qa_reviewer → control_gate`. Le résultat est structuré par `scripts/agent-review.schema.json`, contrôlé par `scripts/validate_agent_review.py` et publié sur la PR avec le SHA du HEAD.

Un rapport d'un ancien commit n'est jamais réutilisé après une nouvelle modification.

En CI, le job `Agent review gate` :
- réutilise un rapport PR valide s'il existe déjà pour le HEAD courant ;
- sinon lance l'action officielle `openai/codex-action` si `OPENAI_API_KEY` est disponible ;
- sinon échoue explicitement et demande l'exécution de `agent-review` dans le Codespace authentifié.

Cette stratégie est volontairement fail-closed : un agent non exécuté reste `NOT_RUN`, jamais PASS.

## Répartition des outils

| Agent | Écriture code | Recherche web live | Outils principaux |
| --- | --- | --- | --- |
| salary_v2 | oui | oui | tests V2, code paie, sources officielles |
| time_engine | oui | non par défaut | tests V2, code pointage/temps |
| mobile_platforms | oui | oui | Android/iOS, Firebase, builds, CI |
| ui_ux | oui | non par défaut | layouts, navigation, rendu |
| celestial_system | oui | oui | astronomie, capteurs d’orientation, globe GPS, tests V2, builds Android/iOS, sources scientifiques |
| security_privacy | oui | oui | auth, stockage sensible, règles Firebase en lecture, dépendances, réseau, permissions, CodeQL/CI |
| performance_battery | oui | oui | profilage, batterie, mémoire, réseau, GPS/capteurs, background, builds/tests |
| release_store | oui | oui | gardien publication Android/iOS : APK/AAB, builds/tests iOS, signatures, compatibilité appareils, checks exact-HEAD, rollout/rollback sans publication autonome |
| analytics_data | oui | oui | schémas d’événements, métriques, Crashlytics en lecture, qualité/minimisation des données |
| team_lead | oui | non par défaut | diff, tests, builds, coordination |
| qa_reviewer | non | non par défaut | lecture code, tests, rapports |
| control_gate | non | non par défaut | PR, résultats CI, rapports QA |
| sales_growth | non | oui | marché, offres, documentation produit |
| customer_support | non | oui | documentation, incidents connus, diagnostic |
| marketing_comms | non | oui | stores, SEO/ASO, communication |
| people_ops | non | non par défaut | organisation des rôles et procédures |
| finance_accounting | non | oui | coûts, prix, scénarios économiques |
| legal_compliance | non | oui | sources juridiques officielles datées |
| incident_ops | non | oui | état services, documentation, triage |
| product_manager | non | oui | marché, roadmap, demandes utilisateurs |
| international_lead | non | oui | sources officielles des pays ciblés |

## GitHub MCP

Le serveur MCP GitHub officiel utilise le point de terminaison **lecture seule**.

Dans un Codespace, Codex récupère l'identité GitHub déjà active via `gh auth token` à travers `scripts/github-mcp-headers.sh`. Le jeton n'est jamais enregistré dans le dépôt ni dans `.codex/config.toml`.

Ce mode évite de dépendre du flux `codex mcp login github` lorsque le client indique `Auth Unsupported`.

Le GitHub MCP permet aux agents de contrôle de consulter PR, issues et états CI sans leur donner de droits d'écriture.

`celestial_system` l'utilise aussi pour relire l'historique du chantier céleste (notamment la PR #155 et ses audits) avant de modifier ou de reprendre une correction, afin de ne pas rejouer un travail déjà acquis.

`security_privacy`, `performance_battery`, `release_store` et `analytics_data` utilisent GitHub MCP en lecture seule pour inspecter PR, historiques, CI et incidents techniques. Les mutations GitHub restent pilotées par le chef d'orchestre.

## Services externes futurs

À connecter seulement lorsqu'ils apportent une valeur réelle et avec permissions minimales :

- Firebase / Google Cloud : logs, fonctions, erreurs et métriques ;
- crash reporting : incidents applicatifs ;
- analytics produit : adoption et parcours ;
- support client : tickets SAV ;
- paiement / stores : données économiques pour finance_accounting ;
- sources juridiques officielles par pays pour les équipes internationales.

Un connecteur externe ne doit jamais recevoir plus de droits que nécessaire.

Les quatre nouveaux agents ne reçoivent aucun connecteur externe en écriture par défaut :
- security_privacy peut consulter GitHub/Firebase en diagnostic et les sources officielles de sécurité ;
- performance_battery travaille d'abord avec code, tests, mesures et documentation plateforme ;
- release_store prépare et bloque la readiness multi-plateforme : aucun lot mobile n'est déclaré prêt si Android ou iOS est rouge, pending, NOT_RUN ou non vérifié au HEAD exact ; il contrôle artefacts, signatures quand applicables, compatibilité et rollback, mais ne publie ni sur Google Play ni sur l'App Store sans autorisation humaine explicite ;
- analytics_data peut concevoir et auditer l'instrumentation, mais aucun accès à un fournisseur analytics réel n'est ajouté tant qu'il n'est pas nécessaire et explicitement autorisé.

Pour `celestial_system`, la recherche web live sert à vérifier les hypothèses scientifiques et les références astronomiques/géodésiques avec des sources reconnues et datées. Elle ne remplace jamais les tests numériques du moteur ni la validation réelle des capteurs sur appareil.


## Firebase MCP

HoraTrack utilise le serveur MCP officiel Firebase via :

`npx -y firebase-tools@latest mcp --dir . --only functions,crashlytics,apphosting,developerknowledge`

Le serveur réutilise les identifiants de la Firebase CLI présents dans le Codespace. La première connexion peut nécessiter :

`firebase login --no-localhost`

Le serveur est volontairement limité aux outils de lecture/diagnostic utiles :
- état du projet et configuration SDK ;
- lecture des règles de sécurité ;
- logs Cloud Functions ;
- liste des Functions ;
- Crashlytics en lecture ;
- logs App Hosting ;
- documentation officielle Google/Firebase.

Ne sont pas exposés :
- écriture Firestore ou Realtime Database ;
- gestion des comptes Firebase Auth ;
- envoi FCM ;
- modification Remote Config ;
- création ou suppression de ressources ;
- déploiement.

Toute extension future de ces permissions doit être explicitement validée avant fusion.


## Codex Cloud — JDK 17

Les tâches Codex Cloud doivent utiliser le script de configuration du dépôt :

`bash scripts/codex-cloud-setup.sh`

Ce script :
- vérifie la présence réelle de JDK 17 ;
- installe OpenJDK 17 sur les images Linux prises en charge si nécessaire ;
- persiste `JAVA_HOME` et le `PATH` dans `~/.bashrc` avant son éventuel
  garde-fou non interactif, ainsi que dans `~/.profile`, pour les shells de la
  phase agent qui chargent l'un de ces fichiers ;
- vérifie `./gradlew --version` ;
- valide le câblage des agents avec `scripts/validate_codex_agents.py`.

Le script est conçu pour être utilisé comme **setup script** de l'environnement Codex Cloud. Le setup s'exécute avant la phase agent.

### iOS / macOS

Le conteneur Codex Cloud Linux ne remplace pas le runner macOS. Les validations iOS restent exécutées par le workflow GitHub Actions `Build iOS`, sur un runner macOS avec Xcode.

Une PR qui modifie `ios/**` ne doit pas être considérée prête tant que ce workflow n'est pas vert, même si les contrôles Android obligatoires sont déjà passés.


## Gardien de publication multi-plateforme

`release_store` est requis pour tout diff mobile sous `app/**` ou `ios/**`.

Pour un lot mobile, son avis de readiness doit vérifier le même SHA sur les deux plateformes :
- Android : tests V2, build Android, APK + AAB Google Play, manifeste, version et signature lorsque l'artefact de release existe ;
- iOS : build Xcode, tests Swift, deployment target/architectures/entitlements et signature de distribution lorsqu'un artefact publiable est attendu ;
- GitHub : ruleset/checks obligatoires du dépôt et workflows plateforme pertinents ;
- compatibilité : classes d'appareils officiellement supportées et fallbacks lorsqu'un capteur/API n'existe pas ;
- artefacts : provenance, version/hash lorsque disponibles et rollback.

Un état `FAIL`, `PENDING`, `NOT_RUN` ou une preuve issue d'un autre HEAD interdit de conclure « prêt ». Un build simulateur iOS ne vaut pas preuve de signature App Store. Aucun secret de signature n'est lu ni affiché.
