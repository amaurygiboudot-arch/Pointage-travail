# Céleste V2 — Protocole de validation sur appareils réels

## Statut

Ce document décrit la **validation finale obligatoire** de Céleste sur matériel physique.

> Important : la présence de ce protocole ne signifie pas que la validation réelle est terminée.
> Le lot Céleste ne doit pas être déclaré publiable tant que les résultats Android **et** iOS ne sont pas enregistrés et que les anomalies bloquantes ne sont pas fermées.

## 1. Principes non négociables

La validation doit confirmer, sur appareil réel, que :

- l’astronomie reste la vérité primaire ;
- la météo ne déplace jamais Soleil, Lune, étoiles ou constellations ;
- la luminosité ambiante ne crée jamais artificiellement le jour ou la nuit ;
- l’absence d’un capteur produit un fallback propre ;
- une boussole non qualifiée utilise le **mode Nord stable** ;
- une localisation non qualifiée ne produit pas un faux ciel local précis ;
- les transitions restent progressives ;
- le rendu reste lisible et utilisable ;
- Céleste n’affecte ni Pointage, ni Salaire, ni les autres fonctions métier ;
- le comportement fonctionnel est commun aux appareils officiellement supportés.

## 2. Matrice minimale d’appareils

### Android

Valider au minimum :

1. appareil entrée de gamme / RAM limitée ;
2. appareil milieu de gamme ;
3. appareil haut de gamme ;
4. au moins un appareil sans capteur de lumière si disponible ;
5. au moins un appareil avec capteur de lumière ;
6. différentes densités et tailles d’écran ;
7. portrait et paysage lorsque l’OS/l’application les autorisent.

Aucune marque ou aucun modèle ne doit disposer d’une règle métier spéciale.  
Les différences autorisées concernent seulement les capacités matérielles et la qualité graphique adaptative.

### iOS

Valider au minimum :

1. un iPhone parmi les plus anciens encore officiellement supportés ;
2. un iPhone récent ;
3. tailles d’écran différentes si possible ;
4. mode économie d’énergie activé/désactivé.

iOS ne doit jamais simuler un capteur de luminosité ambiante non exposé par les API publiques.

## 3. Conditions de test

Pour chaque appareil, relever :

- modèle ;
- version Android/iOS ;
- version HoraTrack ;
- SHA Git exact ;
- résolution/densité ;
- RAM ou classe mémoire lorsque disponible ;
- présence capteur de lumière Android ;
- état GPS ;
- état boussole/orientation ;
- connexion réseau ;
- mode économie d’énergie ;
- état thermique si observable.

## 4. Scénarios TEMPS

Exécuter ou reproduire de façon contrôlée :

- matin ;
- midi ;
- coucher ;
- crépuscule ;
- nuit ;
- passage de minuit.

### Critères

- aucune bascule brutale artificielle ;
- ciel de jour clair quand le Soleil est haut ;
- transition progressive quand le Soleil descend ;
- étoiles/constellations apparaissent progressivement ;
- aucune étoile rendue visible artificiellement en plein jour ;
- passage de minuit sans saut graphique ou astronomique.

## 5. Scénarios ASTRONOMIE

Vérifier :

- Soleil au-dessus de l’horizon ;
- Soleil au-dessous ;
- Lune visible en journée ;
- Lune visible la nuit ;
- Lune sous l’horizon ;
- phases lunaires distinctes ;
- constellations différentes selon date/saison ;
- cohérence sur plusieurs latitudes si déplacement réel ou simulation de développement contrôlée.

### Critères

- positions cohérentes avec l’éphéméride calculée ;
- phase de Lune correcte ;
- aucune constellation décorative ;
- le mouvement du téléphone ne modifie jamais la position astronomique elle-même ;
- le globe reste cohérent avec la direction réelle du Soleil.

## 6. Scénarios MÉTÉO

Tester :

- clair ;
- partiellement nuageux ;
- couvert ;
- pluie ;
- brouillard ;
- neige ;
- orage ;
- météo absente ;
- météo périmée.

### Critères

- la météo agit uniquement sur l’atmosphère/visibilité ;
- les nuages ne sont jamais présentés comme une image satellite exacte ;
- météo absente = ciel astronomique seul ;
- météo périmée = non présentée comme actuelle ;
- changement de cellule GPS = ancienne météo non réutilisée pour le nouveau lieu.

## 7. Scénarios LUMINOSITÉ

### Android avec capteur

Tester :

- obscurité ;
- intérieur normal ;
- extérieur lumineux ;
- variation rapide ;
- mesure aberrante ou capteur déclaré non fiable si reproductible.

### Android sans capteur

Céleste doit fonctionner avec astronomie + météo, sans erreur ni écran vide.

### iOS

L’état luminosité doit rester explicitement indisponible si aucune API publique fiable n’expose cette donnée.

### Critères

- obscurité à midi ne crée jamais la nuit ;
- forte lumière nocturne peut diminuer étoiles/Lune visuellement, sans changer l’état astronomique ;
- mesure absente/périmée n’est pas présentée comme actuelle.

