# Céleste — cadran sphérique et étoiles vivantes

Date : 25 septembre 2026. Base contrôlée : `5ef5d4c008475736a58ecaead83e452c9b776fbf` (après #430 et #432).

## Décision utilisateur et portée

Amaury a validé le rendu sphérique puis demandé son intégration dans l'application : suppression des liens entre étoiles, palette blanc/blanc bleuté, tailles et intensités différenciées, scintillement discret. Soleil et Lune restent dans le cadran, avec leurs graphismes et leurs positions astronomiques ; pas de grand Soleil ou de Lune ajoutés dans le fond. L'horizon local reste à **0°**.

Le lot concerne Android et iOS, tous métiers, catégories et appareils officiellement supportés. Il ne change ni Pointage, ni Salaire, ni Firebase, ni les permissions, les signatures ou les protections du dépôt. La maquette générée est une intention esthétique, pas une capture de l'application ni une éphéméride ; le paysage, la Voie lactée décorative et le Soleil extérieur de cette maquette ne sont pas intégrés comme données réelles.

## Intégration effectuée

- `CelestialDomeV2` projette des vecteurs de la sphère locale Est/Nord/Zénith sur une vue orthographique inclinée. Les trajectoires et la grille sont réellement courbes ; tourner le cap n'est plus une rotation 2D rigide d'un disque. L'inclinaison graphique de 35° est un choix de présentation, pas une mesure de capteur.
- La sphère est transparente : un astre derrière le téléphone n'est pas supprimé. L'horizon reste défini par son altitude locale, pas par le bord de l'écran ou le signe de la profondeur graphique.
- Les projections actives des étoiles du cadran et des astres consomment le même propriétaire géométrique. Android raccorde effectivement le renderer de ciel interne dans `HpAnalogClockView`, après le fond et avant les aiguilles et la Terre. iOS raccorde la présentation `.dial` et l'adaptateur `projectSpherical` dans son cadran existant.
- Le rayon de projection réserve une marge pour les symboles existants. Éphémérides, phase, distances Soleil/Lune et géométrie physique des éclipses ne sont pas recalculées par le décor. Android adapte l'orientation du limbe à la différentielle du nouveau repère.
- Les deux rendus étoilés ne dessinent plus aucun chemin de constellation, dans le fond comme dans le cadran. Les métadonnées du catalogue ne sont pas supprimées aveuglément. La grille discrète du dôme est une grille de coordonnées, jamais une liaison entre étoiles.
- `CelestialStarAppearanceV2` définit la même politique Kotlin/Swift : cœur blanc, halo blanc froid (243/247/255), rayon et luminance gradués depuis la magnitude visuelle du catalogue, scintillement déterministe propre à chaque identifiant. Pas de position aléatoire ni de distance inventée.
- Un cœur plus petit, un halo progressif et une modulation bornée remplacent les pastilles uniformes. Seules les étoiles les plus lumineuses scintillent ; la réduction des animations et la qualité réduite produisent un rendu stable.
- La visibilité jour/nuit/météo reste celle du propriétaire canonique. Le fond nuage est toujours dessiné avant les étoiles pour ne pas multiplier deux atténuations météo.

## Horizon et disparition progressive

Le plan géométrique local est à 0°. Cela ne signifie pas « supprimer le symbole dès que son centre géométrique passe sous zéro » : le modèle existant distingue altitude géométrique, réfraction standard et seuil du disque au lever/coucher.

Le propriétaire `CelestialHorizonTransitionV2` existant est conservé. La projection des disques reste disponible pendant toute sa plage d'opacité positive. Les tests parcourent le seuil et 0° pour vérifier la continuité et l'absence de découpage anticipé. Aucune règle arbitraire de visibilité des disques jusqu'à −8° n'est ajoutée. Une lueur crépusculaire n'est pas un disque solaire visible.

L'observation « la Lune a disparu » n'est pas attribuée sans preuve à une cause unique. Une perte de localisation qualifiée reste un état distinct et ne doit pas devenir un faux ciel actuel. La validation téléphone doit encore distinguer horizon, qualité GPS, reprise et couche graphique.

## Budget de rendu et cycle de vie

Les positions locales restent préparées hors du thread d'interface, au rythme existant de 30 secondes. Android met en cache les étoiles faibles du panorama et anime seulement la population brillante à 10/20 Hz selon qualité, avec un seul rappel en attente. Le cadran réutilise la cadence de son horloge, sans nouvel abonnement GPS/capteurs. La grille Android est cachée selon taille et cap.

Les publications asynchrones devenues obsolètes sont rejetées. Le panorama garde son dernier cache récent du même lieu pendant sa reconstruction, plutôt que d'afficher un vide entre deux résultats. Un cache d'un autre lieu n'est jamais réutilisé. Les bitmaps déjà publiés sont libérés par référence au lieu d'être recyclés pendant qu'une liste graphique pourrait encore les utiliser.

iOS sépare les étoiles faibles du `TimelineView` des étoiles brillantes, suspend ce dernier hors scène active/visibilité et annule la préparation devenue inutile. Aucun travail ne demande un accès réseau astronomique ou n'envoie une localisation à un fournisseur de catalogue.

Ces précautions ne sont pas une mesure de batterie ou de fluidité sur appareils réels.

## Contrôles locaux exécutés

- Compilation du noyau Kotlin/JVM et Swift/Linux.
- 7 797 lignes appariées de géométrie et de style : écart absolu maximal `7.216449660063518e-16`, tolérance `1e-9`.
- 9 XCTest du noyau dans un package isolé portant le module `CelestialV2Contract` : 0 échec.
- Analyse syntaxique des sources Swift modifiées ; aucune compilation locale contre SwiftUI/SDK iOS n'est revendiquée.
- Avant modification, les copies locales complètes de `HpAnalogClockView.kt` et `CelestialHomeView.swift` ont été comparées aux empreintes Git de la base, afin de conserver les parties non concernées.

Le dépôt reçoit également 9 tests JUnit du noyau, 4 tests Swift de raccord du cadran et 20 tests Android de géométrie/compatibilité. Les anciennes attentes de projection plate sont remplacées par les attentes de la sphère approuvée ; les scénarios de cap stabilisé, tangage, roulis, orientation Est, marge et réfraction sont conservés. Leur exécution complète et les builds mobiles restent à établir par la CI sur le commit de ce lot, pas par les succès d'une PR précédente.

## Validation de livraison

Le fichier déclencheur DEV est mis à jour dans le même lot afin que la fusion appelle le workflow existant de tests, compilation/signature APK+AAB et publication **dev-latest**. Aucune modification du workflow, aucun store et aucun déploiement Firebase.

La décision humaine d'Amaury autorise les fusions de travail lorsque les agents sont indisponibles ; un quota ou une absence de revue n'est jamais converti en PASS. Les contrôles techniques exigés par GitHub restent en place. Une APK installable ne vaut pas validation graphique finale.

À contrôler sur Android et iOS : étoiles sans liens, palette, halo et scintillement à vitesse normale ; rotation du cap 0/360°, ciel opposé au cap ; astres proches de l'horizon ; absence de doubles astres hors cadran ; cadran/aiguilles/Terre préservés ; mode sombre/jour, nuages, réseau coupé, GPS non qualifié, passage arrière-plan/reprise, taille paysage/tablette, économie d'énergie et mémoire.

## Références et distinction entre données et présentation

Le catalogue BSC5 embarqué fournit ici position et magnitude apparente, pas une distance individuelle exploitable. Une étoile brillante ne signifie pas automatiquement « plus proche ». Les rayons graphiques ne représentent pas des diamètres physiques mesurés.

- US Naval Observatory, définitions lever/coucher et horizon : https://aa.usno.navy.mil/faq/RST_defs
- NASA StarChild, scintillement atmosphérique : https://starchild.gsfc.nasa.gov/docs/StarChild/questions/question26.html
- Android, Canvas : https://developer.android.com/reference/android/graphics/Canvas
- Apple, GraphicsContext : https://developer.apple.com/documentation/swiftui/graphicscontext

Angle de caméra, marges, palette réduite, courbe de luminance et amplitude du scintillement sont des choix graphiques explicites. Ce document est le checkpoint du lot ; il ne remplace pas une mise à jour complète du document maître et ne déclare pas le rendu physique validé.
