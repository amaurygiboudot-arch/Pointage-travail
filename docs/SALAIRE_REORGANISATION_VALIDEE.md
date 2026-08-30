# HoraTrack — Réorganisation validée de l'onglet SALAIRE

Ce document fige les décisions fonctionnelles validées avant leur implémentation. Il ne demande pas la suppression des branches actives : lors de la réorganisation, supprimer/déplacer uniquement l'ancienne interface et conserver/reconnecter les moteurs, calculs, données et fonctions encore utilisés.

## Structure principale

Ordre de l'onglet SALAIRE :
1. + AJOUTER UNE ENTREPRISE
2. FICHE DE RENSEIGNEMENTS
3. MES ENTREPRISES

MES ENTREPRISES accepte un nombre illimité d'entreprises. Pas de libellés artificiels « Entreprise 1 / Entreprise 2 ». Une entreprise n'apparaît qu'après avoir été réellement ajoutée.

Chaque entreprise affiche de façon compacte son nom réel et `SIRET : <numéro>` dans un cadre individuel. La section MES ENTREPRISES possède également son cadre principal. Les cadres suivent le thème global.

Chaque fiche, contrat, salaire, compteur et donnée métier est obligatoirement rattaché à l'entreprise concernée afin qu'une entreprise ne puisse jamais écraser les données d'une autre.

## + AJOUTER UNE ENTREPRISE

Recherche possible soit par nom, soit par SIRET.

- Plusieurs correspondances : proposer plusieurs choix avec les informations utiles pour les différencier (nom, SIRET, adresse).
- Une seule correspondance fiable : un seul choix suffit.
- Toujours demander une confirmation finale de l'entreprise avant son ajout.
- Contrôler le format du SIRET lorsqu'il est saisi directement.
- Après confirmation : ajouter l'entreprise dans MES ENTREPRISES et créer/lier ses données propres.

Réutiliser les branches de recherche/ajout déjà fonctionnelles lorsque possible.

## FICHE DE RENSEIGNEMENTS

La fiche est liée à une entreprise précise.

À conserver/renseigner :
- type de contrat ;
- durée hebdomadaire contractuelle ;
- taux horaire brut ;
- montant du panier ;
- date d'entrée dans l'entreprise, nécessaire notamment au calcul d'ancienneté ;
- autres paramètres réellement nécessaires aux calculs.

Nom, SIRET et convention collective ne doivent pas être dupliqués dans cette fiche lorsqu'ils proviennent de la fiche entreprise.

Le bouton `ENREGISTRER LA FICHE` est conservé. Les valeurs destinées aux calculs sont validées par cet enregistrement.

Pendant le remplissage, maintenir l'écran allumé au maximum 15 minutes. Arrêter immédiatement ce maintien si la fiche est enregistrée ou fermée avant. Au bout de 15 minutes, rendre à Android son comportement normal même si la fiche est encore ouverte.

## Accès sécurisé à une entreprise

Toucher une entreprise dans MES ENTREPRISES ouvre son espace détaillé après authentification Android.

Utiliser la méthode de verrouillage configurée au niveau Android (biométrie ou identifiant de l'appareil pris en charge : PIN, schéma, mot de passe selon disponibilité). HoraTrack ne crée ni ne stocke son propre secret biométrique/PIN/schéma.

L'espace sécurisé contient au même niveau :
- INFORMATIONS ENTREPRISE
- CONTRAT
- FICHE DE SALAIRE
- DROITS, CONGÉS & REPOS

## INFORMATIONS ENTREPRISE

Contient notamment :
- nom ;
- SIRET ;
- adresse ;
- convention collective / IDCC ;
- autres informations administratives utiles.

Ajouter `MODIFIER LES INFORMATIONS` pour corriger les données sans recréer l'entreprise.

Placer `SUPPRIMER L'ENTREPRISE` tout en bas. Conserver la branche de suppression existante et déplacer seulement son accès. Demander une confirmation obligatoire avant suppression.

## CONTRAT

Ordre prioritaire d'affichage :
1. Taux horaire brut
2. Coefficient conventionnel
3. Type de contrat
4. Date d'entrée
5. Date de fin uniquement si applicable
6. Durée hebdomadaire contractuelle

Le coefficient conventionnel est le bon terme (pas « quotient salarial »). Utiliser la convention/IDCC et les sources officielles quand elles permettent de récupérer les grilles/règles. Ne jamais inventer un coefficient : lorsque la classification dépend du poste/niveau réel, demander à l'utilisateur de choisir/confirmer.

Le coefficient doit alimenter les calculs auxquels il s'applique (minimum conventionnel, ancienneté ou autres règles prévues par la convention).

Prévoir `MODIFIER LE CONTRAT` plutôt que laisser tous les champs modifiables en permanence.

Ne pas ajouter un numéro URSSAF uniquement pour rechercher ces informations : le SIRET reste l'identifiant principal de l'employeur pour les données publiques utiles.

## FICHE DE SALAIRE

Espace sécurisé propre à l'entreprise.

### Estimation HoraTrack

Présenter l'estimation sous une forme proche d'un véritable bulletin de salaire, avec la mention claire `FICHE DE PAIE ESTIMATIVE`.

Inclure selon les données/règles applicables :
- identité entreprise/salarié et période ;
- heures normales ;
- heures supplémentaires et majorations ;
- nuit ;
- samedi ;
- dimanche ;
- ancienneté ;
- paniers ;
- autres éléments de rémunération applicables ;
- cotisations/charges estimées ;
- brut estimé ;
- net estimé.

Tous les calculs existants utiles restent actifs. Appliquer les règles légales et conventionnelles réellement applicables, y compris les règles de cumul/non-cumul ; ne pas inventer de pourcentage universel.

Le montant du panier reste renseignable dans la Fiche de renseignements. L'estimation affiche notamment `nombre de paniers × montant unitaire = total`.

L'ancienneté doit être calculée à partir des données nécessaires (dont date d'entrée) et de la règle légale/conventionnelle applicable ; elle ne se limite pas à afficher une durée.

