# Transfert de confort Android et iOS

Le transfert reste manuel. Le format actuel `agkgmg.comfort` version 2 transporte le contraste, la réduction des mouvements, le zoom de lecture et la programmation du mode nuit. La sauvegarde du compte utilise le même contrat. Aucune transmission automatique n’est ajoutée.

Dans Personnalisation, partager le texte puis le coller sur l’autre appareil. Vérifier l’aperçu avant de confirmer. Les exports natifs complets restent disponibles séparément.

## Contrat actuel

```json
{"format":"agkgmg.comfort","version":2,"highContrast":true,"reduceMotion":false,"readerScale":2.25,"nightScheduleEnabled":true,"nightStartMinute":1320,"nightEndMinute":420}
```

Tous les champs sont obligatoires. Les champs inconnus, types incorrects, versions incompatibles et documents dépassant 4 096 octets UTF-8 sont refusés. Le zoom est fini et compris entre 1 et 4. Les minutes sont des entiers de 0 à 1439 ; début et fin doivent différer, même quand la programmation est désactivée.

Les horaires sont des heures locales, sans fuseau transporté : 22 h signifie 22 h sur l’appareil destinataire. L’aperçu indique les heures et l’activation avant confirmation. Le contexte manuel et les préférences système d’accessibilité restent prioritaires. Le transfert ne change ni le contexte manuel, ni le thème, ni la taille native, ni la saisie, ni les données métier.

## Compatibilité des sauvegardes

Le format historique version 1 reste accepté :

```json
{"format":"agkgmg.comfort","version":1,"highContrast":true,"reduceMotion":false,"readerScale":2.25}
```

Restaurer une version 1 conserve intégralement la programmation de nuit locale, y compris son activation. Réexporter cet objet sans l’enrichir conserve son format version 1. Un nouvel export du profil courant utilise la version 2. Les anciennes applications qui ne comprennent que la version 1 refuseront la version 2 : mettre à jour l’application destinataire, sans abaisser silencieusement le format.

Au moment de la confirmation, les valeurs sont appliquées au profil alors courant. Un changement de compte invalide la restauration en attente. Aucun identifiant, texte saisi, brouillon, salaire ou pointage n’est transporté. Le fichier documentaire conserve son ancien nom pour préserver les références existantes.
