# HoraTrack — refonte du pointage à partir de zéro

Décision utilisateur : 30 octobre 2026, Europe/Paris.
Périmètre : 100 % des utilisateurs concernés, Android et iOS, tous métiers, contrats, sites, appareils compatibles.
Statut : CHANTIER EN COURS. Document d'architecture et premier lot local, non publié.

## Intention et frontière de suppression

CORRECTION DU 30/10/2026 : ENTRÉE et SORTIE restent possibles par deux déclenchements : bouton manuel OU détection GPS automatique (arrivée/départ). Conserver les deux voies Android/iOS ; ne pas désactiver le GPS. L'interface de pointage doit rester limitée à ces deux actions, sans bouton pause ni règles métier superflues. Le GPS doit garder ses protections de précision, anti-doublon et reprise ; toute incertitude reste à vérifier.

Retirer progressivement les règles métier historiques obsolètes **du pointage actif**, sans les transposer automatiquement dans une nouvelle version. Ne pas retirer les protections d'intégrité, la provenance des faits, le cloisonnement des comptes, ni les règles légales/salariales qui relèvent du moteur Salaire V2.

**Restent obligatoirement conservés :**
- événements factuels (arrivée, début/fin d'activité, pauses, déplacements, sortie), heures réelles et fuseau ;
- identifiants stables utilisateur, entreprise, session, provenance et états de confirmation ;
- persistance hors ligne, restauration, historique et documents déjà enregistrés ;
- statut payé/non payé/inconnu d'une pause, sans transformer l'inconnu en zéro ;
- contrats, règles juridiques datées, bulletins, retenues, primes, absences, indemnités et calculs de Salaire V2 ;
- la frontière canonique du temps payé, sa fiabilité et son exhaustivité vers les calculateurs de salaire ;
- les règles historiques **uniquement pour relire/recalculer les périodes passées sans réécriture silencieuse**.

**À retirer du moteur de pointage actif après cartographie et validation :**
- arrondis et tolérances automatiques d'entrée/sortie sans règle nouvellement confirmée ;
- déductions et qualifications automatiques de pauses ou déplacements ;
- anciennes heuristiques GPS non fiables à remplacer par une détection automatique d'entrée/sortie vérifiable, sans supprimer l'automatisation GPS ;
- règles particulières à un employeur, des équipes, zones ou heures codées en dur ;
- chemins concurrents et fallbacks de pointage quand le remplacement est autonome et prouvé.

## Premier lot effectif sur la copie isolée

Branche locale : refonte/pointage-zero-20261030
Worktree : C:\Users\amaur\HoraTrack-Pointage-Refonte-20261030
Base PR 689 constatée au début : 670be78942d82d4a2893b85e2de47d9c2977326f.

1. Android TimeEngineV2 : les API de comptage de *nouveaux* faits restituent l'entrée et la sortie réelles, sans créneau de 30 minutes/10 minutes ou seuil de sortie de 20 minutes.
2. Android HoraTrackV2 : contrôles de cohérence internes alignés sur les heures factuelles.
3. Android TimeEngineV2Test : attendu des nouveaux pointages ajusté ; le test de réparation de l'ancien arrondi reste conservé.
4. Android V2ManualSessionWriterTest : les sessions manuelles courtes gardent désormais une entrée et une sortie comptées identiques aux heures réelles ; un test séparé vérifie que l'ancienne entrée comptée située après la sortie reste inconnue et n'invente pas de durée.
5. Android V2RuntimeStore : la fermeture d'une session LEGACY_UNVERIFIED garde explicitement WorkTimePolicyV2.countedExit, pour ne pas altérer la lecture historique. Les sessions REAL_FACTS passent toujours par leurs heures réelles.
6. iOS : WorkStoreV2 possède déjà une saisie factuelle ; pas de modification iOS de ce premier lot. La parité de la refonte globale reste à tester.

**À conserver et fiabiliser** : détection GPS automatique des entrées et sorties sur Android/iOS, géorepères, règles de départ/retour pertinentes, états persistants et protections anti-doublons. Les heuristiques anciennes sont à auditer et non à supprimer sans remplacement. Aucune suppression globale n'est prétendue. Les anciens fichiers ne seront effacés qu'après absence de consommateurs vérifiée.

## Carte des dépendances à préserver

Android :
- Modèle WorkSessionV2 / PauseV2 ; V2RuntimeStore, V2RuntimeHistoryGuardV2, V2RuntimeReader et V2ManualSessionWriter.
- TimeEngineV2.calculate, PaidWorkAllocationV2, MonthlyPaidWorkScopeV2, SegmentedPayrollSessionEvidenceBuilderV2.
- Historique WorkTimePolicyV2.repairKnownCountedEntry et WorkTimePolicyV2.countedExit des sessions non vérifiées.
- Frontière des contrats/règles et des exports de Salaire V2.

iOS :
- WorkStoreV2, WorkSessionPersistenceV2, WorkSessionAccountStorageV2, ManualSessionPolicyV2.
- SalaryWorkSessionBridgeV2, SalaryPaidWorkAggregatorV2, SalarySegmentedPayrollSessionEvidenceBuilderV2 et stores des règles salariales.
- Distinction explicite entre fait GPS et temps payé.

## Conditions de migration/suppression ultérieure

1. Terminer inventaire de tous les producteurs/consommateurs d'événements et règles, y compris widgets, back-up/PDF et migrateurs.
2. Écrire le contrat universel du nouveau journal de faits sans créer de règles implicites.
3. Implémenter et comparer Android/iOS sur comptes multiples, mobile hors ligne, redémarrage, déplacements multizones, pauses, sessions incomplètes et changement d'heure.
4. Vérifier à l'identique les résultats Salaire V2 sur des historiques non modifiés ; un fait ambigu bloque la certification au lieu de produire zéro fiable.
5. Corriger les anomalies et réussir tests/compilations/revues applicables, puis supprimer uniquement les anciens chemins démontrés inutiles.
6. Fusionner/livrer via circuits autorisés avec SHA/version prouvés ; installation et tests physiques séparés.

## Contrôles et limites de ce premier lot

- Sources des quatre fichiers modifiés contrôlées sur EDUCTOR, dans un worktree initialement propre.
- Aucune suppression de données utilisateur, aucune modification des modules Salaire V2, ni de code iOS lors de ce lot.
- Tests unitaires modifiés, **NON EXÉCUTÉS** à ce stade. Builds Android/iOS, tests physiques, régression paie end-to-end, revue de fusion, publication Google Play : **NON VALIDÉS**.
- L'autre copie de travail C:\Dev\AGKGMG\source et HoraTrack-review-689-20261014, toutes deux modifiées localement, ne doivent jamais être réinitialisées ou écrasées.
- Dernier état du candidat du 21 octobre : compilation Android en échec documentée, qui ne constitue pas un résultat de ce nouveau lot.

Prochain point de reprise : auditer/extraire les producteurs automatiques GPS et les règles de pause dans la nouvelle architecture, maintenir les écritures factuelles et la frontière Salaire V2, exécuter les tests quand l'ordinateur dispose des ressources suffisantes, puis seulement envisager les suppressions anciennes et la distribution.
