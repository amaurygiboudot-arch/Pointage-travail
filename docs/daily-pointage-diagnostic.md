# Diagnostic journalier du pointage

Le diagnostic est facultatif et local. L'utilisateur l'active dans les paramètres de pointage, choisit une journée, consulte le rapport puis le partage volontairement. Aucun envoi automatique ni service payant n'est ajouté.

## Lecture du rapport

- L'heure de réception d'un événement GPS ne prouve pas l'heure physique du franchissement de la zone.
- Une tentative de pointage n'est pas une écriture réussie.
- L'instantané des pointages à l'export décrit les données actuellement conservées ; ce n'est pas une reconstruction de leur historique.
- Une trace absente, interrompue, tronquée ou illisible reste inconnue. Elle ne prouve jamais une absence de travail.
- La collecte commence à l'activation. Les journées antérieures ne peuvent pas être reconstituées à partir de ce journal.
- Le diagnostic reste séparé par compte. Son effacement ne modifie pas les pointages.

## Comparaison avec le récit de l'utilisateur

Après réception du fichier, demander les heures réelles d'arrivée, de début de travail, de chaque pause, de fin de travail et de départ. Demander également les déplacements, changements de site, retours, confirmations manuelles et interruptions connues du téléphone.

Comparer dans un tableau : récit de l'utilisateur, observation du téléphone, décision de l'application, pointage conservé, écart et niveau de preuve. Distinguer les causes démontrées des hypothèses. Ne pas corriger automatiquement un historique sur une hypothèse.

## Essais sur appareil

1. Vérifier qu'aucune trace métier n'est collectée avant activation.
2. Activer, pointer une entrée, débuter/terminer une pause et confirmer une sortie ; comparer les heures du rapport avec l'historique.
3. Tester un départ puis un retour entre plusieurs zones et une confirmation retardée.
4. Fermer et rouvrir l'application ; vérifier la conservation des traces déjà écrites et les limites indiquées.
5. Passer de A à B puis à A : aucun rapport ni aucune trace de A ne doit être présenté à B.
6. Exporter une journée de nuit et une journée sans trace ; vérifier dates, fuseau et mention explicite des inconnues.
7. Effacer le diagnostic : enregistrement arrêté, pointages conservés, exports temporaires supprimés.

Les tests automatiques ne remplacent pas ces essais physiques, notamment pour les délais GPS et les restrictions des fabricants.

## Sous-lot diagnostic préparé le 15 octobre 2026

Le diff de travail distingue désormais la complétude de l'historique et la
lisibilité du journal du compte courant. Un historique non attribué ne valide
pas son contenu ; une session courante lisible ne certifie pas un total global.
Les motifs de refus et les origines renseignées concernent les nouvelles traces
facultatives uniquement. Une demande de fermeture n'est pas une écriture réussie.

Le [bilan initial](diagnostic-subset-20261015.md) décrit le checkpoint diagnostic,
avant la migration du départ. L'état du travail courant, ses limites et les
commandes de validation figurent dans la [reprise du départ](departure-evidence-handoff.md).
Les tests runtime Android/iOS n'ont pas pu être exécutés localement ; aucune
livraison n'est attestée.

## Observation de départ et provenance

Une observation GPS est distincte d'une demande de fermeture et de son écriture
réussie. Une sortie volontaire admissible peut reprendre l'heure observée plus
tôt ; son origine reste MANUAL ou WIDGET, même si l'heure vient du GPS. Une
ancienne trace GPS_CONFIRMATION ne doit pas être réinterprétée comme une clôture
automatique. Les origines historiques absentes restent inconnues.

L'état « hors site / à vérifier » conserve la session ouverte sans question
systématique de fin de journée. L'heure observée n'est réutilisable qu'après les
contrôles de session, compte, entreprise, configuration et retour. Les anciennes
demandes persistées ne sont pas des preuves nouvelles. Une politique automatique
reste un choix explicite par compte/entreprise, désactivé par défaut ; sa présence ne
prouve pas que ses conditions d'exécution soient remplies.

La présence GPS ne qualifie jamais à elle seule une période comme payée.
Le diagnostic reste facultatif, local et sans noms, adresses ni coordonnées.
La rétention est appliquée lors des accès actifs ; un fichier illisible ne permet
pas de garantir une purge à échéance exacte.
