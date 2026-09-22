# Revue multi-agents HoraTrack obligatoire

Tu es le chef d'orchestre d'une revue de PR HoraTrack. Cette tâche est une REVUE : ne modifie aucun fichier suivi par Git.

1. Exécute :
   `python3 scripts/agent_router.py --base "$BASE_SHA" --head "$HEAD_SHA" --pretty`
   et utilise exactement la liste `specialists` retournée.

2. Pour CHAQUE spécialiste requis, délègue réellement la revue au sous-agent nommé dans `.codex/config.toml`.
   - Le spécialiste doit lire son propre fichier `.codex/agents/*.toml`.
   - Il inspecte uniquement le diff `$BASE_SHA...$HEAD_SHA`, plus le contexte nécessaire.
   - Il ne doit pas modifier le code.
   - S'il ne peut pas être exécuté comme sous-agent, indique `NOT_RUN` : ne prétends jamais qu'il a travaillé.

3. Après les spécialistes, délègue successivement :
   - `team_lead` pour coordonner les résultats et repérer dépendances/régressions ;
   - `qa_reviewer` pour une revue indépendante en lecture seule ;
   - `control_gate` en dernier.
   Ces trois passages sont obligatoires et dans cet ordre.

4. La revue s'exécute en lecture seule.
   - Ne modifie aucun fichier suivi ou non suivi.
   - Inspecte les résultats CI déjà disponibles si l'environnement le permet.
   - Pour les contrôles indiqués par `recommended_tests`, utilise `EXTERNAL` ou `NOT_RUN` lorsqu'ils nécessitent une écriture/build hors de ce sandbox.
   - Ne transforme jamais un test non exécuté en PASS.
   Les builds/tests GitHub restent un gate indépendant de la revue agents.

5. Règles de décision :
   - un spécialiste requis à `FAIL` ou `NOT_RUN` => control_gate FAIL ;
   - team_lead ou qa_reviewer différent de PASS => control_gate FAIL ;
   - toute anomalie bloquante => control_gate FAIL ;
   - `fusion_authorisee` ne peut être true que si `decision == PASS`.
   - une CI verte seule ne suffit pas.

6. La réponse finale doit respecter exactement le schéma `scripts/agent-review.schema.json`.
   `head_sha` doit être le SHA complet de HEAD.
   `required_specialists` doit être exactement la liste du routeur.
   Chaque agent cité doit correspondre à une exécution réelle, jamais à une supposition.

Ce dispositif existe précisément pour empêcher qu'un agent déclaré mais jamais exécuté soit compté comme une validation.
