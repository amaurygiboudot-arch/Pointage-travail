# HoraTrack — critères de publication du pointage

**Décision utilisateur du 9 octobre 2026 : le pointage est désormais la priorité produit n° 1.** Salaire V2 doit rester compatible, mais son achèvement ne détourne pas le chantier des blocages de pointage. Ce fichier définit les *preuves à obtenir* ; aucun résultat non testé n'y vaut PASS.

## Invariants de base

- Toute session conserve les faits (entrée, sortie, pauses, fuseau et provenance) sans arrondi implicite ni réécriture silencieuse de l'histoire.
- **Présence GPS ≠ temps de travail ≠ salaire.** Une zone GPS ne fabrique aucune minute payée.
- Comptes, employeurs, contrats, dates et lieux restent séparés. Les règles d'un autre utilisateur ne sont jamais importées par défaut.
- Informations manquantes/incohérentes : conserver les faits, marquer le résultat « à confirmer », ne pas déduire un zéro ou détruire une journée.
- Parité métier Android/iOS ; tenir compte des limites propres aux appareils, versions d'OS et permissions.

## Gates P0 avant publication publique

| Gate | Preuve requise | État constaté |
|---|---|---|
| Horaires factuels | Entrée **06:15**, sortie prévue 16:00 mais réelle **16:07**, nuit et changement d'heure : ne jamais remplacer une heure réelle sans règle et trace | Protection dans PR #689, appareils non vérifiés |
| Pauses | Pauses rémunérées/non rémunérées, statut indéterminé, fermeture/reprise, sans double déduction | Correctifs V2 et tests automatisés ; end-to-end requis |
| Multizones GPS | Travail/parking/pause/atelier/chantier, plusieurs zones par lieu, superposition, changement de zone, sortie partielle | Android : éditeur et regroupement multi-zones ; iOS : zones circulaires ; appareils non vérifiés |
| GPS erratique | Précision faible, callback obsolète ou doublé, permissions refusées, changement de géométrie | Nécessite tests sur appareils et OS variés |
| Hors ligne | Pointage manuel/widget, fermeture, redémarrage, reprise réseau, sauvegarde et restauration sans perte/duplication | Non testé de bout en bout |
| Cloisonnement | Employeurs et comptes A/B, changement de compte en cours de session, données historiques non réattribuées | Registre personnel protégé en PR #689 ; stores historiques à auditer |
| Historique & PDF | Durées canoniques identiques dans pointage, historique, récap, export ; avertissements de fiabilité visibles partout | Non prouvé de bout en bout |
| Accessibilité | Boutons réactifs, textes sans débordement, contraste lisible à 100 %/200 %, Android/iOS | Vérification physique en attente |
| Sécurité/QA | CI du commit de livraison, tests Android/iOS, revue humaine et tests sur appareils sans FAIL bloquant | CI de PR #689 verte au SHA antérieur ; revue physique et QA en attente |
| Distribution | Paquet signé, SHA + versionCode + piste Google Play interne, preuve de publication, puis décision de passage en public | Aucune livraison de PR #689 prouvée |

## Rejeu concret obligatoire

1. Journée 05:00–13:00 avec pause payée explicite.
2. Arrivée 06:15 préservée sans arrondi historique.
3. Sortie prévue 16:00, réelle 16:07 conservée.
4. Nuit traversant minuit et deux changements d'heure.
5. Travail zone A → zone B, chevauchement des zones et callbacks inversés : aucune fausse sortie.
6. Zone Parking/Pause et zone Travail superposées : pas de déduction de travail fictive.
7. Geofence supprimée/modifiée : callback ancien rejeté et session préservée.
8. Entreprises A/B et comptes A/B : aucun mélange de faits/règles.
9. Mode hors ligne, écran fermé, redémarrage du téléphone, restauration.
10. Export PDF et historique alignés sur les faits et le niveau de fiabilité.
11. Agrandissement, contraste, interaction tactile sur de vrais appareils.
12. Astreinte/déplacement non qualifiés : avertissement, jamais salaire certifié.

Pour chaque test : date, plateforme, modèle, version d'OS et de l'app, SHA, attendu, observé, PASS/FAIL, preuve anonymisée, anomalie ouverte si échec. **Non testé ≠ PASS**.

## Ordre de travail

1. Corriger les blocages P0 du **pointage** (entrées/sorties, pauses, GPS multi-zones, hors ligne et historique).
2. Tester et comparer le comportement Android/iOS et les différentes catégories d'utilisateurs.
3. Déployer sur piste **Google Play internal** une version test signée une fois provenance et CI validées, puis effectuer les essais physiques.
4. Corriger les échecs, obtenir les revues et contrôles requis, puis envisager la fusion/publication.
5. Si Salaire V2 demeure incomplet, ses calculs ne doivent pas être exposés comme définitifs ou certifiés.

