import SwiftUI

struct SalaryV2View: View {
    @EnvironmentObject private var salaryStore: SalaryV2Store
    @EnvironmentObject private var workStore: WorkStoreV2

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    periodSelector
                    companySelectorCard
                    reliabilityCard
                    paidWorkCard
                    referenceCard
                    incomeTaxCard
                    warningsCard
                }
                .padding()
            }
            .navigationTitle("Salaire")
            .onAppear {
                salaryStore.refresh()
            }
            .onChange(of: workStore.sessions) { _ in
                salaryStore.refresh()
            }
            .onChange(of: workStore.storageReliable) { _ in
                salaryStore.refresh()
            }
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

    private var companySelectorCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ENTREPRISE ANALYSÉE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            if !salaryStore.companies.reliable {
                Label("Stockage des entreprises non fiable", systemImage: "exclamationmark.triangle.fill")
                    .font(.headline)
                Text("Aucune entreprise n'est déduite tant que ce stockage n'est pas fiable.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if salaryStore.companies.companies.isEmpty {
                Text("Aucune entreprise confirmée")
                    .font(.headline)
                Text("Ajoutez et confirmez une entreprise avant d'analyser un salaire.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if salaryStore.companies.companies.count == 1,
                      let company = salaryStore.companies.companies.first {
                Label(companyLabel(company), systemImage: "building.2.fill")
                    .font(.headline)
                Text("Entreprise unique : sélection non ambiguë.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Menu {
                    ForEach(salaryStore.companies.companies) { company in
                        Button {
                            _ = salaryStore.selectCompany(company.id)
                        } label: {
                            if company.id == salaryStore.selectedCompanyId {
                                Label(companyLabel(company), systemImage: "checkmark")
                            } else {
                                Text(companyLabel(company))
                            }
                        }
                    }

                    if salaryStore.selectedCompanyId != nil {
                        Divider()
                        Button("Effacer la sélection", role: .destructive) {
                            _ = salaryStore.selectCompany(nil)
                        }
                    }
                } label: {
                    HStack {
                        Image(systemName: "building.2")
                        Text(salaryStore.selectedCompany.map(companyLabel) ?? "Choisir une entreprise")
                            .fontWeight(.semibold)
                        Spacer()
                        Image(systemName: "chevron.up.chevron.down")
                            .font(.caption)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)

                Text("Avec plusieurs employeurs, HoraTrack n'en choisit jamais un à votre place.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
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

    private var paidWorkCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("TEMPS PAYÉ ISSU DU POINTAGE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            if salaryStore.selectedCompanyId == nil {
                Text("À confirmer")
                    .font(.title3.bold())
                Text("Sélectionnez d'abord l'entreprise à analyser.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if let paidWork = salaryStore.paidWork {
                Text(minutesLabel(paidWork.totalPaidMinutes))
                    .font(.title3.bold())
                Text(paidWork.reliable
                     ? "Pointages rattachés explicitement à cette entreprise."
                     : "Total indicatif uniquement : les pointages contiennent au moins une incertitude.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                Text("\(paidWork.completedSessionCount) session(s) retenue(s) pour ce mois")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else {
                Text("À confirmer")
                    .font(.title3.bold())
            }
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

            if salaryStore.selectedCompanyId == nil {
                Text("Sélectionnez une entreprise avant de confirmer un taux.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            }

            TextField("Taux personnel (%)", text: $salaryStore.incomeTaxRateText)
                .keyboardType(.decimalPad)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Source (ex. bulletin confirmé)", text: $salaryStore.incomeTaxSource)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            Button("Confirmer ce taux pour ce mois") {
                _ = salaryStore.confirmIncomeTaxRate()
            }
            .buttonStyle(.borderedProminent)
            .disabled(salaryStore.selectedCompanyId == nil)
            Button("Retirer le taux confirmé de ce mois", role: .destructive) {
                _ = salaryStore.removeIncomeTaxRate()
            }
            .disabled(salaryStore.selectedCompanyId == nil)
            if let feedback = salaryStore.incomeTaxFeedback {
                Text(feedback)
                    .font(.footnote)
            }
            Text("Le taux est lié à l'entreprise sélectionnée et au mois affiché ; il n'est jamais réutilisé silencieusement pour un autre employeur ou un autre mois.")
                .font(.footnote)
                .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private var warningsCard: some View {
        if !salaryStore.displayWarnings.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                Text("ÉLÉMENTS À VÉRIFIER")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)
                ForEach(Array(salaryStore.displayWarnings.enumerated()), id: \.offset) { _, warning in
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

    private func companyLabel(_ company: SalaryCompanyV2) -> String {
        let name = company.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let base = name.isEmpty ? "Entreprise" : name
        let siret = company.siret.filter(\.isNumber)
        if siret.count == 14 {
            return "\(base) — SIRET \(siret)"
        }
        return "\(base) — ID \(company.id)"
    }

    private func minutesLabel(_ totalMinutes: Int) -> String {
        let hours = totalMinutes / 60
        let minutes = totalMinutes % 60
        return minutes == 0 ? "\(hours) h" : "\(hours) h \(minutes) min"
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
