# Pointage GPS et trajets entre sites — 2 novembre 2026

## Demande confirmée

Le pointage ne se déclenche ni à l'arrivée ni au départ. Chaque arrivée et chaque
départ de site doivent être enregistrés. Les trajets entre sites sont distincts
des trajets domicile → travail et travail → domicile, qui sont exclus.

Base de travail : candidat PR704, commit
`a9a342067d35d17fef13e1752ff105e9fdb74a0a`. Ce candidat n'est pas `main` et ne
constitue pas une preuve de la version installée sur le téléphone.

## Contrat des faits

- L'horodatage d'un callback est une **heure d'observation**, pas une mesure
  garantie de l'instant physique du franchissement. Les plateformes peuvent
  retarder les notifications GPS.
- Une arrivée persiste son contexte compte, entreprise, zone, configuration et
  session. La reprise réutilise cette observation ; ouvrir l'application ne
  fabrique jamais une arrivée.
- Une sortie de site admissible est enregistrée sans heure de fin théorique ni
  ancienne grille d'arrondi. Les contrôles de compte, session, chronologie,
  stockage et zones restent obligatoires.
- Les callbacks redondants n'ajoutent pas de deuxième session. Un échec de
  stockage ne vaut jamais reçu de pointage.
- Les zones qui se chevauchent restent soumises à l'arbitrage de présence. Les
  observations sont conservées ; une situation ambiguë ne devient pas une
  succession artificielle de sessions ou un trajet de durée négative.
- Une session antérieure incohérente, un journal illisible ou une pause non
  résolue ne sont pas réparés par invention d'horaires. L'observation conservée
  reste à vérifier.

## Présence et paie

Les nouvelles présences GPS portent une qualification d'activité `TO_CONFIRM`
dans les segments canoniques de session. Les heures restent visibles, mais le
temps payé reste inconnu jusqu'à qualification. Le moteur salarial n'interprète
jamais cette incertitude comme un zéro fiable.

L'utilisateur peut qualifier un intervalle fermé comme travail effectif ou
présence hors travail. L'écriture vise la session existante, vérifie le compte
et le contenu affiché, conserve les horaires, les pauses et la provenance, puis
actualise ensemble l'historique et la session courante lorsqu'ils représentent
la même session. Cette action ne qualifie aucun trajet.

Les sessions historiques et manuelles n'acquièrent pas automatiquement une
nouvelle qualification. Les sauvegardes doivent conserver les segments ouverts
et fermés sans effacer leurs inconnues.

## Trajets observés

Le rôle de zone **Domicile** sert à exclure les trajets domicile-travail. Il ne
déclenche aucun pointage professionnel et ne participe pas à la présence sur un
site de travail.

Le journal de transitions reste local et séparé par compte. La durée d'un
intervalle entre un départ Travail et une arrivée Travail sur un autre site est
calculée à partir des deux observations. Un passage Domicile ou une interruption
observée coupe la chaîne. Les doublons, changements de contexte et chevauchements
ne doivent jamais créer un trajet fictif.

Cette durée n'est pas nécessairement du temps de conduite. Sans preuve du
parcours et de sa nature, le trajet reste **à confirmer**, notamment quand le
domicile n'est pas configuré ou quand les employeurs diffèrent. Aucun seuil de
durée ou de changement de date ne remplace cette preuve.

La distance routière reste inconnue : deux positions ou un lien Maps ne
mesurent pas les kilomètres parcourus. Les trajets observés ne sont pas ajoutés
automatiquement à la paie ou aux indemnités kilométriques.

## Validation et livraison

Les résultats d'exécution sont consignés dans le compte rendu du lot, avec le
SHA exact. Une compilation ou un test de logique ne valide pas les callbacks
physiques GPS ni les restrictions d'arrière-plan des appareils.

EDUCTOR était accessible pendant cette reprise, mais aucun appareil ADB n'était
connecté. Aucun pointage utilisateur n'a été corrigé et aucune version n'a été
installée ou publiée pendant l'implémentation.

Avant distribution : tests Android et iOS, revue du diff, essais physiques
arrivée/départ/retour, sites multiples, domicile et reprise après interruption.
Les règles de fusion et de publication du dépôt restent inchangées.

Le retour à une ancienne version iOS qui ignore les segments de qualification
n'est pas pris en charge après création de nouveaux faits GPS : elle pourrait
perdre leur statut « à confirmer ». Un éventuel correctif de retour arrière
doit conserver ce schéma et ses contrôles. Le journal de passages Android est
local et n'est pas encore inclus dans la sauvegarde cloud ; les sessions
canoniques et leurs qualifications utilisent le circuit de sauvegarde existant.
