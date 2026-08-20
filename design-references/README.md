# Bibliothèque graphique HP Travail

Ce dossier contient uniquement des **références de design** destinées au développement de l'application.

Ces fichiers ne doivent pas être placés dans `app/src/main/res` tant qu'ils ne sont pas explicitement validés pour être utilisés dans l'APK.

## Arborescence

```text
design-references/
├── README.md
├── themes/
│   ├── diamond/
│   │   ├── buttons/
│   │   │   ├── entry/
│   │   │   ├── pause/
│   │   │   ├── exit/
│   │   │   ├── generic/
│   │   │   └── popup/
│   │   ├── clock/
│   │   ├── widget/
│   │   ├── panels/
│   │   ├── icons/
│   │   └── notes/
│   ├── carbon/
│   │   ├── buttons/
│   │   ├── clock/
│   │   ├── widget/
│   │   ├── panels/
│   │   ├── icons/
│   │   └── notes/
│   └── _template/
│       ├── buttons/
│       ├── clock/
│       ├── widget/
│       ├── panels/
│       ├── icons/
│       └── notes/
├── shared/
│   ├── celestial/
│   │   ├── sun/
│   │   ├── moon/
│   │   ├── earth/
│   │   └── eclipses/
│   ├── materials/
│   ├── lighting/
│   ├── typography/
│   └── motion/
└── archive/
```

## Convention de nommage

Chaque référence doit avoir un nom explicite :

- `entry_v01_reference.png`
- `entry_v02_brighter_edges.png`
- `clock_v03_earth_centered.png`
- `moon_v02_phase_lighting.png`
- `popup_confirm_v01.png`

Quand une image est validée comme référence officielle, ajouter `_approved` :

- `entry_v04_approved.png`

## Fichier de notes associé

Pour les designs complexes, ajouter un fichier Markdown portant le même nom :

- `entry_v04_approved.png`
- `entry_v04_approved.md`

Le fichier `.md` décrit les couches à reproduire dans le code : géométrie, matière, relief, éclairage, ombre, reflet, mouvement, états pressé/désactivé, etc.

## Règle importante

Le dossier `design-references/` sert de bibliothèque privée au projet. Il ne doit pas être utilisé directement comme ressource Android sans décision explicite. Le code de l'application doit reproduire ou exploiter la référence seulement après validation du design.
