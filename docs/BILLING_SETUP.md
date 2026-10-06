# HoraTrack — achats intégrés

## Offres validées

| Produit | Identifiant Google Play | Type | Prix cible France |
|---|---|---|---|
| Premium | `horatrack_premium` | Abonnement, plans mensuel et annuel | 4,99 €/mois ; 49,99 €/an |
| Premium avec analyses | `horatrack_plus` | Abonnement, plans mensuel et annuel | 9,99 €/mois ; 99,99 €/an |
| Analyse détaillée du bulletin | `horatrack_analysis` | Achat consommable | 4,99 € |
| Comparaison pointage / bulletin | `horatrack_payslip_comparison` | Achat consommable | 6,99 € |
| Bilan annuel approfondi | `horatrack_annual_review` | Achat consommable | 9,99 € |
| Dossier factuel de réclamation | `horatrack_claim_dossier` | Achat consommable | 14,99 € |
| PDF | `horatrack_pdf` | Achat consommable | 0,99 € |

Les montants sont les prix cibles approuvés, pas des produits déjà créés dans Play Console. Le client affiche les prix et périodes renvoyés par Google Play. L'application reste gratuite à télécharger.

Les quatre prestations ont été approuvées le 6 octobre 2026. Leur prix inclut leur propre rapport PDF, sans supplément de 0,99 €. Le catalogue Android prépare des rapports factuels privés à partir des données confirmées, puis propose les prix réels Google Play. Le déblocage reste soumis à la vérification serveur et aux configurations externes. Le statut de code prêt ne signifie pas que les offres sont déjà actives dans le Store. Premium inclut les exports ordinaires, pas ces prestations approfondies.

Un abonnement Plus prévoit une analyse de bulletin par mois, pas une comparaison, un bilan ou un dossier. La fiche de l’offre doit présenter ce périmètre. Le quota est d’une analyse de bulletin par mois civil UTC, non reportable ; aucune consommation n’a lieu en arrière-plan ou sur une simple restauration. L’utilisateur demande explicitement son utilisation après préparation du PDF. Les anciens crédits achetés sont proposés séparément : le choix de l’un ne consomme jamais l’autre.

Dans cette première intégration, les PDF et leur archive sont raccordés aux droits Premium. Les autres avantages envisagés (sans publicité, statistiques avancées, sauvegarde Premium) ne doivent pas être annoncés comme des exclusivités payantes tant que leurs consommateurs ne sont pas raccordés aux droits serveur.

## PDF

Pas d'aperçu avant autorisation serveur. Avant toute proposition d'achat, les octets et le nom du PDF sont conservés dans un dossier privé durable propre au compte. Cette copie ne constitue pas une autorisation de visualisation, d'export ou de partage. Une empreinte SHA-256 des octets identifie le document acheté. Les mêmes octets restent accessibles sans nouvel achat, y compris après la résiliation de Premium. Un document modifié a une nouvelle empreinte.

Premium inclut les PDF. Le compte propriétaire est exempté seulement sur décision serveur ; un réglage local ou un email déclaré par le client ne suffit pas. Aucun paiement en attente n'accorde de droit. Les annulations/remboursements doivent être vérifiés auprès de Google avant d'accorder un accès.

Les données de pointage et les sauvegardes de données brutes ne sont pas supprimées ni bloquées par l'expiration d'un abonnement. Un export PDF automatique en arrière-plan ne lance jamais une fenêtre d'achat.

L'archive des octets PDF est conservée sur l'appareil, par compte connecté. La restauration des droits Google Play ne restaure pas les fichiers perdus après désinstallation ou changement d'appareil. Ne pas promettre une archive PDF cloud tant que cette synchronisation n'est pas intégrée.

« Mes PDF » donne accès aux copies préparées et aux archives autorisées. Après un paiement différé ou une fermeture du processus, l'utilisateur peut retrouver la copie exacte même si ses pointages ou son bulletin ont changé. Toute ouverture vérifie à nouveau les droits serveur ; une copie privée, un paiement en attente ou un état d'écran sauvegardé ne débloque jamais l'aperçu.

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

