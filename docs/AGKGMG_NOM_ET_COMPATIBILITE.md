# AGKGMG — nom du produit et compatibilité

Décision du 2 novembre 2026 : le nom affiché du produit et celui des documents actifs est **AGKGMG**. Les anciens libellés de marque sont remplacés dans la prose documentaire et les surfaces visibles de l’application.

## Documents actifs renommés

| Document | Chemin actif |
| --- | --- |
| Maître et checkpoints | `docs/AGKGMG_MASTER.txt` |
| Audit du système céleste | `docs/audits/AGKGMG_Celestial_System_V2_Audit.md` |
| Fondation céleste iOS | `docs/audits/AGKGMG_Celestial_iOS_Foundation.md` |

Les noms de fichiers cités comme sources historiques dans le maître restent ceux des pièces d’origine. La mise à jour du nom ne modifie aucun SHA, résultat de test, preuve de livraison ou critère métier. La note en tête du maître distingue cette édition des sources originales dont les empreintes sont conservées.

## Références techniques conservées

Ces noms servent à retrouver un objet existant ou des données déjà enregistrées. Ils ne désignent pas la marque à afficher.

| Référence | Raison de conservation |
| --- | --- |
| `HoraTrackV2`, son fichier Kotlin et ses membres | Nom du symbole réellement présent dans le code ; les documents techniques doivent citer une API existante. |
| Identifiants d’achats `horatrack_*` dans `BILLING_SETUP.md` | Références des produits de facturation, distinctes de leurs titres visibles. Un changement demande une migration et une vérification des droits existants. |
| Champ `horatrack_version` du modèle de preuve Céleste | Clé du schéma attendu par les outils de validation ; un renommage isolé rendrait les preuves incompatibles. |
| Noms originaux de DOCX, TXT, ZIP et patchs dans les archives | Provenance vérifiable et correspondance avec les empreintes historiques. |
| Ancien titre de workflow et noms d’artefacts `HoraTrack-dev.*` cités dans l’archive | Description d’exécutions passées, pas réactivation d’un canal de livraison. Google Play reste le canal actuel. |
| Chemins des worktrees historiques cités dans la refonte du pointage | Emplacements de travail réellement observés ; cette modification documentaire ne déplace pas ces autres checkouts. |

Les identifiants persistants de stockage, de sauvegarde, d’installation et de services suivent la même règle : remplacer un libellé visible ne doit ni rendre les données existantes introuvables ni créer une seconde identité technique. Leur éventuelle migration constitue un travail séparé avec reprise des données et validation, pas un remplacement de texte.

## Portée de la vérification documentaire

Les occurrences restantes dans les documents actifs ont été relues et se limitent aux références techniques ou historiques décrites ci-dessus. Les fichiers de gouvernance et les changements Android/iOS sont coordonnés séparément par le chef d’orchestre. Aucun nouveau test métier n’est justifié par cette seule modification de prose.
