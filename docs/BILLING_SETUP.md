# HoraTrack — achats intégrés

## Offres validées

| Produit | Identifiant Google Play | Type | Prix cible France |
|---|---|---|---|
| Premium | `horatrack_premium` | Abonnement, plans mensuel et annuel | 4,99 €/mois ; 49,99 €/an |
| Premium avec analyses | `horatrack_plus` | Abonnement, plans mensuel et annuel | 9,99 €/mois ; 99,99 €/an |
| Analyse ponctuelle | `horatrack_analysis` | Achat consommable | 4,99 € |
| PDF | `horatrack_pdf` | Achat consommable | 0,99 € |

Les montants sont les prix cibles approuvés, pas des produits déjà créés dans Play Console. Le client affiche les prix et périodes renvoyés par Google Play. L'application reste gratuite à télécharger.

Un abonnement Plus donne droit à une analyse par mois. L'offre Plus et l'analyse ponctuelle restent indisponibles à l'achat tant que le service d'analyse correspondant n'est pas raccordé et testé. Ne pas vendre un service indisponible.

Dans cette première intégration, les PDF et leur archive sont raccordés aux droits Premium. Les autres avantages envisagés (sans publicité, statistiques avancées, sauvegarde Premium) ne doivent pas être annoncés comme des exclusivités payantes tant que leurs consommateurs ne sont pas raccordés aux droits serveur.

## PDF

Pas d'aperçu avant autorisation serveur. La préparation des octets dans le cache privé ne constitue pas une autorisation de visualisation, d'export ou de partage. Une empreinte SHA-256 des octets identifie le document acheté. Les mêmes octets restent accessibles sans nouvel achat, y compris après la résiliation de Premium. Un document modifié a une nouvelle empreinte.

Premium inclut les PDF. Le compte propriétaire est exempté seulement sur décision serveur ; un réglage local ou un email déclaré par le client ne suffit pas. Aucun paiement en attente n'accorde de droit. Les annulations/remboursements doivent être vérifiés auprès de Google avant d'accorder un accès.

Les données de pointage et les sauvegardes de données brutes ne sont pas supprimées ni bloquées par l'expiration d'un abonnement. Un export PDF automatique en arrière-plan ne lance jamais une fenêtre d'achat.

L'archive des octets PDF est conservée sur l'appareil, par compte connecté. La restauration des droits Google Play ne restaure pas les fichiers perdus après désinstallation ou changement d'appareil. Ne pas promettre une archive PDF cloud tant que cette synchronisation n'est pas intégrée.

## Raccordement avant activation

1. Créer les produits et les plans dans Google Play Console, renseigner leurs prix et activer les offres. Tester sur la piste Google Play existante avec des testeurs de licence.
2. Activer Google Play Android Developer API et accorder au compte de service du serveur les seules permissions nécessaires à la vérification et au traitement des achats de `com.amaury.pointage`. Aucune clé privée n'est intégrée à l'application.
3. Déployer les Functions de paiement avec l'authentification Firebase et App Check. Les collections de droits doivent rester inaccessibles en écriture aux clients.
4. Vérifier le statut propriétaire dans le stockage serveur protégé. Ne jamais ajouter une exemption basée sur un bouton, un email client ou un identifiant matériel.
5. Tester achat accepté, paiement en attente, annulation utilisateur, compte différent, changement de téléphone, relance après achat, remboursement, expiration et re-téléchargement du même PDF.
6. Activer les achats uniquement après validation du serveur, de la version Google Play et de la chaîne de contrôle du dépôt.

Le déploiement, la création des produits, l'attribution des permissions et l'activation du compte propriétaire ne sont pas accomplis par la simple présence de ce code. Les achats iOS nécessitent un raccordement StoreKit et une vérification serveur propre à Apple ; sans ce raccordement, les PDF restent explicitement indisponibles à l'export.

## Sources techniques

- https://developer.android.com/google/play/billing/integrate
- https://developer.android.com/google/play/billing/security
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.productsv2/getproductpurchasev2
- https://developers.google.com/android-publisher/api-ref/rest/v3/purchases.subscriptionsv2/get

## État de publication

Les prix et le comportement ont été approuvés par l'utilisateur. L'intégration est préparée avant publication publique. Ne pas présenter les paiements comme opérationnels tant que les étapes externes et les essais réels ne sont pas terminés.
