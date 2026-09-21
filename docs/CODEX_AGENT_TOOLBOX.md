# HoraTrack — Atelier / outillage des agents Codex

## Objectif

Donner à chaque agent uniquement les outils nécessaires à son métier, avec le minimum de privilèges.

## Outils communs

Les commandes locales sont centralisées dans `scripts/agent-toolbox.sh`.

| Commande | Usage |
| --- | --- |
| `bash scripts/agent-toolbox.sh codex-config` | Valider le câblage des agents |
| `bash scripts/agent-toolbox.sh v2-tests` | Tests unitaires Android V2 |
| `bash scripts/agent-toolbox.sh android-build` | Build Android debug |
| `bash scripts/agent-toolbox.sh play-build` | APK + AAB Google Play |
| `bash scripts/agent-toolbox.sh functions-tests` | Tests Firebase Functions |
| `bash scripts/agent-toolbox.sh update-architecture` | Vérifier l'architecture de mise à jour |
| `bash scripts/agent-toolbox.sh ios-build` | Build iOS simulateur sur macOS |
| `bash scripts/agent-toolbox.sh ios-tests` | Tests Swift sur macOS |
| `bash scripts/agent-toolbox.sh technical` | Contrôle technique standard |

## Répartition des outils

| Agent | Écriture code | Recherche web live | Outils principaux |
| --- | --- | --- | --- |
| salary_v2 | oui | oui | tests V2, code paie, sources officielles |
| time_engine | oui | non par défaut | tests V2, code pointage/temps |
| mobile_platforms | oui | oui | Android/iOS, Firebase, builds, CI |
| ui_ux | oui | non par défaut | layouts, navigation, rendu |
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

## Services externes futurs

À connecter seulement lorsqu'ils apportent une valeur réelle et avec permissions minimales :

- Firebase / Google Cloud : logs, fonctions, erreurs et métriques ;
- crash reporting : incidents applicatifs ;
- analytics produit : adoption et parcours ;
- support client : tickets SAV ;
- paiement / stores : données économiques pour finance_accounting ;
- sources juridiques officielles par pays pour les équipes internationales.

Un connecteur externe ne doit jamais recevoir plus de droits que nécessaire.


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
- persiste `JAVA_HOME` et le `PATH` dans `~/.bashrc` pour la phase agent ;
- vérifie `./gradlew --version` ;
- valide le câblage des agents avec `scripts/validate_codex_agents.py`.

Le script est conçu pour être utilisé comme **setup script** de l'environnement Codex Cloud. Le setup s'exécute avant la phase agent.

### iOS / macOS

Le conteneur Codex Cloud Linux ne remplace pas le runner macOS. Les validations iOS restent exécutées par le workflow GitHub Actions `Build iOS`, sur un runner macOS avec Xcode.

Une PR qui modifie `ios/**` ne doit pas être considérée prête tant que ce workflow n'est pas vert, même si les contrôles Android obligatoires sont déjà passés.
