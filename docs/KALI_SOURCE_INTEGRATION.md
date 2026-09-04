# Intégration de la source KALI

Cette intégration permet à HoraTrack de vérifier la convention collective d'une
entreprise à partir de son IDCC. Elle ne déduit et n'applique encore aucune règle
de paie issue de la convention.

## Composants

- `LegifranceFunctionClientV2` appelle la fonction Firebase callable
  `legifranceRequest` dans la région `us-central1`.
- `functions/index.js` authentifie l'utilisateur Firebase, obtient un jeton OAuth
  PISTE côté serveur et relaie uniquement les routes Légifrance autorisées.
- `OfficialConventionCatalogParserV2` lit les résultats de `/list/conventions`.
- `OfficialConventionContainerParserV2` vérifie que la réponse de
  `/consult/kaliContIdcc` contient exactement l'IDCC demandé et une référence
  `KALICONT` valide.
- `OfficialConventionResultStoreV2` conserve localement les seules métadonnées
  vérifiées : IDCC, titre, identifiant du conteneur, textes de base et date de
  vérification.

## Flux d'une vérification

1. L'utilisateur ouvre l'entreprise puis **Convention collective (KALI)**.
2. HoraTrack normalise l'IDCC sur quatre chiffres.
3. Le SDK Firebase joint automatiquement l'identité Firebase de l'utilisateur à
   l'appel callable.
4. La fonction refuse les utilisateurs non authentifiés et toute route hors de
   sa liste d'autorisation.
5. La fonction obtient ou réutilise un jeton OAuth PISTE, puis interroge KALI.
6. L'application accepte la réponse uniquement si l'IDCC et l'identifiant
   `KALICONT` correspondent aux valeurs attendues.
7. Les métadonnées sont enregistrées et un lien vers la convention officielle
   peut être ouvert.

## Identifiants et jetons

Les secrets `PISTE_CLIENT_ID` et `PISTE_CLIENT_SECRET` sont des secrets Firebase.
Ils ne sont ni compilés dans l'APK, ni enregistrés dans les préférences Android,
ni renvoyés par la fonction. Le jeton OAuth PISTE reste en mémoire dans l'instance
Firebase et est renouvelé avant son expiration.

Les utilisateurs de HoraTrack n'ont donc pas à créer ou connecter un compte
PISTE. Ils doivent seulement être connectés à HoraTrack pour que Firebase puisse
authentifier l'appel.

## Limite volontaire

Une convention trouvée est marquée comme source vérifiée, mais ses majorations,
dates d'effet, catégories de salariés et règles de cumul restent à analyser et à
valider avant toute utilisation par le moteur de paie. En attendant, HoraTrack
conserve le régime déjà configuré et affiche cette réserve à l'utilisateur.

## Vérifications avant livraison

- Exécuter `npm test --prefix functions`.
- Exécuter `gradle :app:testDebugUnitTest :app:assemblePlay` avec Gradle 8.13.
- Déployer la fonction Firebase.
- Dans l'APK publié, vérifier un IDCC connu, puis un IDCC différent afin de
  confirmer que les réponses incohérentes sont refusées.