### Import et comparaison

`IMPORTER SALAIRE` se trouve dans FICHE DE SALAIRE, pas directement dans la page principale SALAIRE.

Si aucune vraie fiche n'est importée, proposer :
- PRENDRE UNE PHOTO
- IMPORTER UN FICHIER via le sélecteur Android (notamment Téléchargements ; PDF/image attendu).

Lier chaque fiche importée à la bonne entreprise. Classer par mois/année quand la période est reconnue, avec possibilité de corriger manuellement la période en cas d'erreur de détection.

Conserver l'original importé sans le modifier par les calculs HoraTrack. Permettre sa suppression avec confirmation.

Comparer par défilement horizontal (swipe) : Estimation HoraTrack ↔ vraie fiche importée. Le nombre de pages est dynamique et sans plafond artificiel. L'indicateur `1 / X` est du texte cliquable, pas un bouton 3D : toucher l'indicateur ouvre une sélection de page et permet de sauter directement à la page choisie. Sa couleur suit le thème.

### IA

L'IA doit être disponible dans FICHE DE SALAIRE pour analyser une vraie fiche importée, la comparer à l'estimation HoraTrack, expliquer les écarts et signaler les anomalies potentielles.

Elle doit distinguer clairement calcul certain, estimation et anomalie potentielle et ne jamais présenter une interprétation IA comme une certitude juridique.

## DROITS, CONGÉS & REPOS

Ce bloc n'est plus indépendant sur la page principale SALAIRE. Il se trouve dans l'espace sécurisé de chaque entreprise.

Conserver les branches et données existantes, notamment :
- congés acquis, pris, disponibles/restants ;
- autres compteurs de repos/droits ;
- AJOUTER / METTRE À JOUR UN COMPTEUR ;
- informations nécessaires aux calculs de salaire.

Les compteurs sont propres à chaque entreprise.

Le repos entre deux journées doit être calculé entre la dernière sortie définitive d'une journée de travail et la première entrée de la journée de travail suivante, sans confondre les pauses/coupures internes avec du repos inter-journées.

Dans ANALYSES, n'afficher qu'une seule ligne de repos pertinente/récente dans un cadre thématique, sans bouton.

L'historique complet va dans FICHE DE SALAIRE / PDF. Le calendrier doit également montrer clairement les week-ends et congés/vacances afin de préserver la continuité, sans transformer ces périodes en durées artificiellement énormes de « repos entre journées ».

## Nettoyage de l'ancienne interface SALAIRE

À retirer visuellement de la page principale SALAIRE après reconnexion de leurs fonctions utiles :
- ancien gros bloc `ENTREPRISE PRINCIPALE & SALAIRE` ;
- ancien PANIER autonome ;
- `RECALCULER` (calculs automatiques) ;
- `CHOISIR LE MOIS` / période Salaire (la période est déjà choisie dans ANALYSES) ;
- ancien `RÉSULTATS DU MOIS` et ses sous-blocs dupliqués ;
- ancien `CONTRÔLE DU BULLETIN DE PAIE` ;
- ancien accès direct à l'import de salaire ;
- ancien bloc indépendant `DROITS, CONGÉS & REPOS` ;
- libellés génériques Entreprise 1 / Entreprise 2 et autres répétitions inutiles.

IMPORTANT : « retirer/supprimer un bloc » signifie retirer l'ancienne UI seulement. Ne supprimer aucune branche active, moteur, donnée, calcul ou fonction encore utile. Reconnecter d'abord la fonctionnalité à sa nouvelle destination.

## Thèmes et futurs boutons 3D

Tous les cadres et boutons concernés par la nouvelle interface restent connectés au système central de thèmes : un changement de thème met à jour cadre et bouton automatiquement, sans style isolé codé en dur.

Les boutons 3D et leurs longueurs physiques seront classés/dimensionnés dans l'étape dédiée ultérieure. Les boutons diamant Entrée / Pause / Sortie sont terminés, exclus de cette réorganisation et ne doivent pas être modifiés.