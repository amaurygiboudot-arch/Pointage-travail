# Transfert de confort Android/iOS

Le partage est manuel et porte uniquement sur trois préférences consommées sur les deux plateformes. Il n’ajoute pas de synchronisation cloud ni de transmission automatique.

Dans Personnalisation, utiliser « Partager le confort Android/iOS », copier le texte transmis et utiliser « Coller le confort Android/iOS » sur l’autre appareil. Vérifier l’aperçu avant de confirmer. Les exports natifs complets restent disponibles séparément.

## Contrat

```json
{"format":"agkgmg.comfort","version":1,"highContrast":true,"reduceMotion":false,"readerScale":2.25}
```

Tous les champs sont obligatoires. Les champs inconnus, types incorrects, versions incompatibles et documents dépassant 4 096 octets UTF-8 sont refusés. Le zoom est un nombre fini compris entre 1 et 4. Aucun identifiant de compte, texte saisi, brouillon, salaire ou pointage n’est inclus.

Les trois champs sont appliqués au profil courant seulement après confirmation. Le thème, la taille native, le contexte actif, la correction de saisie et les autres préférences restent conservés. Le mode économie ou une préférence système d’accessibilité peut continuer à réduire les mouvements même si la préférence importée vaut false. Un changement de compte invalide l’import en attente.

Le transfert reproduit les préférences, pas un rendu pixel pour pixel entre Android et iOS. Les tests contractuels utilisent le même exemple, rejettent des entrées malformées et vérifient la conservation des préférences locales.
