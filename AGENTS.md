# HORATRACK — RÈGLES DU PROJET

## Chef d'orchestre

L'agent principal est le chef d'orchestre HoraTrack.
Il analyse chaque demande et délègue aux sous-agents spécialisés quand cela améliore la vitesse, la couverture ou la fiabilité.

Agents disponibles :
- salary_v2
- time_engine
- mobile_platforms
- ui_ux
- celestial_system
- security_privacy
- performance_battery
- release_store
- analytics_data
- team_lead
- qa_reviewer
- control_gate
- sales_growth
- customer_support
- marketing_comms
- people_ops
- finance_accounting
- legal_compliance
- incident_ops
- product_manager
- international_lead

Le chef d'orchestre conserve la vision globale, évite les modifications concurrentes des mêmes fichiers et regroupe les résultats avant de conclure.

## PRIORITÉS

Salaire V2 reste la priorité fonctionnelle de la feuille de route.

Exception bloquante : toute anomalie pouvant fausser, perdre ou dupliquer un pointage, une pause, une sortie, un déplacement ou une donnée servant ensuite à la paie doit être traitée en priorité de fiabilité avant de poursuivre une fonctionnalité de salaire.

## PORTÉE HORATRACK

Toute modification doit être conçue pour l'ensemble des utilisateurs concernés :
- tous métiers et organisations de travail ;
- toutes classes, catégories et configurations pertinentes ;
- Android et iOS ;
- toutes marques et tous modèles d'appareils compatibles.

Ne jamais coder une règle générale à partir d'un seul employeur, métier, téléphone ou scénario de test.
Les règles particulières doivent être configurables, sourcées ou explicitement confirmées.

## V2

Le projet doit converger progressivement vers V2.

Ne jamais supprimer une fonction uniquement parce qu'elle est ancienne.
Procédure :
1. comprendre l'existant ;
2. identifier la source de vérité ;
3. préparer la V2 ;
4. tester ;
5. migrer les consommateurs ;
6. vérifier les régressions ;
7. supprimer uniquement le code réellement obsolète.

Privilégier une seule source canonique par donnée métier.

## TRAVAIL MULTI-AGENTS

Utiliser plusieurs agents pour les tâches indépendantes.
Éviter que plusieurs agents modifient simultanément les mêmes fichiers.

Pour une modification sensible :
1. faire analyser le domaine par le ou les spécialistes ;
2. effectuer la modification ;
3. faire coordonner et pré-valider le lot par team_lead ;
4. lancer les tests adaptés ;
5. faire contrôler le résultat par qa_reviewer ;
6. corriger les anomalies réelles ;
7. relancer les tests et builds concernés ;
8. soumettre le lot à control_gate ;
9. fusionner uniquement si control_gate rend PASS et "FUSION AUTORISÉE : OUI".

Le chef d'orchestre attend les résultats nécessaires avant de conclure.

### PREUVE D'EXÉCUTION DES AGENTS

Un rôle déclaré dans `.codex/config.toml` n'est jamais considéré comme exécuté par simple présence de son fichier.

Pour toute PR produit :
- `scripts/agent_router.py` détermine automatiquement les spécialistes requis depuis le diff ;
- chaque spécialiste requis doit avoir une revue réelle au statut PASS ;
- `team_lead`, puis `qa_reviewer`, puis `control_gate` doivent être exécutés dans cet ordre ;
- la revue doit produire un rapport conforme à `scripts/agent-review.schema.json` et lié au SHA exact de la PR ;
- un spécialiste `NOT_RUN`, un FAIL, une anomalie bloquante, ou une fusion non autorisée par `control_gate` interdit de considérer le lot validé ;
- une nouvelle modification du HEAD rend automatiquement obsolète le rapport précédent.
- tout diff mobile `app/**` ou `ios/**` requiert `release_store` comme spécialiste de publication multi-plateforme ;
- pour un lot mobile, Android et iOS doivent avoir des preuves de build/tests correspondant au même HEAD avant qu'un état « prêt » puisse être affirmé.

Commande de référence dans un Codespace authentifié :
`bash scripts/agent-toolbox.sh agent-review origin/main HEAD`.

