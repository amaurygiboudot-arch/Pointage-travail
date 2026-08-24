# Programmation automatique des pauses Android

HoraTrack n'utilise pas la permission spéciale `SCHEDULE_EXACT_ALARM`.

Les heures de début et de fin automatiques sont planifiées avec une alarme Android compatible Doze mais **inexacte**. Android peut donc décaler légèrement le déclenchement pour économiser la batterie.

Ce choix est volontaire : une pause automatique de HoraTrack n'est pas une alarme critique destinée à réveiller l'utilisateur et ne justifie pas l'autorisation spéciale d'alarme exacte imposée par les versions Android récentes et par les politiques de distribution.

Pour limiter les écarts, `PauseScheduleManager.applyCurrentWindow()` réconcilie l'état avec l'heure réelle dès que le flux applicatif repasse par la gestion de la session. Les pointages manuels restent disponibles si une précision à la minute exacte est nécessaire.
