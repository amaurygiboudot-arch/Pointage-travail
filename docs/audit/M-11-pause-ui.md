# M-11 — Séparer pauses manuelles et planning automatique

La saisie de 1 à 5 pauses concerne uniquement la journée sélectionnée.

Le planning automatique quotidien reste une fonction distincte et n'utilise qu'un créneau tant que le modèle `PauseScheduleManager` ne gère pas plusieurs plages.

## Critères UI

- bloc clairement titré **PAUSES MANUELLES DE LA JOURNÉE** pour la date, les 5 créneaux et le total ;
- bloc séparé **PLANNING AUTOMATIQUE QUOTIDIEN** pour le switch ;
- texte explicite : le planning automatique utilise le premier créneau uniquement ;
- aucune ambiguïté laissant croire que les créneaux 2 à 5 seront répétés chaque jour ;
- aucune modification du stockage ou du calcul de pause dans cette correction.
