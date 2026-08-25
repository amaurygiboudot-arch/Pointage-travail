# Programmation automatique des pauses Android

HoraTrack n'utilise pas la permission spéciale `SCHEDULE_EXACT_ALARM`.

Les heures de début et de fin automatiques sont planifiées avec une alarme Android compatible Doze mais **inexacte**. Sur les versions Android récentes, une alarme inexacte peut être livrée avec un décalage pouvant atteindre environ une heure dans les conditions normales, et les restrictions de batterie du constructeur peuvent parfois la retarder davantage. Une courte fenêtre de pause peut donc être entièrement dépassée avant que l'alarme initialement prévue soit livrée.

Ce choix est volontaire : une pause automatique de HoraTrack n'est pas une alarme critique destinée à réveiller l'utilisateur et ne justifie pas l'autorisation spéciale d'alarme exacte imposée par les versions Android récentes et par les politiques de distribution.

Pour éviter qu'une alarme retardée applique une action devenue fausse, `PauseScheduleReceiver` ne démarre ou ne termine jamais une pause en se fiant uniquement à l'intention historique START/END. À chaque livraison, `PauseScheduleManager.applyCurrentWindow()` recalcule l'état attendu avec l'heure réelle puis la prochaine programmation est réarmée. Ainsi, un START reçu après la fin de la fenêtre ne crée pas une pause artificielle jusqu'au lendemain.

Les pointages manuels restent disponibles lorsqu'une précision à la minute exacte est nécessaire.