## Audit de monétisation

Voir [MONETIZATION_AUDIT.md](MONETIZATION_AUDIT.md) pour les fonctionnalités examinées, les quatre prestations approuvées, les propositions supplémentaires et les conditions de livraison. Les propositions supplémentaires ne constituent pas des tarifs approuvés ni des droits actifs.

## Rapports dédiés et achat Google Play

Le parcours prépare les octets dans un dossier privé propre au compte, sans aperçu. Il vérifie les données nécessaires puis enregistre un manifeste serveur (produit, empreinte des entrées, empreinte du PDF et identifiant idempotent), sans transmettre salaire, bulletin, lieu ou pointages bruts au backend de paiement.

- `billingPrepareReport` : enregistrement du manifeste ; aucune charge et aucun crédit consommé.
- `billingVerifyPurchase` : preuve Google Play liée au compte, au produit et à l’empreinte immuable du PDF via `obfuscatedProfileId`.
- `billingAuthorizeReport` : vérifie le droit propre du rapport. L’analyse Plus exige `usePlusCredit=true`, après une action explicite, et conserve la commande financeuse pour les remboursements.
- `billingAuthorizePdf` : reconnaît les rapports dédiés avant les droits ordinaires. Un abonnement Premium ou un PDF à 0,99 € ne peut pas contourner le prix d’une prestation.

Le compte propriétaire est exempté sur preuve serveur. Les anciens crédits de bulletin déjà prouvés côté serveur doivent rester restaurables. La restauration des achats ne restaure pas les fichiers effacés ; les rapports préparés et achetés restent conservés localement par compte. Une nouvelle génération utilise la même copie pour les mêmes entrées confirmées ; une modification du courrier ou des données constitue un nouveau résultat explicitement préparé avant achat.

La comparaison rapide reste gratuite. Le contrôle payant porte sur neuf familles de montants confirmés au maximum : il ne remplace pas une vérification de toutes les bases, tous les taux ou toutes les lignes d’un bulletin. Les références inconnues sont laissées à confirmer. Le bilan annuel signale les mois non contrôlés et distingue les cumuls partiels ; il exige suffisamment de données exploitables avant achat. Le dossier comporte une chronologie, les écarts et un courrier modifiable, avec une liste de pièces originales à joindre par l’utilisateur, sans envoi automatique.

Les pointages fermés qui traversent une borne de mois utilisent la sélection et la répartition du temps payé du moteur canonique. La chronologie distingue la session complète de la part attribuée au mois, sans double imputation. Une preuve ouverte, contradictoire ou non qualifiée reste bloquante. L’export mensuel Android demande des pointages terminés et fiables avant de préparer une copie durable : une durée calculée depuis l’heure courante ne doit jamais devenir un ancien PDF réutilisé silencieusement. Le choix du fichier public intervient après autorisation ; annulation ou refus ne doit pas créer un document vide avant paiement.

## Essais supplémentaires avant activation

- Quatre achats distincts : produit erroné, empreinte erronée, même jeton réutilisé, paiement en attente, reprise après fermeture, refus réseau et remboursement.
- Premium/PDF ordinaire incapables de déverrouiller un rapport dédié.
- Plus : action explicite seulement, concurrence de deux rapports, même rapport rejoué, limite mensuelle, changement de mois, expiration naturelle et remboursement de la commande financeuse.
- Données insuffisantes : aucun paiement proposé ; couverture réelle et limites affichées avant achat.
- Courrier modifié : nouvelle empreinte ; aucun original de bulletin supprimé ou bloqué.
- PDF ordinaire : paiement différé, fermeture puis modification des données ; la copie préparée reste retrouvable sans régénération ni nouvel achat du même document.
- Export mensuel : rotation ou redimensionnement pendant préparation, paiement, choix d'emplacement et copie ; reprendre les octets exacts et revérifier les droits, sans relancer automatiquement l'achat ni ouvrir un deuxième sélecteur.
- Abonnements actifs : empêcher un deuxième abonnement simultané sans parcours de remplacement vérifié. Le lien de gestion Google Play reste accessible.
