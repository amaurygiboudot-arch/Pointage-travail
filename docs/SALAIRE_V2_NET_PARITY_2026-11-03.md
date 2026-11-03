# Salaire V2 — contrat de projection nette commun, HT-017

Ce correctif porte sur les montants salariés estimés par les routes mensuelle et segmentée Android/iOS. Les comptes, la restauration, les imports et les durées HT-015 conservent leurs propriétaires existants. Une projection identique sur les deux plateformes ne constitue pas une certification de bulletin réel.

## Calcul monétaire

Les espèces, avantages en nature, retenues directes et réintégrations sont normalisés au centime. Chaque cotisation salariale issue des assiettes et taux datés est calculée par produit décimal et arrondie au centime, puis les centimes sont additionnés. Les mêmes montants de CSG/CRDS non déductibles sont retranchés du cash et réintégrés au fiscal. L'arithmétique interne conserve un solde signé avant tout écrêtement.

L'arrondi par ligne est une convention technique explicite de cette projection. Il n'est pas présenté comme une obligation universelle applicable à toutes les cotisations ou à tous les employeurs. La [fiche DSN 100, version du 22 mars 2025](https://net-entreprises.custhelp.com/app/answers/detail_dsn/a_id/100) distingue le format déclaratif des règles de paie qui justifient un arrondi.

Les calculs décimaux utilisent HALF_UP : un demi-centime positif monte au centime suivant. Les conversions et les sommes rejettent les valeurs non finies, les dépassements de la borne technique des entiers exactement représentables et les retours Double qui perdraient un centime. Il ne s'agit pas d'un plafond légal de salaire. Aucun sous-total interne de repli ne devient un résultat salarié complet.

## Fiscal et PAS

Le net imposable est calculé à partir du solde avant écrêtement, augmenté des avantages en nature, CSG/CRDS non déductibles et réintégrations confirmées. Le [BOFiP BOI-IR-PAS-20-10-10, publication du 28 juillet 2020, § 10](https://bofip.impots.gouv.fr/bofip/11249-PGP.html/identifiant=BOI-IR-PAS-20-10-10-20200728) définit l'assiette à partir des revenus et avantages, sous déduction des cotisations déductibles. Une retenue en espèces ne prouve pas à elle seule sa déductibilité fiscale.

Pour avril 2026, la référence aux réintégrations de santé/prévoyance est la [version BOI-RSA-BASE-30-10-20 du 17 février 2026](https://bofip.impots.gouv.fr/bofip/5956-PGP.html/identifiant=BOI-RSA-BASE-30-10-20-20260217), publiée jusqu'au 10 août 2026. Les montants confirmés des tests sont synthétiques, pas des forfaits légaux.

Le PAS individuel est calculé en décimal puis arrondi au centime, demi-centime supérieur : [CGI annexe III, article 39 C, 3° f, en vigueur depuis le 2 juin 2024](https://www.legifrance.gouv.fr/codes/article_lc/LEGIARTI000049642530), corroboré par la [fiche Net-entreprises 1275 du 22 mars 2025](https://net-entreprises.custhelp.com/app/answers/detail_dsn/a_id/1275/p/613). L'arrondi à l'euro du reversement global du collecteur ne s'applique pas au salarié.

## Cas restant explicitement incomplets

- Retenue transport positive : sa qualification fiscale n'est pas décrite par l'entrée actuelle. Le net avant impôt reste calculable, le fiscal/PAS/après impôt reste à confirmer.
- RNF calculée négative : régularisation non couverte par la projection ordinaire ; aucun zéro fiscal fiable n'est publié. La [fiche 1810 du 22 mars 2025](https://net-entreprises.custhelp.com/app/answers/detail/a_id/1810/~/r%C3%A9mun%C3%A9ration-nette-fiscale-n%C3%A9gative) distingue RNF négative et assiette PAS non négative.
- PAS théorique supérieur aux espèces disponibles : PAS effectif et net après impôt inconnus tant que le traitement employeur n'est pas confirmé. Aucun minimum automatique entre impôt et cash, aucun zéro supposé. La [fiche 1940 actuelle, modifiée le 5 octobre 2026](https://net-entreprises.custhelp.com/app/answers/detail_dsn/a_id/1940/kw/Suivi%20dsn/p/675), décrit une tolérance de non-prélèvement ; elle ne prouve pas l'option d'un employeur. Son antériorité est corroborée par des documents secondaires, pas une archive primaire d'avril 2026 récupérée lors de cet audit.
- Solde espèces négatif avant écrêtement : le montant espèces affichable reste limité à zéro selon le contrat existant, avec avertissement explicite. Cela ne prouve pas l'absence d'une dette ou d'une régularisation. Un taux nul confirmé peut produire PAS zéro si la RNF est par ailleurs fiable.
- Entrées manquantes, périodes incohérentes et calculs non représentables : résultats concernés incomplets. Une inconnue fiscale n'efface pas un avant impôt fiable.

## Référence commune

Le fichier unique `ios/HPTravail/HPTravailTests/Fixtures/net-projection-v2.json` est consommé par les tests Android, SwiftPM et XCTest. Ses attentes sont des centimes entiers ou des valeurs nulles, établis avec un calcul décimal indépendant. `expected.complete` désigne exclusivement la complétude du **net avant impôt**.

Exemples synthétiques d'avril 2026, non-cadre, mutuelle 42 €, prévoyance 18 €, réintégrations 8/4 €, protection CSG 12 €, taux PAS 3,2 % :

| Espèces / avantages | Avant impôt | Imposable | PAS | Après impôt |
| --- | ---: | ---: | ---: | ---: |
| 3 000 € / 0 € | 2 313,62 € | 2 411,45 € | 77,17 € | 2 236,45 € |
| 0 € / 1 000 € | 0 € avec avertissement | 771,27 € | À confirmer | À confirmer |

La fixture couvre aussi cadre, régime local, temps partiel, seuils de plafond, demi-centimes, taux nul, données inconnues et limites de couverture. Les tests de valeurs non finies et débordements sont natifs à chaque plateforme. Les validations de paie réelle et les essais de pointage sur téléphone restent distincts.
