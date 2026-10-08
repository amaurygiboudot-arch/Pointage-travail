import Foundation
import SwiftUI

/// Écran de référence datée des règles de travail pour le salarié du compte actif.
/// La source contractuelle vérifiée n'est pas un barème de paie certifié.
struct SalaryDatedWorkRulesEditorV2: View {
    let companyId: String

    @EnvironmentObject private var authentication: AuthManager
    @Environment(\.dismiss) private var dismiss
    @State private var openedUid: String?
    @State private var selectedContractId = ""
    @State private var selectedTopic: SalaryWorkRuleTopicV2 = .timeAccounting
    @State private var effectiveFrom = Date()
    @State private var effectiveUntil = Date()
    @State private var hasEnd = false
    @State private var sourceId = ""
    @State private var ruleReference = ""
    @State private var confirmsOwnContract = false
    @State private var savedRecords: [SalaryDatedWorkRuleV2] = []
    @State private var info = ""
    @State private var preview = ""
    @State private var proposal: SalaryDatedWorkRuleV2?
    @State private var replacingId: String?
    @State private var showConfirmation = false

    private var authenticatedUid: String? {
        let uid = authentication.user?.uid.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        return uid.isEmpty ? nil : uid
    }
    private var accountStillValid: Bool {
        openedUid != nil && openedUid == authenticatedUid
    }
    private var companyConfirmed: Bool {
        let stored = SalaryCompanyStoreV2.readConfirmed()
        return stored.reliable && stored.companies.contains(where: { $0.id == companyId })
    }
    private var history: SalaryEmploymentContractHistoryReadResultV2 {
        SalaryEmploymentContractHistoryStoreV2.readConfirmed()
    }
    private var companyContracts: [SalaryEmploymentContractSnapshotV2] {
        guard companyConfirmed, history.reliable else { return [] }
        return history.snapshots.filter { $0.contract.employerId == companyId }
            .sorted { $0.effectiveFromEpochDay > $1.effectiveFromEpochDay }
    }
    private var selectedContract: SalaryEmploymentContractSnapshotV2? {
        companyContracts.first { $0.versionId == selectedContractId }
    }