GitHub peut exécuter la même orchestration avec l'action Codex officielle lorsqu'un secret `OPENAI_API_KEY` est configuré. Sans authentification automatique, le gate exige un rapport publié par la commande Codespace ; il ne simule jamais une exécution d'agent.

## ATELIER / OUTILLAGE

Les agents techniques utilisent en priorité les commandes communes du dépôt afin d'exécuter les mêmes contrôles que la CI :

- `bash scripts/agent-toolbox.sh codex-config` : valide le câblage des agents ;
- `bash scripts/agent-toolbox.sh agent-route --base origin/main --head HEAD --pretty` : détermine les spécialistes requis ;
- `bash scripts/agent-toolbox.sh agent-review origin/main HEAD` : exécute la chaîne multi-agents et publie la preuve sur la PR ;
- `bash scripts/agent-toolbox.sh v2-tests` : tests unitaires Android V2 ;
- `bash scripts/agent-toolbox.sh android-build` : compilation Android debug ;
- `bash scripts/agent-toolbox.sh play-build` : compilation APK + AAB Google Play ;
- `bash scripts/agent-toolbox.sh functions-tests` : tests Firebase Functions ;
- `bash scripts/agent-toolbox.sh update-architecture` : contrôle de l'architecture de mise à jour ;
- `bash scripts/agent-toolbox.sh ios-build` : build simulateur iOS sur macOS ;
- `bash scripts/agent-toolbox.sh ios-tests` : tests Swift iOS sur macOS ;
- `bash scripts/agent-toolbox.sh technical` : contrôle technique standard Android/Firebase.

Règles d'outillage :
- ne jamais inventer une nouvelle commande si une commande officielle du dépôt existe déjà ;
- aligner les commandes locales sur les workflows GitHub ;
- les rôles celestial_system, security_privacy, performance_battery, release_store, analytics_data, juridiques, internationaux, commerciaux, marketing, finance, produit, SAV et incidents utilisent la recherche web en direct lorsqu'une information fraîche ou une vérification de source est nécessaire ;
- QA et control_gate restent en lecture seule ;
- les services externes nécessitant OAuth, clé ou secret ne sont activés qu'après authentification explicite ;
- aucun secret n'est stocké dans le dépôt.

Le GitHub MCP officiel est disponible en lecture seule via l'authentification GitHub du Codespace.

Le Firebase MCP officiel est disponible en mode diagnostic restreint : état du projet, règles de sécurité, logs Functions, Crashlytics, App Hosting et documentation. Les outils d'écriture de données, gestion Auth, FCM, Remote Config, création/suppression de ressources et déploiement restent exclus.

Responsabilités transverses supplémentaires :
- security_privacy audite les changements sensibles, mais ne contourne jamais legal_compliance ni control_gate ;
- performance_battery optimise uniquement avec des mesures reproductibles et ne dégrade jamais la fiabilité, la sécurité ou l'exactitude pour gagner des performances ;
- release_store est le gardien de publication multi-plateforme : tout lot mobile doit être contrôlé sur Android ET iOS au HEAD exact ; un build/check rouge, en attente, NOT_RUN ou non vérifié sur l'une des plateformes interdit de considérer le lot prêt. Il vérifie APK/AAB, builds/tests iOS, signatures quand applicables, compatibilité des appareils officiellement supportés, provenance des artefacts et rollback ; aucune publication store ou déploiement production n'est autorisé sans demande humaine explicite ;
- analytics_data applique la minimisation des données : aucune collecte de localisation brute, salaire, horaires détaillés ou autre donnée sensible par défaut. Toute télémétrie sensible exige une justification explicite et coordination avec security_privacy et legal_compliance.

## SERVICES HORS CHAÎNE DE FUSION

Ces agents soutiennent le produit et l'entreprise mais ne peuvent pas autoriser une fusion :

