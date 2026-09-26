import SwiftUI

struct SalaryV2View: View {
    @EnvironmentObject private var salaryStore: SalaryV2Store
    @EnvironmentObject private var workStore: WorkStoreV2
    @State private var salaryPdfURL: URL?
    @State private var salaryPdfFeedback: String?
    @State private var payslipGrossText = ""
    @State private var payslipNetBeforeTaxText = ""
    @State private var payslipNetTaxableText = ""
    @State private var payslipIncomeTaxText = ""
    @State private var payslipNetAfterTaxText = ""
    @State private var payslipComparisonResult: SalaryPayslipComparisonResultV2?
    @State private var payslipComparisonFeedback: String?

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 18) {
                    periodSelector
                    companySelectorCard
                    contractCard
                    segmentedProrationCard
                    segmentedVariableStatusCard
                    socialProfileCard
                    classificationCard
                    conventionCoverageCard
                    reliabilityCard
                    paidWorkCard
                    absenceCard
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
                clearPayslipComparisonResult()
            }
            .onChange(of: workStore.storageReliable) { _ in
                salaryStore.refresh()
                clearPayslipComparisonResult()
            }
            .onChange(of: salaryStore.selectedPeriod) { _ in
                resetPayslipComparison()
            }
            .onChange(of: salaryStore.selectedCompanyId) { _ in
                resetPayslipComparison()
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

    private var contractCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CONTRAT DATÉ CONFIRMÉ")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            Text("La date d’entrée et la date d’effet sont distinctes. HoraTrack ne déduit aucune date du mois affiché ni de la date du jour.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Picker("Type de contrat", selection: $salaryStore.contractTypeSelection) {
                Text("À confirmer").tag("")
                Text("Temps plein").tag("FULL_TIME")
                Text("Temps partiel").tag("PART_TIME")
                Text("Forfait heures annuel").tag("FORFAIT_HOURS")
                Text("Forfait jours annuel").tag("FORFAIT_DAYS")
                Text("Autre").tag("OTHER")
            }
            .pickerStyle(.menu)
            .disabled(salaryStore.selectedCompanyId == nil)

            TextField("Date d’entrée — JJ/MM/AAAA", text: $salaryStore.contractHireDateText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Date d’effet de cette version — JJ/MM/AAAA", text: $salaryStore.contractEffectiveDateText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Source — contrat signé, avenant…", text: $salaryStore.contractSourceText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)

            if hourlyContractSelected {
                TextField("Durée hebdomadaire — ex. 35", text: $salaryStore.contractWeeklyHoursText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
                TextField("Taux horaire brut — ex. 13,70", text: $salaryStore.contractHourlyRateText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
            } else if salaryStore.contractTypeSelection == "FORFAIT_HOURS" {
                TextField("Nombre d’heures du forfait annuel", text: $salaryStore.contractForfaitHoursText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
                TextField("Salaire brut mensuel convenu", text: $salaryStore.contractMonthlyGrossText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
            } else if salaryStore.contractTypeSelection == "FORFAIT_DAYS" {
                TextField("Nombre de jours du forfait annuel — max. standard 218", text: $salaryStore.contractForfaitDaysText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
                TextField("Salaire brut mensuel convenu", text: $salaryStore.contractMonthlyGrossText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                    .disabled(salaryStore.selectedCompanyId == nil)
            }

            Button("Confirmer cette version datée") {
                _ = salaryStore.confirmEmploymentContract()
            }
            .buttonStyle(.borderedProminent)
            .disabled(salaryStore.selectedCompanyId == nil)

            if let feedback = salaryStore.contractFeedback {
                Text(feedback)
                    .font(.footnote)
            }

            if let resolution = salaryStore.contractResolution?.resolution,
               resolution.requiresMultipleContractVersions {
                Label("Plusieurs versions couvrent le mois : le calcul unique reste bloqué.", systemImage: "arrow.triangle.branch")
                    .font(.footnote)
            } else if salaryStore.contractResolution?.readyForSingleContractCalculation == true {
                Label("Le mois est couvert par une version contractuelle datée confirmée.", systemImage: "checkmark.shield.fill")
                    .font(.footnote)
            } else if salaryStore.selectedCompanyId != nil {
                Label("Le mois n’est pas encore entièrement couvert par un contrat daté confirmé.", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    @ViewBuilder
    private var segmentedProrationCard: some View {
        if salaryStore.requiresSegmentedProration {
            VStack(alignment: .leading, spacing: 10) {
                Text("PRORATISATION DU MOIS")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                Text("Un changement réel de contrat en cours de mois exige une base planifiée confirmée. HoraTrack n’invente ni répartition calendaire ni planning.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                ForEach(salaryStore.segmentedProrationDraftSegments) { segment in
                    VStack(alignment: .leading, spacing: 5) {
                        Text("Version \(segment.versionId)")
                            .font(.footnote.bold())
                        Text("\(epochDayLabel(segment.startEpochDay)) → \(epochDayLabel(segment.endEpochDay))")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                        TextField(
                            "Minutes planifiées confirmées pour ce segment",
                            text: Binding(
                                get: { segment.scheduledMinutesText },
                                set: {
                                    salaryStore.updateSegmentedProrationMinutes(
                                        segmentId: segment.id,
                                        text: $0
                                    )
                                }
                            )
                        )
                        .keyboardType(.numberPad)
                        .textFieldStyle(.roundedBorder)
                    }
                }

                TextField(
                    "Source — planning signé, avenant, relevé employeur…",
                    text: $salaryStore.segmentedProrationSourceText
                )
                .textFieldStyle(.roundedBorder)

                Button("Confirmer les minutes planifiées du mois") {
                    _ = salaryStore.confirmSegmentedProration()
                }
                .buttonStyle(.borderedProminent)

                if salaryStore.segmentedProrationSource?.reliable == true {
                    Label(
                        "Base planifiée confirmée et stockée pour ce mois",
                        systemImage: "checkmark.shield.fill"
                    )
                    .font(.footnote)

                    if let base = salaryStore.segmentedMonthlyBase,
                       base.reliable,
                       let amount = base.baseGross {
                        Label(
                            "Base mensuelle segmentée calculée : \(euros(amount))",
                            systemImage: "function"
                        )
                        .font(.footnote.bold())

                        Text("Montant contractuel proratisé uniquement. Les heures supplémentaires/complémentaires, majorations, primes, absences, retenues et le net final restent traités séparément et ne sont pas inventés.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else {
                        Label(
                            "Base confirmée, mais calcul monétaire encore bloqué par les contrats ou règles datées.",
                            systemImage: "exclamationmark.triangle.fill"
                        )
                        .font(.footnote)
                    }

                    Button("Retirer cette confirmation", role: .destructive) {
                        _ = salaryStore.removeSegmentedProration()
                    }
                    .font(.footnote)
                } else {
                    Label(
                        "Base planifiée à confirmer — aucun prorata n’est calculé",
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.footnote)
                }

                if let feedback = salaryStore.segmentedProrationFeedback {
                    Text(feedback)
                        .font(.footnote)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    @ViewBuilder
    private var segmentedVariableStatusCard: some View {
        if salaryStore.hasMaterialSegmentedPayrollTransition,
           let assessment = salaryStore.segmentedPayrollBoundary {
            VStack(alignment: .leading, spacing: 10) {
                Text("ÉLÉMENTS VARIABLES SEGMENTÉS")
                    .font(.caption.bold())
                    .foregroundStyle(.secondary)

                if assessment.safeForIndependentWeeklyVariableCalculation {
                    Label(
                        "Les changements de paie commencent en début de semaine : aucun seuil hebdomadaire n’est coupé.",
                        systemImage: "checkmark.shield.fill"
                    )
                    .font(.footnote)

                    Text("Cette preuve prépare le calcul segmenté des heures supplémentaires/complémentaires et majorations. Aucun montant variable n’est encore publié tant que ce calcul dédié n’est pas raccordé.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else {
                    Label(
                        "Un changement intervient en cours de semaine : les variables hebdomadaires restent bloquées.",
                        systemImage: "exclamationmark.triangle.fill"
                    )
                    .font(.footnote)

                    Text("HoraTrack ne découpe pas artificiellement une semaine pour inventer des heures supplémentaires, complémentaires ou majorations.")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding()
            .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
        }
    }

    private var hourlyContractSelected: Bool {
        ["FULL_TIME", "PART_TIME", "OTHER"].contains(salaryStore.contractTypeSelection)
    }

    private var socialProfileCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("PROFIL SOCIAL DATÉ")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            Text("Le statut professionnel et le régime local peuvent modifier les cotisations. HoraTrack ne les déduit jamais du métier, de l’adresse ou de la convention.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Picker("Statut professionnel", selection: $salaryStore.socialProfessionalStatusSelection) {
                Text("À confirmer").tag("")
                Text("Non-cadre").tag("NON_CADRE")
                Text("Cadre").tag("CADRE")
            }
            .pickerStyle(.menu)
            .disabled(salaryStore.selectedCompanyId == nil)

            Picker("Régime local Alsace-Moselle", selection: $salaryStore.socialAlsaceMoselleSelection) {
                Text("À confirmer").tag("")
                Text("Oui, affilié").tag("YES")
                Text("Non, non affilié").tag("NO")
            }
            .pickerStyle(.menu)
            .disabled(salaryStore.selectedCompanyId == nil)

            TextField("Date d’effet — JJ/MM/AAAA", text: $salaryStore.socialEffectiveDateText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)

            TextField("Source — bulletin, contrat, attestation…", text: $salaryStore.socialSourceText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)

            Button("Confirmer ce profil daté") {
                _ = salaryStore.confirmSocialProfile()
            }
            .buttonStyle(.borderedProminent)
            .disabled(salaryStore.selectedCompanyId == nil)

            if let feedback = salaryStore.socialProfileFeedback {
                Text(feedback)
                    .font(.footnote)
            }

            if salaryStore.socialProfile?.reliable == true,
               let status = salaryStore.socialProfile?.professionalStatus,
               let local = salaryStore.socialProfile?.alsaceMoselleLocalRegime {
                Label("Profil confirmé pour tout le mois", systemImage: "checkmark.shield.fill")
                    .font(.footnote)
                Text("\(status == .cadre ? "Cadre" : "Non-cadre") — régime local Alsace-Moselle : \(local ? "oui" : "non")")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if salaryStore.selectedCompanyId != nil {
                Label("Profil social à confirmer pour toute la période", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var classificationCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("CLASSIFICATION CONVENTIONNELLE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            Text("Renseignez uniquement les critères écrits sur votre classification réelle. Selon la convention, un coefficient, un niveau, un échelon, une position, un groupe, une catégorie ou un emploi peut être utilisé.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            TextField("Coefficient — ex. 910", text: $salaryStore.classificationCoefficientText)
                .keyboardType(.numberPad)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Niveau", text: $salaryStore.classificationLevelText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Échelon", text: $salaryStore.classificationEchelonText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Position", text: $salaryStore.classificationPositionText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Groupe", text: $salaryStore.classificationGroupText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Catégorie", text: $salaryStore.classificationCategoryText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Emploi / emploi repère", text: $salaryStore.classificationEmploymentText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)

            Button("Enregistrer cette classification") {
                _ = salaryStore.confirmConventionClassification()
            }
            .buttonStyle(.borderedProminent)
            .disabled(salaryStore.selectedCompanyId == nil)

            if let feedback = salaryStore.classificationFeedback {
                Text(feedback)
                    .font(.footnote)
            }

            if let companyId = salaryStore.selectedCompanyId {
                let classification = SalaryConventionClassificationStoreV2.load(companyId: companyId)
                if classification.isEmpty {
                    Label("Classification à confirmer", systemImage: "exclamationmark.triangle.fill")
                        .font(.footnote)
                } else {
                    Label(classification.label, systemImage: "checkmark.shield.fill")
                        .font(.footnote)
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 18))
    }

    private var conventionCoverageCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("CONVENTION COLLECTIVE — PÉRIODE")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            if salaryStore.selectedCompanyId == nil {
                Text("À confirmer")
                    .font(.title3.bold())
                Text("Sélectionnez d'abord l'entreprise à analyser.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
            } else if let coverage = salaryStore.conventionCoverage {
                if let snapshot = coverage.singleSnapshotForWholePeriod {
                    Label("Mois entièrement couvert", systemImage: "checkmark.shield.fill")
                        .font(.title3.bold())
                    Text("IDCC \(snapshot.idcc) — version \(snapshot.versionId)")
                        .font(.footnote)
                    Text("Source confirmée : \(snapshot.sourceId)")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else if coverage.requiresMultipleRuleVersions {
                    Label("Changement de version dans le mois", systemImage: "arrow.triangle.branch")
                        .font(.title3.bold())
                    Text("IDCC \(coverage.idcc ?? "à confirmer") — \(coverage.segments.count) versions confirmées")
                        .font(.footnote)
                    Text("HoraTrack conserve chaque période séparément et n'applique aucune version unique à tout le mois.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else if coverage.sourceReliable {
                    Label("Couverture incomplète", systemImage: "exclamationmark.triangle.fill")
                        .font(.title3.bold())
                    Text("IDCC \(coverage.idcc ?? "à confirmer")")
                        .font(.footnote)
                    Text("Une partie du mois n'a pas de version confirmée : aucun fallback n'est utilisé.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                } else {
                    Label("À confirmer", systemImage: "questionmark.diamond.fill")
                        .font(.title3.bold())
                    if let idcc = coverage.idcc {
                        Text("IDCC \(idcc)")
                            .font(.footnote)
                    }
                    Text("Les sources locales ne permettent pas d'établir une couverture conventionnelle fiable pour ce mois.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            } else {
                Text("À confirmer")
                    .font(.title3.bold())
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

    private var absenceCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("ABSENCES DU MOIS")
                .font(.caption.bold())
                .foregroundStyle(.secondary)

            Text("Une liste vide n'est considérée comme « aucune absence » qu'après confirmation explicite de l'exhaustivité du mois.")
                .font(.footnote)
                .foregroundStyle(.secondary)

            Picker("Type", selection: $salaryStore.absenceTypeSelection) {
                Text("Absence non rémunérée").tag(SalaryAbsencePayrollImpactV2.typeUnpaid)
                Text("Arrêt maladie").tag(SalaryAbsencePayrollImpactV2.typeSickness)
                Text("Congé payé").tag(SalaryAbsencePayrollImpactV2.typePaidLeave)
                Text("Accident du travail").tag(SalaryAbsencePayrollImpactV2.typeWorkAccident)
                Text("Accident de trajet").tag(SalaryAbsencePayrollImpactV2.typeCommutingAccident)
                Text("Maladie professionnelle").tag(SalaryAbsencePayrollImpactV2.typeOccupationalDisease)
                Text("Maternité / paternité").tag(SalaryAbsencePayrollImpactV2.typeParental)
                Text("Autre").tag(SalaryAbsencePayrollImpactV2.typeOther)
            }
            .pickerStyle(.menu)
            .disabled(salaryStore.selectedCompanyId == nil)

            Picker("Traitement salarial", selection: $salaryStore.absenceTreatmentSelection) {
                Text("Non rémunérée").tag(SalaryAbsenceTreatmentV2.unpaid.rawValue)
                Text("Maintien total").tag(SalaryAbsenceTreatmentV2.fullyMaintained.rawValue)
                Text("Maintien partiel").tag(SalaryAbsenceTreatmentV2.partiallyMaintained.rawValue)
                Text("À confirmer").tag(SalaryAbsenceTreatmentV2.toConfirm.rawValue)
            }
            .pickerStyle(.menu)
            .disabled(salaryStore.selectedCompanyId == nil)

            Toggle("Journée(s) complète(s)", isOn: $salaryStore.absenceFullDay)
                .disabled(salaryStore.selectedCompanyId == nil)

            TextField("Début — JJ/MM/AAAA", text: $salaryStore.absenceStartDateText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)
            TextField("Fin incluse — JJ/MM/AAAA", text: $salaryStore.absenceEndDateText)
                .textFieldStyle(.roundedBorder)
                .disabled(salaryStore.selectedCompanyId == nil)

            Button("Ajouter cette absence") {
                _ = salaryStore.saveAbsence()
            }
            .buttonStyle(.bordered)
            .disabled(salaryStore.selectedCompanyId == nil)

            if let companyId = salaryStore.selectedCompanyId {
                let stored = SalaryAbsenceStoreV2.read(companyId: companyId)
                let visible = stored.absences.filter(absenceTouchesSelectedMonth)
                if !visible.isEmpty {
                    Divider()
                    ForEach(visible, id: \.id) { absence in
                        HStack(alignment: .top) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(SalaryAbsencePayrollImpactV2.label(absence.type))
                                    .font(.footnote.bold())
                                Text("\(dateLabel(absence.start)) → \(dateLabel(absence.end.addingTimeInterval(-1)))")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Spacer()
                            Button(role: .destructive) {
                                _ = salaryStore.removeAbsence(absence.id)
                            } label: {
                                Image(systemName: "trash")
                            }
                        }
                    }
                }
            }

            Divider()
            TextField(
                "Source de confirmation du mois — planning, bulletin, vérification personnelle…",
                text: $salaryStore.absenceMonthSourceText
            )
            .textFieldStyle(.roundedBorder)
            .disabled(salaryStore.selectedCompanyId == nil)

            Button("Confirmer la liste exhaustive pour ce mois") {
                _ = salaryStore.confirmAbsenceMonthCoverage()
            }
            .buttonStyle(.borderedProminent)
            .disabled(salaryStore.selectedCompanyId == nil)

            if salaryStore.absenceSource?.reliable == true {
                Label("Liste des absences du mois confirmée exhaustive", systemImage: "checkmark.shield.fill")
                    .font(.footnote)
            } else if salaryStore.selectedCompanyId != nil {
                Label("Liste des absences du mois à confirmer", systemImage: "exclamationmark.triangle.fill")
                    .font(.footnote)
            }

            if let feedback = salaryStore.absenceFeedback {
                Text(feedback)
                    .font(.footnote)
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
            VStack(alignment: .leading, spacing: 8) {
                Text("Comparaison bulletin")
                    .fontWeight(.semibold)

                Text("Renseignez uniquement les montants réellement lus sur le bulletin. Un champ vide est ignoré ; HoraTrack compare seulement les valeurs que la référence canonique sait aussi produire.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)

                TextField("Brut social observé", text: $payslipGrossText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                TextField("Net avant impôt observé", text: $payslipNetBeforeTaxText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                TextField("Net imposable observé", text: $payslipNetTaxableText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                TextField("Prélèvement à la source observé", text: $payslipIncomeTaxText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)
                TextField("Net après impôt observé", text: $payslipNetAfterTaxText)
                    .keyboardType(.decimalPad)
                    .textFieldStyle(.roundedBorder)

                Button("Comparer avec HoraTrack") {
                    comparePayslip()
                }
                .buttonStyle(.bordered)
                .disabled(salaryStore.selectedCompanyId == nil)

                if let result = payslipComparisonResult {
                    if result.conforming {
                        Label(
                            "Aucun écart supérieur à 0,02 € sur les champs comparables.",
                            systemImage: "checkmark.shield.fill"
                        )
                        .font(.footnote)
                    } else {
                        ForEach(result.discrepancies) { discrepancy in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(discrepancy.field.label)
                                    .font(.footnote.bold())
                                Text(
                                    "HoraTrack : \(euros(discrepancy.expected)) — Bulletin : \(euros(discrepancy.observed))"
                                )
                                .font(.footnote)
                                Text(discrepancy.explanation)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                }

                if let payslipComparisonFeedback {
                    Text(payslipComparisonFeedback)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                HStack {
                    Text("PDF paie")
                    Spacer()
                    Button("Préparer le PDF") {
                        do {
                            salaryPdfURL = try SalaryV2PdfExporter.export(
                                snapshot: salaryStore.snapshot,
                                company: salaryStore.selectedCompany
                            )
                            salaryPdfFeedback = "PDF généré depuis le snapshot canonique affiché."
                        } catch {
                            salaryPdfURL = nil
                            salaryPdfFeedback = "Impossible de générer le PDF."
                        }
                    }
                    .buttonStyle(.bordered)
                }

                if let salaryPdfURL {
                    ShareLink(
                        item: salaryPdfURL,
                        subject: Text("HoraTrack — estimation de salaire"),
                        message: Text("Export Salaire V2 HoraTrack")
                    ) {
                        Label("Partager le PDF", systemImage: "square.and.arrow.up")
                    }
                }

                if let salaryPdfFeedback {
                    Text(salaryPdfFeedback)
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
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

    private func absenceTouchesSelectedMonth(_ absence: SalaryAbsenceFactV2) -> Bool {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = .current
        let period = salaryStore.selectedPeriod
        let nextYear = period.month == 12 ? period.year + 1 : period.year
        let nextMonth = period.month == 12 ? 1 : period.month + 1
        guard let start = calendar.date(
            from: DateComponents(year: period.year, month: period.month, day: 1)
        ),
        let end = calendar.date(
            from: DateComponents(year: nextYear, month: nextMonth, day: 1)
        ) else {
            return false
        }
        return absence.start < end && absence.end > start
    }

    private func dateLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.dateFormat = "dd/MM/yyyy"
        return formatter.string(from: date)
    }

    private func epochDayLabel(_ epochDay: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochDay) * 86_400.0)
        let formatter = DateFormatter()
        formatter.calendar = Calendar(identifier: .gregorian)
        formatter.locale = Locale(identifier: "fr_FR")
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        formatter.dateFormat = "dd/MM/yyyy"
        return formatter.string(from: date)
    }

    private func comparePayslip() {
        let parsed = [
            parseObservedAmount(payslipGrossText),
            parseObservedAmount(payslipNetBeforeTaxText),
            parseObservedAmount(payslipNetTaxableText),
            parseObservedAmount(payslipIncomeTaxText),
            parseObservedAmount(payslipNetAfterTaxText)
        ]
        guard parsed.allSatisfy(\.valid) else {
            payslipComparisonResult = nil
            payslipComparisonFeedback =
                "Bulletin : vérifiez les montants saisis. Seuls les nombres positifs ou nuls sont acceptés."
            return
        }

        let observed = SalaryPayslipObservedValuesV2(
            socialGross: parsed[0].value,
            netBeforeIncomeTax: parsed[1].value,
            netTaxable: parsed[2].value,
            incomeTax: parsed[3].value,
            netAfterIncomeTax: parsed[4].value
        )
        guard let result = SalaryPayslipComparisonEngineV2.compare(
            snapshot: salaryStore.snapshot,
            observed: observed
        ) else {
            payslipComparisonResult = nil
            payslipComparisonFeedback =
                "Comparaison impossible : aucun champ saisi n'est actuellement comparable à une valeur canonique fiable."
            return
        }

        payslipComparisonResult = result
        payslipComparisonFeedback =
            "\(result.comparedFields.count) champ(s) comparé(s) avec une tolérance de 0,02 €."
    }

    private func parseObservedAmount(_ raw: String) -> (valid: Bool, value: Double?) {
        let trimmed = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return (true, nil) }
        let normalized = trimmed.replacingOccurrences(of: ",", with: ".")
        guard let value = Double(normalized), value.isFinite, value >= 0 else {
            return (false, nil)
        }
        return (true, value)
    }

    private func clearPayslipComparisonResult() {
        payslipComparisonResult = nil
        payslipComparisonFeedback = nil
    }

    private func resetPayslipComparison() {
        payslipGrossText = ""
        payslipNetBeforeTaxText = ""
        payslipNetTaxableText = ""
        payslipIncomeTaxText = ""
        payslipNetAfterTaxText = ""
        clearPayslipComparisonResult()
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
