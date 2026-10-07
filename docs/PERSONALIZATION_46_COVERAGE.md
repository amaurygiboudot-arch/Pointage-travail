# Personnalisation globale — couverture du lot du 7 octobre 2026

Ce lot contient du code branché sur les applications Android et iOS existantes. Il ne réalise pas les 46 exigences intégralement. Aucun point ci-dessous ne vaut preuve de livraison ou certification de compatibilité. Les comportements listés « partiels » nécessitent encore compilation complète et essais sur appareils.

## Périmètre réel

Android : entrée Paramètres > Personnalisation > Confort visuel et écriture ; profil local isolé par compte, zoom du texte, contraste renforcé, réduction des effets du ciel, contexte nuit/économie, export et import validé, réinitialisation visuelle réversible, lecteur de texte agrandi. L’aide à l’écriture est accessible dans les actions de sélection des champs compatibles et explicitement dans la boîte à idées.

iOS : réglages de confort, thème et accents, taille dynamique, surfaces opaques, contraste, mouvements réduits et contexte, import/export et annulation. Les champs natifs existants consomment un composant commun protégeant les champs techniques des corrections de prose.

Les sauvegardes natives complètes Android et iOS ont des schémas différents. Un transfert commun explicite partage trois réglages : contraste renforcé, réduction des mouvements et zoom de lecture. Il conserve les autres réglages propres à chaque plateforme et exige un aperçu puis une confirmation. Une sauvegarde manuelle du même sous-ensemble est également disponible dans le compte Firebase existant, avec révisions, conflits explicites et suppression versionnée. Il n’y a aucun envoi automatique ni nouveau service IA.

## Suivi des exigences

| Point | État de ce lot | Reste nécessaire |
| --- | --- | --- |
| 1 Profil universel | Partiel : profil de confort local par compte | Profil global, identité et toutes les préférences métier |
| 2 Apparence | Partiel : confort, taille, contraste, contexte | Luminosité, densité et harmonisation de tous les réglages historiques |
| 3 Accessibilité | Partiel : tailles système, contraste, lecteurs et lecture vocale native à la demande, réduction des effets | Revue complète TalkBack/VoiceOver et contrôles sur appareils |
| 4 Langue et région | Non ajouté | Traductions, formats et préférences régionales cohérentes |
| 5 Personnalité IA | Non ajouté | Moteur conversationnel et préférences consommées |
| 6 Relation IA | Non ajouté | Identité de communication et consommation par les réponses |
| 7 Explications | Non ajouté | Niveaux consommés par les moteurs d’explication |
| 8 Autonomie | Non ajouté | Politique d’actions et validations par catégorie |
| 9 Mémoire | Non ajouté hors confort/dictionnaire | Inspection, correction et oubli des données mémorisées |
| 10 Apprentissage | Non ajouté | Propositions d’habitudes réversibles, sans inférence cachée |
| 11 Notifications | Partiel : état réel et liens vers réglages système, canaux Android | Catégories, horaires silencieux, appareils et déduplication |
| 12 Confidentialité | Partiel : isolation, exclusions, brouillons chiffrés Android, état des permissions système | Contrôle global des traitements |
| 13 Multi appareils | Partiel : sauvegarde/restauration manuelles de trois réglages Android/iOS, contrôle de révision et suppression versionnée | Synchronisation automatique des catégories autorisées ; essais réels de concurrence et de changements de compte |
| 14 Modules | Partiel : services communs des applications existantes | Contrat d’enregistrement pour futurs modules et Genesis |
| 15 Contextes | Partiel : contextes manuels prioritaires et horaire de nuit local configurable, actif seulement au premier plan | Autres contextes configurables et combinaisons de règles |
| 16 Sauvegarde | Partiel : confort exportable/importable, reset visuel ; transfert Android/iOS de trois réglages communs | Restauration globale transactionnelle et portabilité des autres réglages |
| 17 Écriture commune | Partiel : champs Android éligibles et wrapper SwiftUI | Dialogues Android, champs personnalisés et inventaire exhaustif |
| 18 Orthographe/grammaire | Partiel : correcteur Android installé, accord explicite et validation ; clavier natif iOS | Véritable correcteur grammatical multilingue ; aucune IA simulée |
| 19 Frappe | Très limité : erreurs connues proposées | Détection contextuelle complète des fautes de frappe |
| 20 Suggestions | Partiel Android : acceptation explicite et révision du texte | Parité iOS, essais de concurrence/IME sur appareils |
| 21 Dictionnaire | Partiel Android : privé, chiffré, par compte/langue | Parité iOS et échanges contrôlés |
| 22 Complétion | Limitée au dictionnaire personnel Android | Prédiction contextuelle, parité et mesures de latence |
| 23 Reformulation | Non ajouté | Moteur de reformulation avec comparaison et vérification du sens |
| 24 Dictée | Partiel Android : service vocal système, aperçu éditable et insertion confirmée ; clavier iOS | Essais interruptions/appareils et parité iOS |
| 25 Fiabilité saisie | Partiel : garde de composition, limites Unicode, exclusions techniques | Matrice réelle claviers, gestes, orientations et longs textes |
| 26 Brouillons | Partiel Android : boîte à idées, conservation chiffrée 7 jours | Identité explicite de chaque document, extension aux autres écrans/iOS |
| 27 Annuler/rétablir | Partiel Android : historique borné du texte | Parité et intégration complète des commandes natives |
| 28 Performance | Partiel : analyse retardée, historique borné, cache du profil | Mesures réelles appareils modestes, gros textes et rendu |
| 29 Moteur unique | Partiel : aide manuelle, pas de correction automatique concurrente | Inventaire complet et validation des claviers tiers |
| 30 Transversalité | Partiel : services partagés et contrat de transfert commun pour trois réglages | Extension du contrat aux autres réglages et modules futurs |
| 31 Propriétaire unique | Confort nouveau canonique par compte | Migration de toutes les préférences historiques avec garanties |
| 32 Compatibilité | Code Android/iOS ajouté | Builds complets, essais matériels et matrice officielle |
| 33 Tests globaux | Tests de logique ajoutés, harness écriture exécuté | Exécution CI complète et tests fonctionnels transversaux |
| 34 Thèmes | Partiel : confort ajouté aux points de rendu existants | Audit et refonte de chaque thème/composant |
| 35 Lisibilité | Partiel : contraste explicite Android, cartes opaques iOS | Contraste mesuré de tous les états et fonds |
| 36 Contraste dynamique | Partiel : choix noir/blanc par contraste sRGB et protection opaque sur fond photo Android | Couverture complète des rendus et parité iOS |
| 37 Protection lecture | Partiel : support opaque en mode renforcé | Couverture globale des textes personnalisés et effets |
| 38 Taille texte | Partiel : facteur Android, Dynamic Type iOS | Réorganisation de toutes les contraintes fixes, tests extrêmes |
| 39 Zoom universel | Partiel : lecteurs texte Android/iOS et images bitmap Android | Autres contenus et couverture exhaustive |
| 40 Pincement | Partiel : texte Android/iOS, images Android et PDF natifs, boutons alternatifs | Essais gestes et conflits sur appareils |
| 41 Zoom contenus | Partiel : texte, images Android, PDF Android existants et Quick Look iOS | Graphiques, autres documents et vues 3D |
| 42 Responsive | Partiel : hauteur des textes flexible, cartes et formulaires natifs | Revue de tous parents à hauteur fixe et écrans en paysage |
| 43 Défilement | Lecteur et paramètres dédiés défilables | Tous écrans et fenêtres au fort agrandissement |
| 44 Mémorisation | Confort et zoom lecteur Android/iOS locaux par compte | Choix détaillé des portées et zoom par document |
| 45 Extrêmes | Scénarios écrits, tests de logique ajoutés | Campagne sur petits/grands écrans, thèmes et performances |
| 46 Lecture prioritaire | Comportement renforcé branché | Vérification exhaustive et contrôle automatique de nouveaux composants |

