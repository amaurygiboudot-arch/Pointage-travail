import SwiftUI

struct SalaryV2View: View {
    @EnvironmentObject private var salaryStore: SalaryV2Store

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    periodSelector
                    reliabilityCard
                    referenceCard
                    incomeTaxCard
                    warningsCard
                }
                .padding()
            }
            .navigationTitle("Salaire")
        }
    }

    private var periodSelector: some View {
        HStack {
            Button {
                salaryStore.moveMonth(by: -1)
            } label: {
                Image(systemName: "chevron.left.circle.fill")
                    .font(.title2)
            }
            .accessibilityLabel("Mois précédent")

            Spacer()
            Text(periodLabel(salaryStore.selectedPeriod))
                .font(.headline)
            Spacer()

            Button {
                salaryStore.moveMonth(by: 1)
            } label: {
                Image(systemName: "chevron.right.circle.fill")
                    .font(.title2)
            }
            .accessibilityLabel("Mois suivant")
        }
    }

    private var reliabilityCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("SALAIRE V2")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            Text(salaryStore.snapshot.sourceReady ? "Sources amont détectées" : "Sources amont à raccorder")
                .font(.title3.bold())
            Text("HoraTrack n'affiche aucun montant de remplacement lorsque les données nécessaires ne sont pas certifiables.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var referenceCard: some View {
        VStack(spacing: 12) {
            amountRow("Brut social estimé", amount: salaryStore.snapshot.socialGross)
            Divider()
            amountRow("Net estimé avant impôt", amount: salaryStore.snapshot.netBeforeIncomeTax)
            Divider()
            amountRow("Net imposable estimé", amount: salaryStore.snapshot.netTaxable)
            Divider()
            amountRow("Prélèvement à la source", amount: salaryStore.snapshot.incomeTax)
            Divider()
            amountRow("Net après impôt", amount: salaryStore.snapshot.netAfterIncomeTax)

            Divider()
            HStack {
                Text("Comparaison bulletin")
                Spacer()
                Text("À confirmer")
                    .fontWeight(.semibold)
            }
            HStack {
                Text("PDF paie")
                Spacer()
                Text("À confirmer")
                    .fontWeight(.semibold)
            }
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var incomeTaxCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("PRÉLÈVEMENT À LA SOURCE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)
            TextField("Taux personnel (%)", text: $salaryStore.incomeTaxRateText)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
            TextField("Source (ex. bulletin confirmé)", text: $salaryStore.incomeTaxSource)
                .textFieldStyle(.roundedBorder)
            Button("Confirmer ce taux pour ce mois") {
                _ = salaryStore.confirmIncomeTaxRate()
            }
            .buttonStyle(.borderedProminent)
            Button("Retirer le taux confirmé de ce mois", role: .destructive) {
                _ = salaryStore.removeIncomeTaxRate()
            }
            if let feedback = salaryStore.incomeTaxFeedback {
                Text(feedback)
                    .font(.footnote)
            }
            Text("Le taux est enregistré uniquement pour le mois affiché et n'est jamais réutilisé silencieusement pour un autre mois.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private var warningsCard: some View {
        if !salaryStore.snapshot.warnings.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("ÉLÉMENTS À VÉRIFIER")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                ForEach(Array(salaryStore.snapshot.warnings.enumerated()), id: \.offset) { _, warning in
                    Text("• \(warning)")
                        .font(.footnote)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    private func amountRow(_ title: String, amount: Double?) -> some View {
        HStack {
            Text(title)
            Spacer()
            Text(amount.map(euros) ?? "À confirmer")
                .fontWeight(.semibold)
        }
    }

    private func euros(_ amount: Double) -> String {
        String(format: "%.2f €", locale: Locale(identifier: "fr_FR"), amount)
    }

    private func periodLabel(_ period: YearMonthV2) -> String {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.year = period.year
        components.month = period.month
        components.day = 1
        guard let date = components.date else { return period.description }

        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "LLLL yyyy"
        return formatter.string(from: date).capitalized
    }
}