## 8. Scénarios ORIENTATION

Tester :

- Nord, Est, Sud, Ouest ;
- rotation lente ;
- rotation rapide ;
- proximité de métal/perturbation magnétique ;
- orientation absente/non qualifiée ;
- reprise après retour à une mesure fiable.

### Critères

- cap fiable : point de vue suit le téléphone ;
- cap non fiable : **mode Nord stable** ;
- aucun saut aléatoire de 180° ;
- aucun ciel inventé ;
- Soleil/Lune restent disponibles si le GPS et l’éphéméride sont qualifiés.

## 9. Scénarios GPS

Tester :

- permission accordée ;
- permission refusée ;
- GPS momentanément perdu ;
- position ancienne ;
- position imprécise ;
- retour d’une nouvelle position qualifiée ;
- déplacement suffisant pour changer de cellule météo.

### Critères

- pas de faux ciel local précis sans GPS qualifié ;
- une mauvaise nouvelle mesure ne remplace pas silencieusement une bonne position encore exploitable ;
- reprise propre quand une position valide revient.

## 10. Scénarios SYSTÈME

Tester :

- démarrage à froid ;
- retour après mise en arrière-plan ;
- arrêt/reprise ;
- perte réseau ;
- retour réseau ;
- mode économie d’énergie ;
- pression mémoire raisonnablement reproductible ;
- changement portrait/paysage ;
- multi-fenêtre Android si supporté.

### Critères

- pas de crash ;
- pas de fuite visuelle d’un ancien lieu/météo ;
- caches reconstruits proprement ;
- aucune incidence sur les autres onglets ;
- Céleste se désabonne des capteurs lorsqu’il n’est plus affiché.

## 11. Performance et batterie

Mesurer au minimum sur Android entrée/milieu/haut de gamme et iPhone ancien/récent :

- fluidité visuelle lors d’une rotation continue ;
- consommation CPU approximative via outils système ;
- mémoire avant/après plusieurs entrées/sorties de l’Accueil ;
- absence de croissance mémoire continue ;
- température excessive ;
- comportement en économie d’énergie.

### Critères bloquants

- fuite mémoire reproductible ;
- cache bitmap non libéré ;
- freeze ou jank majeur ;
- activité capteur persistante hors Accueil ;
- consommation anormalement élevée en arrière-plan ;
- dégradation de Pointage ou Salaire.

## 12. Lisibilité et accessibilité

Vérifier :

- contraste texte jour/nuit/crépuscule ;
- contraste avec ciel couvert ;
- taille et lisibilité ;
- VoiceOver/TalkBack ;
- description du mode orienté ou Nord stable ;
- aucun nuage/ciel ne masque un contrôle critique ;
- navigation Accueil réapparaît comme prévu après interaction.

## 13. Non-régression métier

Sur chaque plateforme :

1. ouvrir Céleste ;
2. quitter vers Pointage/Aujourd’hui ;
3. effectuer les opérations normales disponibles ;
4. ouvrir Salaire ;
5. revenir à Accueil.

Résultat attendu :

- aucune modification des calculs de temps ;
- aucune modification des règles Salaire ;
- aucune erreur Firebase provoquée par Céleste ;
- aucun capteur Céleste ne reste actif sans consommateur.

## 14. Classification des anomalies

### BLOQUANT

- crash ;
- faux ciel présenté comme réel ;
- mauvaise position Soleil/Lune reproductible ;
- orientation inventée ;
- météo d’un ancien lieu affichée comme actuelle ;
- régression Pointage/Salaire ;
- fuite mémoire ou batterie majeure ;
- divergence fonctionnelle Android/iOS non justifiée par une capacité matérielle.

### MAJEUR

- transition visuelle brutale ;
- constellation incorrectement projetée ;
- fallback trompeur ;
- lisibilité insuffisante ;
- performance nettement insuffisante sur appareil officiellement supporté.

### MINEUR

- imperfection esthétique sans erreur de vérité, de lisibilité ou de performance.

## 15. Condition de clôture

Céleste peut être considéré conforme uniquement lorsque :

- tous les scénarios bloquants sont PASS ;
- aucune anomalie BLOQUANTE ou MAJEURE n’est ouverte ;
- Android et iOS ont au moins un rapport matériel réel complet ;
- les appareils faibles ne sont pas exclus : seule la qualité graphique peut être réduite ;
- la CI technique est verte hors éventuel verrou externe identifié ;
- la revue multi-agents exigée par HoraTrack a réellement été produite ;
- la validation visuelle finale a été acceptée ;
- la version installable testée correspond au SHA exact destiné à la publication.

## 16. Format du rapport

Utiliser le modèle JSON :

`docs/celestial-real-device-validation-v2.template.json`

Chaque rapport doit conserver :

- le SHA testé ;
- l’appareil ;
- l’OS ;
- chaque scénario ;
- PASS / FAIL / NOT_RUN ;
- preuve/notes ;
- anomalie associée si FAIL ;
- date de validation.

Aucun résultat non exécuté ne doit être transformé en PASS.
