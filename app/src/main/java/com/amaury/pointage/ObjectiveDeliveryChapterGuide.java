package com.amaury.pointage;

/** Read-only instructions for the existing ten chapter state machines. */
final class ObjectiveDeliveryChapterGuide {
    static final class Step {
        final String phase;
        final String instruction;
        Step(String phase, String instruction) {
            this.phase = phase;
            this.instruction = instruction;
        }
    }
    static final class Chapter {
        final int number;
        final String title;
        final String goal;
        final String risk;
        private final Step[] steps;
        Chapter(int number, String title, String goal, String risk, Step... steps) {
            this.number = number;
            this.title = title;
            this.goal = goal;
            this.risk = risk;
            this.steps = steps.clone();
        }
        int stepCount() { return steps.length; }
        Step step(int index) { return steps[index]; }
        int indexOf(String phase) {
            for (int i = 0; i < steps.length; i++) {
                if (steps[i].phase.equals(phase)) return i;
            }
            throw new IllegalArgumentException("Unknown chapter phase: " + number + "/" + phase);
        }
    }
    private static Step step(String phase, String instruction) { return new Step(phase, instruction); }
    private static Step result() { return step("RESULT", "Lis le bilan et les actions proposées. En entraînement, conserve le résultat réussi pour l’appliquer ; sinon, ajuste tes choix et réessaie."); }
    private static final Chapter[] CHAPTERS = {
        new Chapter(1, "Premier contact", "Comprendre la demande et obtenir une première commande.",
            "Un besoin incomplet fragilise le prix et le délai promis.",
            step("QUALIFICATION", "Échange avec le client et vérifie ses besoins avant de chiffrer."),
            step("OFFER", "Choisis un prix et un délai compatibles avec la demande."),
            step("RESULT", "Lis le bilan. En entraînement, utilise RETOUR À LA CAMPAGNE ; ce rejeu ne remplace pas ta campagne.")),
        new Chapter(2, "Construire le devis", "Faire accepter une offre réalisable et rentable.",
            "Une remise peut gagner la vente tout en réduisant la marge.",
            step("QUOTE", "Compare les coûts, puis prépare le prix et le délai du devis."),
            step("NEGOTIATION", "Réponds au client en pesant la marge et l’engagement de délai."), result()),
        new Chapter(3, "Passer le relais", "Transmettre un dossier complet avant le lancement.",
            "Une option oubliée peut provoquer une reprise en production.",
            step("REVIEW", "Vérifie devis signé, spécifications, options, livraison et date promise."), result()),
        new Chapter(4, "Organiser l’équipe", "Adapter les compétences et les moyens à la commande.",
            "Un renfort coûte de l’argent et ne remplace pas une compétence manquante.",
            step("PLAN", "Répartis les tâches, puis compare les besoins de recrutement et les décisions de reconnaissance."), result()),
        new Chapter(5, "Préparer les matières", "Réunir les matières nécessaires dans un délai maîtrisé.",
            "Une équipe disponible ne peut pas produire sans matières.",
            step("PROCUREMENT", "Compare le stock, le délai fournisseur et les solutions de remplacement."), result()),
        new Chapter(6, "Fabriquer et contrôler", "Préparer une commande conforme pour l’expédition.",
            "Expédier un défaut connu peut bloquer la livraison ou coûter une correction.",
            step("PLANNING", "Organise la production et choisis les moyens de réalisation."),
            step("QUALITY_CONTROL", "Vérifie les critères de la commande avant le feu vert."),
            step("CORRECTION", "Traite l’écart détecté et vérifie la correction ou l’accord du client."), result()),
        new Chapter(7, "Livrer et écouter le client", "Faire réceptionner la commande et résoudre le retour client.",
            "Un colis expédié n’est pas encore une commande acceptée.",
            step("DELIVERY_PLAN", "Choisis un transport et un créneau adaptés à la commande."),
            step("ORDER_CHECK", "Vérifie la commande et ses documents avant le départ."),
            step("CUSTOMER_CLAIM", "Écoute le client, vérifie les faits et organise le suivi."), result()),
        new Chapter(8, "Faire vivre l’entreprise et l’équipe", "Concilier activité, budget et confiance de l’équipe.",
            "Des décisions mal expliquées peuvent créer des tensions durables.",
            step("WEEK_PLAN", "Prépare la semaine et compare charge, moyens et objectifs."),
            step("TEAM_EVENTS", "Traite les événements de l’équipe et les demandes individuelles."),
            step("ANNUAL_REVIEW", "Prépare les décisions de reconnaissance dans le budget disponible."), result()),
        new Chapter(9, "Protéger l’activité", "Corriger les écarts et obtenir une réouverture vérifiée.",
            "Reprendre avant vérification peut aggraver l’arrêt de l’activité.",
            step("INSPECTION", "Consulte les constats de la visite et les faits du dossier."),
            step("SAFETY_ACTION", "Sécurise le poste concerné et décide de la réponse à l’alerte."),
            step("CORRECTION", "Organise la correction, les preuves et la vérification."), result()),
        new Chapter(10, "Piloter sous pression", "Préserver les engagements et la trésorerie face aux aléas.",
            "Accélérer une commande peut consommer les moyens nécessaires aux autres.",
            step("PRESSURE_PLAN", "Compare priorités, approvisionnement, capacité et communication client."), result())
    };
    static Chapter chapter(int number) {
        if (number < 1 || number > CHAPTERS.length) throw new IllegalArgumentException("Unknown chapter: " + number);
        return CHAPTERS[number - 1];
    }
}