    var body: some View {
        Form {
            Section("Propriétaire du calcul") {
                if accountStillValid {
                    Label("Votre propre profil salarié connecté", systemImage: "person.crop.circle.badge.checkmark")
                        .foregroundStyle(.secondary)
                    Text("L'identité utilisée est celle du compte Firebase actuellement connecté.")
                        .font(.caption)
                } else {
                    Label("Connectez-vous ou rouvrez cet écran après changement de compte.",
                          systemImage: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
                Text("Entreprise sélectionnée : \(companyId)")
                    .font(.caption)
            }

            Section("Contrat daté") {
                if !companyConfirmed {
                    Text("Entreprise introuvable ou stockage local incohérent.")
                        .foregroundStyle(.orange)
                } else if !history.reliable {
                    Text("Historique des contrats non fiable. Aucune règle ne sera enregistrée.")
                        .foregroundStyle(.orange)
                } else if companyContracts.isEmpty {
                    Text("Ajoutez d'abord une version datée du contrat depuis l'onglet Salaire.")
                } else {
                    Picker("Version", selection: $selectedContractId) {
                        ForEach(companyContracts, id: \.versionId) { c in
                            Text("\(c.versionId) — \(dayLabel(c.effectiveFromEpochDay))")
                                .tag(c.versionId)
                        }
                    }
                }
            }

            if selectedContract != nil {
                Section("Référence applicable") {
                    Picker("Sujet", selection: $selectedTopic) {
                        ForEach(SalaryWorkRuleTopicV2.allCases, id: \.self) { topic in
                            Text(topicLabel(topic)).tag(topic)
                        }
                    }
                    DatePicker("Début de validité", selection: $effectiveFrom, displayedComponents: .date)
                    Toggle("Une date de fin est connue", isOn: $hasEnd)
                    if hasEnd {
                        DatePicker("Fin incluse", selection: $effectiveUntil, displayedComponents: .date)
                    }
                    TextField("Identifiant de la source", text: $sourceId)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    TextField("Référence de l'article ou du contrat", text: $ruleReference)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    Toggle("Je confirme que ce contrat concerne mon propre travail", isOn: $confirmsOwnContract)
                }
                Section {
                    Button("Vérifier et enregistrer la référence") { prepareProposal() }
                        .disabled(!accountStillValid || !confirmsOwnContract)
                } footer: {
                    Text("Une règle manuelle sans source juridique vérifiée reste « à confirmer ». Aucun taux, panier ou salaire ne sera deviné.")
                }
            }

            Section("Historique personnel pour ce contrat") {
                if !accountStillValid {
                    Text("Compte déconnecté ou changé : historique masqué.")
                } else if savedRecords.isEmpty {
                    Text("Aucune règle encore enregistrée, ou lecture non fiable.")
                } else {
                    ForEach(savedRecords, id: \.id) { rule in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(topicLabel(rule.topic)).font(.headline)
                            Text(rule.confirmation == .confirmed
                                ? "Provenance du contrat confirmée — pas un taux de paie"
                                : "À confirmer avant tout calcul certifié")
                                .font(.caption)
                                .foregroundStyle(rule.confirmation == .confirmed ? .primary : .orange)
                            Text("\(dayLabel(rule.effectiveFromEpochDay)) → \(rule.effectiveToEpochDay.map { dayLabel($0 - 1) } ?? "en cours")")
                            Text("Source : \(rule.sourceId.isEmpty ? "à fournir" : rule.sourceId)")
                                .font(.caption)
                            Text("Référence : \(rule.ruleReference.isEmpty ? "à préciser" : rule.ruleReference)")
                                .font(.caption)
                        }
                    }
                }
            }

            if !info.isEmpty {
                Section("État") { Text(info).font(.footnote) }
            }
        }
        .navigationTitle("Règles datées")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear { initialize() }
        .onChange(of: selectedContractId) { _ in
            loadSelectedContract()
        }
        .onChange(of: authentication.user?.uid) { _ in
            savedRecords = []
            info = "Le compte a changé : fermez puis rouvrez l'écran avant d'enregistrer."
            confirmsOwnContract = false
        }
        .alert("Confirmer la référence", isPresented: $showConfirmation) {
            Button("Annuler", role: .cancel) {
                proposal = nil
                replacingId = nil
            }
            Button("Enregistrer") { saveProposal() }
        } message: {
            Text(preview)
        }
    }

    private func initialize() {
        guard openedUid == nil else { return }
        openedUid = authenticatedUid
        guard accountStillValid else {
            info = "Connexion Firebase nécessaire pour les règles personnelles."
            return
        }
        guard let first = companyContracts.first else {
            info = "Aucune version de contrat datée et confirmée n'est disponible."
            return
        }
        selectedContractId = first.versionId
        loadSelectedContract()
    }

    private func loadSelectedContract() {
        savedRecords = []
        confirmsOwnContract = false
        guard let snapshot = selectedContract, accountStillValid else { return }
        effectiveFrom = localDate(for: snapshot.effectiveFromEpochDay)
        if let end = snapshot.effectiveToEpochDay {
            effectiveUntil = localDate(for: end)
            hasEnd = true
        } else {
            effectiveUntil = effectiveFrom
            hasEnd = false
        }
        sourceId = snapshot.sourceId
        ruleReference = "contract:\(snapshot.versionId)"
        reloadHistory()
    }

    private func reloadHistory() {
        savedRecords = []
        guard accountStillValid, let uid = authenticatedUid,
              let snapshot = selectedContract,
              let owner = SalaryDatedWorkRuleOwnerAuthorizationV2.ownerForSelf(
                  authenticatedUid: uid,
                  employerId: companyId,
                  contractVersionId: snapshot.versionId
              ) else { return }
        let result = SalaryDatedWorkRuleStoreV2.read(
            owner: owner, authenticatedAccountId: uid
        )
        guard result.reliable else {
            info = "Compte, contrat ou stockage local incohérent : lecture refusée."
            return
        }
        savedRecords = result.records.sorted {
            $0.effectiveFromEpochDay > $1.effectiveFromEpochDay
        }
        info = ""
    }

    private func prepareProposal() {
        guard accountStillValid, confirmsOwnContract,
              let uid = authenticatedUid, let snapshot = selectedContract,
              let owner = SalaryDatedWorkRuleOwnerAuthorizationV2.ownerForSelf(
                  authenticatedUid: uid, employerId: companyId,
                  contractVersionId: snapshot.versionId
              ), let from = epochDay(effectiveFrom) else {
            info = "Compte, propriétaire ou date invalide."
            return
        }
        let until: Int64?
        if hasEnd {
            guard let endDay = epochDay(effectiveUntil), endDay < Int64.max else {
                info = "Date de fin invalide."
                return
            }
            until = endDay + 1
        } else {
            until = nil
        }
        let source = sourceId.trimmingCharacters(in: .whitespacesAndNewlines)
        let reference = ruleReference.trimmingCharacters(in: .whitespacesAndNewlines)
        guard source.count <= 512, reference.count <= 512 else {
            info = "La référence saisie est trop longue."
            return
        }
        let provenContract =
            selectedTopic == .timeAccounting &&
            snapshot.checkedAtMs > 0 &&
            source == snapshot.sourceId &&
            reference == "contract:\(snapshot.versionId)"
        let record = SalaryDatedWorkRuleV2(
            id: UUID().uuidString, owner: owner, topic: selectedTopic,
            effectiveFromEpochDay: from, effectiveToEpochDay: until,
            sourceId: source, ruleReference: reference,
            checkedAtMs: provenContract ? snapshot.checkedAtMs : 0,
            confirmation: provenContract ? .confirmed : .toConfirm
        )
        guard SalaryDatedWorkRuleContractScopeV2.verifiedApplicability(record, contract: snapshot) else {
            info = "La période dépasse celle de votre contrat ou la source est incohérente."
            return
        }
        let current = SalaryDatedWorkRuleStoreV2.read(
            owner: owner, authenticatedAccountId: uid
        )
        guard current.reliable else {
            info = "Le stockage n'est pas fiable ; aucune modification possible."
            return
        }
        replacingId = provenContract ? current.records.first(where: {
            $0.topic == selectedTopic && $0.confirmation == .confirmed &&
            $0.effectiveToEpochDay == nil && $0.effectiveFromEpochDay < from
        })?.id : nil

        proposal = record
        preview =
            "Entreprise : \(companyId)\nContrat : \(snapshot.versionId)\n" +
            "Sujet : \(topicLabel(selectedTopic))\n" +
            "Début : \(dayLabel(from))\n" +
            "Fin incluse : \(until.map { dayLabel($0 - 1) } ?? "en cours")\n" +
            "Source : \(source.isEmpty ? "à fournir" : source)\n" +
            "Référence : \(reference.isEmpty ? "à vérifier" : reference)\n" +
            (provenContract
              ? "Provenance du contrat confirmée — aucun taux de paie déduit"
              : "À confirmer — ne certifie aucun montant de paie") +
            (replacingId.map { "\nAncienne version à fermer : \($0)" } ?? "")
        showConfirmation = true
    }

    private func saveProposal() {
        guard accountStillValid, let uid = authenticatedUid,
              let selected = selectedContract, let record = proposal,
              selected.versionId == record.owner.contractVersionId,
              confirmsOwnContract else {
            info = "Compte ou contrat changé : enregistrement refusé."
            return
        }
        let saved: Bool
        if let previous = replacingId {
            saved = SalaryDatedWorkRuleStoreV2.replaceOpenVersion(
                oldRecordId: previous, with: record,
                owner: record.owner, authenticatedAccountId: uid
            )
        } else {
            saved = SalaryDatedWorkRuleStoreV2.append(
                record, owner: record.owner, authenticatedAccountId: uid
            )
        }
        proposal = nil
        replacingId = nil
        info = saved
            ? "Référence datée enregistrée, sans modifier le barème de paie."
            : "Refus : compte, contrat, doublon ou période incohérente."
        if saved { reloadHistory() }
    }

    private func epochDay(_ date: Date) -> Int64? {
        let local = Calendar.current.dateComponents([.year, .month, .day], from: date)
        guard let year = local.year, let month = local.month, let day = local.day else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let midnight = utc.date(from: DateComponents(
            year: year, month: month, day: day, hour: 0
        )) else { return nil }
        return Int64(floor(midnight.timeIntervalSince1970 / 86_400))
    }

    private func localDate(for day: Int64) -> Date {
        let utc = Date(timeIntervalSince1970: Double(day) * 86_400)
        var utcCalendar = Calendar(identifier: .gregorian)
        utcCalendar.timeZone = TimeZone(secondsFromGMT: 0)!
        let components = utcCalendar.dateComponents([.year, .month, .day], from: utc)
        return Calendar.current.date(from: DateComponents(
            year: components.year, month: components.month, day: components.day, hour: 12
        )) ?? utc
    }

    private func dayLabel(_ day: Int64) -> String {
        let utc = Date(timeIntervalSince1970: Double(day) * 86_400)
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd"
        formatter.timeZone = TimeZone(secondsFromGMT: 0)
        return formatter.string(from: utc)
    }

    private func topicLabel(_ topic: SalaryWorkRuleTopicV2) -> String {
        switch topic {
        case .timeAccounting: return "Décompte contractuel du temps"
        case .pauseCompensation: return "Pauses rémunérées"
        case .nightWork: return "Travail de nuit"
        case .weekendWork: return "Samedi et dimanche"
        case .publicHoliday: return "Jours fériés"
        case .overtime: return "Heures supplémentaires/complémentaires"
        case .onCall: return "Astreintes et interventions"
        case .businessTravel: return "Déplacements professionnels"
        case .mealAllowance: return "Indemnités de repas"
        case .absence: return "Absences"
        }
    }
}
