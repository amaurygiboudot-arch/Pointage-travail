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

Le serveur MCP GitHub officiel est préparé dans `.codex/config.toml` avec le point de terminaison **lecture seule**. Il reste désactivé par défaut tant que l'authentification OAuth n'a pas été explicitement réalisée.

Aucun jeton GitHub n'est stocké dans le dépôt.

Après authentification, le GitHub MCP pourra notamment aider les agents de contrôle à consulter les PR, issues et états CI sans leur donner de droits d'écriture.

## Services externes futurs

À connecter seulement lorsqu'ils apportent une valeur réelle et avec permissions minimales :

- Firebase / Google Cloud : logs, fonctions, erreurs et métriques ;
- crash reporting : incidents applicatifs ;
- analytics produit : adoption et parcours ;
- support client : tickets SAV ;
- paiement / stores : données économiques pour finance_accounting ;
- sources juridiques officielles par pays pour les équipes internationales.

Un connecteur externe ne doit jamais recevoir plus de droits que nécessaire.