## Vérifications observées

- `git diff --check` : aucun défaut de whitespace lors de la revue locale.
- Moteur d’écriture : harness Kotlin autonome exécuté avec le compilateur embarqué dans Gradle ; corrections acceptées, annulation/rétablissement, versions obsolètes, dictionnaire, Unicode et limite 50 000 caractères testés.
- Tests Android locaux via Gradle : récupération des dépendances Google Maven indisponible dans cet environnement ; les builds complets sont exécutés par GitHub Actions.
- Commandes iOS locales : macOS/Xcode requis. Compilation et tests exécutés sur le runner macOS GitHub.
- CI du lot avec transfert commun `8a57e032bfd4adaee84e3299a57f4804e78dc828` : tous les contrôles réussis, dont Android, variantes Play, tests V2, CodeQL, Firebase, compilation et tests iOS. Ces résultats ne valident pas les ajouts suivants ; ils doivent être relancés pour leur SHA.
- Nuit automatique : harness Kotlin exécuté sur 10 080 cas minute/contexte et intervalle invalide. Découpage vocal : harness Unicode et longs textes exécuté.
- Sauvegarde de compte : contrat de révision/tombstone couvert par tests unitaires ajoutés ; revue sécurité source effectuée. Essais réels deux appareils/hors ligne encore requis.
- Contraste : harness de 5 832 couleurs exécuté avec seuil 4,5:1 ; filtres des corrections fournisseur testés avec harness Kotlin.
- Prévalidation de source par mobile_platforms/team_lead ; revue sécurité ayant identifié puis fait corriger les changements de compte et la récupération historique. Cela ne remplace pas le rapport obligatoire lié au SHA ni le sas de fusion.

La branche doit rester en brouillon tant que les contrôles requis et la validation fonctionnelle ne sont pas satisfaits. Pas de publication Google Play à partir d’un build non vérifié.
