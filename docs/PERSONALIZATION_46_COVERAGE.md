# Personnalisation globale — couverture du lot du 7 octobre 2026

Ce lot contient du code branché sur les applications Android et iOS existantes. Il ne réalise pas les 46 exigences intégralement. Aucun point ci-dessous ne vaut preuve de livraison ou certification de compatibilité. Les comportements listés « partiels » nécessitent encore compilation complète et essais sur appareils.

## Périmètre réel

Android : entrée Paramètres > Personnalisation > Confort visuel et écriture ; profil local isolé par compte, zoom du texte, contraste renforcé, réduction des effets du ciel, contexte nuit/économie, export et import validé, réinitialisation visuelle réversible, lecteur de texte agrandi. L’aide à l’écriture est accessible dans les actions de sélection des champs compatibles et explicitement dans la boîte à idées.

iOS : réglages de confort, thème et accents, taille dynamique, surfaces opaques, contraste, mouvements réduits et contexte, import/export et annulation. Les champs natifs existants consomment un composant commun protégeant les champs techniques des corrections de prose.

Les fichiers exportés Android et iOS ont des schémas différents. Ils ne sont pas présentés comme interopérables. Il n’y a pas de synchronisation cloud nouvelle ni de service IA ajouté.

## Suivi des exigences

