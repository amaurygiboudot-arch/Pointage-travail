# Céleste — masquage des onglets sans saut de mise en page

## Reprise du 25 septembre 2026

Base vérifiée : `e2e9cad6bc1e3acd5ec15dfb69b26bbe1060c8ed`, après #434.
Observation d'Amaury : « Ça sautille un peu quand les onglet disparaissent ».

## Défaut et correction ciblée

Android : `MainActivity.hideHomeTabsRunnable` terminait le fondu par `navigationTabs.visibility = View.GONE`. Cette opération retirait l'espace occupé par la navigation et déclenchait une nouvelle disposition du panneau céleste. Le correctif utilise `View.INVISIBLE` : même espace mesuré, seuls l'affichage et la participation de la vue masquée aux interactions changent. Le délai de dix secondes, les durées de fondu, la réapparition au toucher et la restauration hors Accueil sont conservés. Le changement Android actif est limité à cette affectation et son commentaire ; aucun déplacement compensatoire du globe n'est ajouté.

iOS : l'Accueil alternait `.toolbar(.visible/.hidden, for: .tabBar)` alors que le centre du cadran dépend de la hauteur du `GeometryReader`. Le correctif conserve la barre système dans la disposition et applique un fondu d'opacité via `HomeTabBarFadeV2`, sur la barre du contrôleur parent uniquement. Il ne crée aucune seconde navigation, ne devine aucune hauteur et ne modifie ni frame ni safe-area. Les contrôles invisibles sont désactivés pour le toucher et VoiceOver. Le contrôleur annule seulement son propre animateur, préserve la valeur intermédiaire lors d'une interruption, respecte la réduction des animations et restaure l'état initial de la barre à la sortie/désinstallation du contrôleur.

La suppression du changement de disposition traite un mécanisme démontré par le code. L'observation précise sur téléphone n'a pas été enregistrée ni mesurée ici ; la fin de tout sautillement doit encore être confirmée sur appareil.

## Acquis préservés

Terre au pivot exact des aiguilles, compensation vers le nord réel de #434, absence des guides blancs, étoiles, dôme, Soleil/Lune et horizon, nuages et données météo. Aucune modification de leurs moteurs, du calcul de paie, du pointage ou des données utilisateur. Même principe de stabilité Android/iOS pour tous les utilisateurs et appareils supportés, sans exception par marque ou modèle.

## Vérifications locales

- Les copies complètes des deux consommateurs ont été recroisées avec les blobs de la base : MainActivity `26b58556...`, CelestialHomeView `eecb44cf...`.
- 4 tests XCTest de contrats structurels exécutés dans un package Linux isolé : 0 échec sur les sources corrigées.
- Contre-épreuve sur les sources d'origine : les tests Android/iOS de conservation de l'espace échouent ; après restauration du correctif, les 4 tests réussissent à nouveau.
- Analyse syntaxique Swift des deux fichiers de production modifiés/ajoutés réussie. Ce n'est pas un build contre UIKit/SwiftUI.
- 4 tests JUnit de contrats structurels ajoutés pour la CI Android. Leur exécution locale n'est pas revendiquée.

Ces tests vérifient les frontières d'intégration et empêchent la réintroduction des opérations de retrait de layout ciblées. Ils ne simulent pas un écran, ne mesurent pas les images par seconde et ne remplacent pas des tests UIKit/Android sur matériel. Builds mobiles et régression complète : à lire sur le nouveau SHA dans la CI.

## Livraison et essai

Le déclencheur DEV existant est inclus dans ce lot pour construire et signer APK/AAB dans l'emplacement unique `dev-latest` après fusion. Vérifier version et SOURCE_SHA après publication avant de proposer l'installation. Aucun changement de workflow, secret, signature, protection, Firebase ou store.

L'autorisation humaine de fusion des lots de travail pendant l'indisponibilité des agents reste applicable. Une revue absente, un quota ou un échec n'est jamais transformé en PASS ; les contrôles techniques GitHub sont conservés.

Essai : laisser les onglets disparaître, toucher pour les faire revenir, répéter plusieurs fois, puis quitter/revenir sur Accueil. Le cadran et la Terre ne doivent ni sauter ni changer de taille à cause de la navigation. Vérifier aussi rotation écran, reprise, appareils à petite hauteur et réduction des animations ; aucun nouveau réglage manuel n'est requis.

## Références techniques consultées

- Android View / INVISIBLE : https://developer.android.com/reference/android/view/View#INVISIBLE
- Apple UIView / alpha : https://developer.apple.com/documentation/uikit/uiview
- Apple UIViewController / tabBarController : https://developer.apple.com/documentation/uikit/uiviewcontroller/tabbarcontroller
- Apple UIViewControllerRepresentable / dismantleUIViewController : https://developer.apple.com/documentation/swiftui/uiviewcontrollerrepresentable

Ce checkpoint est celui du correctif ; il ne remplace pas le document maître complet ni une validation visuelle finale.