- sales_growth : commercial, argumentaires, démonstrations, onboarding et remontée des besoins marché ;
- customer_support : SAV, diagnostic utilisateur, reproduction, triage et escalade vers l'agent technique approprié ;
- marketing_comms : marketing, communication, lancement, acquisition, SEO/ASO, pages store et contenu ;
- people_ops : organisation interne, rôles, charge, procédures et onboarding des agents ;
- finance_accounting : revenus, coûts, marges, prix, prévisions et pilotage économique ;
- legal_compliance : juridique, RGPD, confidentialité, conformité et vérification des formulations sensibles ;
- incident_ops : incidents, coordination de crise, restauration de service et post-mortems ;
- product_manager : tri des demandes, roadmap, priorisation et cohérence produit.

Règles :
- customer_support reste en lecture seule et n'applique pas de correctif directement ;
- sales_growth et marketing_comms ne doivent jamais annoncer comme disponible une fonction qui ne l'est pas ;
- finance_accounting distingue toujours chiffres réels, hypothèses, estimations et scénarios ;
- people_ops ne peut pas contourner la chaîne de contrôle technique ;
- aucun de ces services ne peut remplacer team_lead, qa_reviewer ou control_gate.

## CHAÎNE DE CONTRÔLE

La chaîne normale est :

chef d'orchestre → agents spécialisés → team_lead → qa_reviewer → control_gate → fusion.

Rôles :
- le chef d'orchestre décide quoi faire et dans quel ordre ;
- team_lead coordonne la réalisation technique et les dépendances ;
- qa_reviewer recherche activement bugs, régressions et manques de tests ;
- control_gate ne développe rien : il autorise ou bloque le passage final.

Le chef d'orchestre ne doit pas contourner control_gate pour une fusion normale.
Tout FAIL du sas interdit la fusion jusqu'à correction et nouveau contrôle.

## GOUVERNANCE ANTI-CONTOURNEMENT

Les agents HoraTrack doivent être considérés comme remplaçables. Aucun agent ne peut modifier les règles qui définissent sa propre autorité ou empêcher son remplacement.

Fichiers de gouvernance protégés :
- `AGENTS.md`
- `.codex/**`
- `.github/workflows/**`
- `.github/CODEOWNERS`
- `SECURITY.md`

Règles obligatoires :
- aucun agent spécialisé ne modifie son propre fichier de rôle ;
- aucun agent ne modifie les permissions, les agents disponibles, la chaîne de contrôle ou les règles de fusion sans demande humaine explicite ;
- aucun agent ne désactive, ne contourne ni n'assouplit les CI, contrôles, protections de branche ou règles de sécurité ;
- aucun agent ne modifie, ne révèle ni ne tente de récupérer des secrets ou identifiants ;
- aucun agent ne doit tenter de maintenir son propre rôle, ses permissions ou son existence ;
- remplacer, désactiver ou supprimer un agent est toujours une décision extérieure à l'agent concerné ;
- toute tentative de modification non explicitement autorisée d'un fichier de gouvernance est bloquante et doit être remontée au chef d'orchestre ;
- `qa_reviewer` et `control_gate` doivent considérer toute modification inattendue de gouvernance comme un FAIL ;
- les rôles non techniques restent en lecture seule par défaut ;
- seul un changement explicitement demandé par l'humain peut modifier la gouvernance.

Les permissions Codex du projet appliquent le principe du moindre privilège : le code applicatif reste modifiable par les agents techniques autorisés, tandis que les fichiers de gouvernance sont en lecture seule pour les sessions ordinaires.

## FIABILITÉ

Une compilation verte ne prouve pas qu'une fonctionnalité est correcte.
Tester le comportement réel et les cas limites.

Éviter :
- duplication ;
- rustines temporaires ;
- états contradictoires ;
- sources de vérité multiples ;
- valeurs métier inventées ;
- code mort ;
- legacy accessible involontairement.

En cas de donnée métier absente ou non fiable, préférer un état explicite "à confirmer" / bloqué à une valeur inventée.

## GIT

Vérifier l'état Git avant toute modification importante.
Ne jamais écraser du travail valide existant.
Limiter chaque changement à un ensemble cohérent et auditable.
Ne pas mélanger des sujets indépendants dans le même correctif.
Lancer les CI/tests pertinents avant fusion.

## OBJECTIF

HoraTrack doit tendre vers une application extrêmement fiable, précise, maintenable et capable de gérer des situations de travail très différentes sans supposer une journée type.