| Point | État de ce lot | Reste nécessaire |
| --- | --- | --- |
| 1 Profil universel | Partiel : profil de confort local par compte | Profil global, identité et toutes les préférences métier |
| 2 Apparence | Partiel : confort, taille, contraste, contexte | Luminosité, densité et harmonisation de tous les réglages historiques |
| 3 Accessibilité | Partiel : tailles système, contraste, action lecteur Android, réduction des effets | Revue complète TalkBack/VoiceOver et contrôles sur appareils |
| 4 Langue et région | Non ajouté | Traductions, formats et préférences régionales cohérentes |
| 5 Personnalité IA | Non ajouté | Moteur conversationnel et préférences consommées |
| 6 Relation IA | Non ajouté | Identité de communication et consommation par les réponses |
| 7 Explications | Non ajouté | Niveaux consommés par les moteurs d’explication |
| 8 Autonomie | Non ajouté | Politique d’actions et validations par catégorie |
| 9 Mémoire | Non ajouté hors confort/dictionnaire | Inspection, correction et oubli des données mémorisées |
| 10 Apprentissage | Non ajouté | Propositions d’habitudes réversibles, sans inférence cachée |
| 11 Notifications | Non ajouté | Catégories, horaires silencieux, appareils et déduplication |
| 12 Confidentialité | Partiel : isolation, exclusions, brouillons chiffrés Android | Tableau global des permissions et contrôle des traitements |
| 13 Multi appareils | Non ajouté | Synchronisation versionnée, conflits et suppressions |
| 14 Modules | Partiel : services communs des applications existantes | Contrat d’enregistrement pour futurs modules et Genesis |
| 15 Contextes | Partiel : nuit/économie manuels | Règles configurables, priorités et activation horaire |
| 16 Sauvegarde | Partiel : confort exportable/importable, reset visuel | Restauration globale transactionnelle et schéma interplateforme |
| 17 Écriture commune | Partiel : champs Android éligibles et wrapper SwiftUI | Dialogues Android, champs personnalisés et inventaire exhaustif |
| 18 Orthographe/grammaire | Très limité : petite liste locale française Android, clavier natif iOS | Véritable correcteur grammatical multilingue ; aucune IA simulée |
| 19 Frappe | Très limité : erreurs connues proposées | Détection contextuelle complète des fautes de frappe |
| 20 Suggestions | Partiel Android : acceptation explicite et révision du texte | Parité iOS, essais de concurrence/IME sur appareils |
| 21 Dictionnaire | Partiel Android : privé, chiffré, par compte/langue | Parité iOS et échanges contrôlés |
| 22 Complétion | Limitée au dictionnaire personnel Android | Prédiction contextuelle, parité et mesures de latence |
| 23 Reformulation | Non ajouté | Moteur de reformulation avec comparaison et vérification du sens |
| 24 Dictée | Claviers système conservés | Parcours intégré de reconnaissance, interruptions et validation |
| 25 Fiabilité saisie | Partiel : garde de composition, limites Unicode, exclusions techniques | Matrice réelle claviers, gestes, orientations et longs textes |
| 26 Brouillons | Partiel Android : boîte à idées, conservation chiffrée 7 jours | Identité explicite de chaque document, extension aux autres écrans/iOS |
| 27 Annuler/rétablir | Partiel Android : historique borné du texte | Parité et intégration complète des commandes natives |
| 28 Performance | Partiel : analyse retardée, historique borné, cache du profil | Mesures réelles appareils modestes, gros textes et rendu |
| 29 Moteur unique | Partiel : aide manuelle, pas de correction automatique concurrente | Inventaire complet et validation des claviers tiers |
| 30 Transversalité | Partiel : services partagés dans chaque plateforme | Contrat commun entre plateformes et modules futurs |
| 31 Propriétaire unique | Confort nouveau canonique par compte | Migration de toutes les préférences historiques avec garanties |
| 32 Compatibilité | Code Android/iOS ajouté | Builds complets, essais matériels et matrice officielle |
| 33 Tests globaux | Tests de logique ajoutés, harness écriture exécuté | Exécution CI complète et tests fonctionnels transversaux |
| 34 Thèmes | Partiel : confort ajouté aux points de rendu existants | Audit et refonte de chaque thème/composant |
| 35 Lisibilité | Partiel : contraste explicite Android, cartes opaques iOS | Contraste mesuré de tous les états et fonds |
| 36 Contraste dynamique | Non ajouté | Analyse locale stable ou protection systématique des fonds imprévisibles |
| 37 Protection lecture | Partiel : support opaque en mode renforcé | Couverture globale des textes personnalisés et effets |
| 38 Taille texte | Partiel : facteur Android, Dynamic Type iOS | Réorganisation de toutes les contraintes fixes, tests extrêmes |
| 39 Zoom universel | Partiel Android : lecteur des TextView compatibles | Tous contenus et parité iOS |
| 40 Pincement | Partiel Android : lecteur texte avec boutons alternatifs | Gestes sur documents/images et contrôle conflits système |
| 41 Zoom contenus | Texte Android seulement | Images, graphiques, documents et vues 3D |
| 42 Responsive | Partiel : hauteur des textes flexible, cartes et formulaires natifs | Revue de tous parents à hauteur fixe et écrans en paysage |
| 43 Défilement | Lecteur et paramètres dédiés défilables | Tous écrans et fenêtres au fort agrandissement |
| 44 Mémorisation | Confort et zoom lecteur Android locaux par compte | Choix détaillé des portées et zoom par document |
| 45 Extrêmes | Scénarios écrits, tests de logique ajoutés | Campagne sur petits/grands écrans, thèmes et performances |
| 46 Lecture prioritaire | Comportement renforcé branché | Vérification exhaustive et contrôle automatique de nouveaux composants |

## Vérifications observées

- `git diff --check` : aucun défaut de whitespace lors de la revue locale.
- Moteur d’écriture : harness Kotlin autonome exécuté avec le compilateur embarqué dans Gradle ; corrections acceptées, annulation/rétablissement, versions obsolètes, dictionnaire, Unicode et limite 50 000 caractères testés.
- `bash scripts/agent-toolbox.sh v2-tests` : ÉCHEC ENVIRONNEMENT, récupération de dépendances Google Maven impossible. Aucun succès de compilation Android annoncé.
- Commandes iOS officielles : BLOQUÉES sur Linux, macOS/Xcode requis. Les tests Swift ajoutés ne sont pas annoncés exécutés.
- Prévalidation de source par mobile_platforms/team_lead ; revue sécurité ayant identifié puis fait corriger les changements de compte et la récupération historique. Cela ne remplace pas le rapport obligatoire lié au SHA ni le sas de fusion.

La branche doit rester en brouillon tant que les contrôles requis et la validation fonctionnelle ne sont pas satisfaits. Pas de publication Google Play à partir d’un build non vérifié.