**Hors priorités** : jeu, Céleste, IA avancée des bulletins, thèmes non bloquants, monétisation et fonctionnalités secondaires.

## Checkpoint de référence

- Dépôt `amaurygiboudot-arch/Pointage-travail`, PR brouillon [#689](https://github.com/amaurygiboudot-arch/Pointage-travail/pull/689), non fusionnée au 9 octobre 2026.
- SHA avant cette documentation : `16ecf054706865a62ce86bbba38105cff8042a4a` ; contrôler le HEAD et les CI après commit.
- La réussite des CI de PR #689 n'atteste pas les tests physiques ni la publication Google Play.

## Réparation des sorties GPS du 10 octobre 2026

Le chantier reprend le HEAD `2b13c77b860ce094f8912171e8902a5a4b8baecb` de la PR #689. Ses contrôles Security checks 37892940189 et Build iOS 37892940190 ont été vérifiés SUCCESS. Ces résultats précèdent les corrections ci-dessous et ne les valident pas.

- Android : une sortie Travail observée à 16:00 ne doit pas devenir 16:07 parce qu'une autre zone non équivalente est quittée plus tard. Le chemin effectivement inscrit concerne plusieurs zones Travail ; Parking/Pause/Autre servent aussi de tests défensifs, mais ne sont pas inscrites comme geofences automatiques ordinaires à ce HEAD. Les observations par zone sont conservées pendant l'arbitrage, avec les zones encore actives. Seule une continuité prouvée entre zones Travail équivalentes peut prolonger cette observation. Une heure manquante reste inconnue ; les faits historiques ne sont pas réécrits. Ce défaut source n'établit pas à lui seul la cause de l'incident observé sur le téléphone d'Amaury.
- iOS : le gestionnaire de localisation appelle la politique de continuité configurée. Toutes les zones candidates doivent être connues, de type Travail et compatibles en employeur et géométrie. La zone choisie à l’arrivée et les heures observées par zone sont persistées ; une zone non équivalente ne doit pas repousser le départ de la session. Une ancienne observation sans preuve suffisante reste à vérifier, sans inventer une heure de sortie.
- iOS : confirmer ou ignorer un ancien dialogue doit revérifier l'événement persisté courant avant toute modification. Un événement annulé, remplacé ou modifié n'autorise plus une entrée, une sortie ou la suppression d'un autre événement.

Régressions ciblées : Travail/Parking, zones Travail équivalentes et contradictoires, réentrée, doublons, reprise après persistance, absence d'horodatage, limite de continuité, autre employeur/session et dialogue obsolète.

Cas de référence du lot : A est la zone liée à la session, B une zone Travail équivalente déjà active, C une zone étrangère. Sorties A à 16:00, B à 16:07, C à 16:10 : heure attendue 16:07, grâce à la continuité A vers B. Si B entre seulement après la sortie de A, cette continuité n’est pas prouvée et ne doit pas prolonger A. Un retour dont les candidats mêlent des employeurs ou sites incompatibles ne doit pas annuler silencieusement le départ.

Android : le défaut de reprise identifié dans cet intervalle est traité par une file durable enregistrée dans le même commit que la présence finale. Le rejeu conserve l'heure observée, vérifie le compte, la session, son arrivée et la configuration, puis attend un acquittement persistant. Les reçus survivent à l'annulation pour éviter la réapparition d'une demande déjà traitée. Les observations partielles sont liées au même contexte. Le rejeu passe par le cycle de confirmation existant, sans exiger un nouveau callback GPS et sans fermer automatiquement le travail. Une preuve de retour horodatée et liée au contexte est également enregistrée avant la temporisation d’entrée : après interruption, elle permet d’annuler l’ancienne sortie de la même zone ou d’une zone Travail équivalente dans la fenêtre vérifiée, sans annuler les contextes ambigus. Les tests d’intégration couvrent les pannes de stockage et les reprises ; leur exécution complète reste requise sur le nouveau SHA.

Validation locale complète bloquée : dépendances Android inaccessibles depuis cet environnement ; iOS exige macOS/Xcode. Les tests purs Kotlin exécutés réussissent ; des tests d’intégration Android et des tests Swift ciblés couvrent les corrections et les anomalies supplémentaires relevées en QA. Leurs résultats CI au nouveau SHA doivent être consignés séparément. La commande officielle de revue reste indisponible ici (`codex` absent). Les essais sur appareils et les autres gates de publication restent ouverts ; cette réparation seule n'autorise ni fusion de l'ensemble de la PR ni publication publique.
