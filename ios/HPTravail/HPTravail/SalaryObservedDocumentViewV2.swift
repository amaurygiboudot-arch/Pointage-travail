import SwiftUI

/// Manual source transcription. Every save is bound to the opened account/company/month/revision.
struct SalaryObservedDocumentViewV2: View {
    let company: SalaryCompanyV2
    let period: YearMonthV2
    let lease: SalaryAccountSessionV2.Lease
    @State private var original: SalaryPayslipObservedStoreV2.Result?
    @State private var sourcePeriod = ""
    @State private var source = ""
    @State private var lines: [SalaryObservedDocumentV2.Line] = []
    @State private var confirmed = false
    @State private var feedback: String?
    @State private var pdf: URL?
    @State private var deleting = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("BULLETIN CONSTATÉ — \(company.name), \(period.description)").font(.headline)
            Text(SalaryObservedDocumentV2.notice).font(.footnote)
            Text("Recopiez les signes tels qu’imprimés et précisez « à payer » ou « à déduire » dans le libellé si nécessaire. Les retenues ne sont pas automatiquement soustraites. Une base ou un cumul ne crée aucun pointage.").font(.footnote)
            Text("Les totaux importés restent présentés séparément pour la comparaison. Les lignes recopiées ci-dessous ne remplacent pas ces totaux et ne modifient aucune estimation.").font(.footnote)
            if let record = original?.record {
                ForEach(SalaryPayslipFieldV2.allCases, id: \.self) { field in
                    if let amount = record.amounts[field.rawValue] {
                        Text("\(field.label) — total importé : \(String(format: "%.2f €", amount))")
                    }
                }
                if record.detailedDocument != nil && !SalaryObservedDocumentV2.current(record) {
                    Text("Les totaux ou sources importés ont changé. Vérifiez la transcription puis confirmez-la à nouveau avant export.").foregroundStyle(.orange)
                }
            }
            if let record = original?.record, let document = record.detailedDocument, SalaryObservedDocumentV2.current(record) {
                DisclosureGroup("Aperçu enregistré — même contenu que le PDF") {
                    ForEach(Array(SalaryObservedDocumentV2.rows(document).enumerated()), id: \.offset) { _, row in
                        VStack(alignment: .leading) { Text(row.label).bold(); Text(row.value).font(.footnote) }
                    }
                }
            }
            Text("Mois indiqué sur le document (MM/AAAA)")
            TextField("MM/AAAA", text: $sourcePeriod).textFieldStyle(.roundedBorder)
            Text("Source : document, date et référence")
            TextField("Source vérifiable", text: $source).textFieldStyle(.roundedBorder)
            ForEach($lines) { $line in
                VStack(alignment: .leading, spacing: 8) {
                    Text("Libellé exact de la ligne")
                    TextField("Rubrique", text: $line.label).textFieldStyle(.roundedBorder)
                    cell("Base salarié", value: $line.base)
                    cell("Base employeur", value: $line.employerBase)
                    cell("Taux salarié (%)", value: $line.employeeRate)
                    cell("Montant salarié (€)", value: $line.employeeAmount)
                    cell("Taux employeur (%)", value: $line.employerRate)
                    cell("Montant employeur (€)", value: $line.employerAmount)
                    Button("Retirer cette ligne", role: .destructive) { lines.removeAll { $0.id == line.id } }
                }.padding().background(Color.secondary.opacity(0.08)).cornerRadius(8)
            }
            Button("Ajouter une ligne du bulletin") { lines.append(.init(label: "")) }.disabled(lines.count >= 150)
            Toggle("Je confirme la transcription et le mois du document pour cette entreprise", isOn: $confirmed)
            Button("Enregistrer le bulletin constaté") { save() }.disabled(!confirmed || !lease.isCurrent || original?.reliable != true)
            Button("Recharger la transcription enregistrée") { load() }
            Button("Exporter le bulletin constaté en PDF") { export() }.disabled(!canExport)
            if let pdf, lease.isCurrent { ShareLink(item: pdf) { Label("Partager le bulletin constaté", systemImage: "square.and.arrow.up") } }
            Button("Supprimer uniquement la transcription", role: .destructive) { deleting = true }.disabled(original?.record?.detailedDocument == nil)
                .confirmationDialog("Supprimer la transcription de \(period.description) ? Les totaux importés restent conservés.", isPresented: $deleting) {
                    Button("Supprimer", role: .destructive) { remove() }
                    Button("Annuler", role: .cancel) { }
                }
            if let feedback { Text(feedback).font(.footnote) }
        }
        .onAppear { load() }
        .onChange(of: lines) { _ in invalidate() }
        .onChange(of: source) { _ in invalidate() }
        .onChange(of: sourcePeriod) { _ in invalidate() }
        .onDisappear { clearPDF() }
    }
    private func cell(_ label: String, value: Binding<String?>) -> some View {
        VStack(alignment: .leading) {
            Text(label).font(.caption)
            TextField("Non renseigné", text: Binding(get: { value.wrappedValue ?? "" }, set: { value.wrappedValue = $0.isEmpty ? nil : $0 }))
                .textFieldStyle(.roundedBorder).keyboardType(.numbersAndPunctuation)
        }
    }
    private var canExport: Bool {
        guard lease.isCurrent, let record = original?.record else { return false }
        return SalaryObservedDocumentV2.current(record) && lines == record.detailedDocument?.lines && source == record.detailedDocument?.source && SalaryObservedDocumentV2.period(sourcePeriod) == period
    }
    private func clearPDF() { if let pdf { try? FileManager.default.removeItem(at: pdf) }; pdf = nil }
    private func invalidate() { confirmed = false; clearPDF() }
    private func load() {
        invalidate()
        guard lease.isCurrent else { feedback = SalaryAccountStorageV2.warning; return }
        let operation = SalaryAccountSessionV2.pin(lease); defer { operation.close() }
        original = SalaryPayslipObservedStoreV2.read(companyId: company.id, period: period)
        guard original?.reliable == true else { feedback = "Source illisible conservée : la saisie ne peut pas l’écraser."; return }
        let detail = original?.record?.detailedDocument
        lines = detail?.lines ?? []
        source = detail?.source ?? ""
        sourcePeriod = detail.map { SalaryObservedDocumentV2.periodText($0.sourcePeriod) } ?? ""
    }
    private func save() {
        guard lease.isCurrent, confirmed, let original else { return }
        guard SalaryObservedDocumentV2.period(sourcePeriod) == period else { feedback = "Le mois du document doit correspondre au mois sélectionné (\(SalaryObservedDocumentV2.periodText(period))). La saisie est conservée."; return }
        var normalized = lines
        for index in normalized.indices {
            let values = [lines[index].base, lines[index].employerBase, lines[index].employeeRate, lines[index].employeeAmount, lines[index].employerRate, lines[index].employerAmount]
            var parsed: [String?] = []
            for (position, value) in values.enumerated() {
                guard let value, !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { parsed.append(nil); continue }
                guard let token = SalaryObservedDocumentV2.cell(value, money: position == 3 || position == 5) else { feedback = "Ligne \(index + 1) : montant ou taux invalide. Montants : 2 décimales ; bases/taux : 4 maximum. Aucun arrondi."; return }
                parsed.append(token)
            }
            normalized[index].base = parsed[0]; normalized[index].employerBase = parsed[1]
            normalized[index].employeeRate = parsed[2]; normalized[index].employeeAmount = parsed[3]
            normalized[index].employerRate = parsed[4]; normalized[index].employerAmount = parsed[5]
        }
        let document = SalaryObservedDocumentV2.Document(sourcePeriod: period, source: source.trimmingCharacters(in: .whitespacesAndNewlines), confirmedAt: Date(), lines: normalized)
        let operation = SalaryAccountSessionV2.pin(lease); defer { operation.close() }
        guard SalaryPayslipObservedStoreV2.saveDetail(document, companyId: company.id, period: period, confirmed: true, expected: original) else {
            feedback = "Enregistrement refusé : source, lignes ou entreprise invalides, ou données modifiées depuis l’ouverture. Votre saisie est conservée."; confirmed = false; return
        }
        load(); feedback = "Bulletin constaté enregistré pour ce mois. Aucun calcul de paie modifié."
    }
    private func remove() {
        guard lease.isCurrent, let original else { return }
        let operation = SalaryAccountSessionV2.pin(lease); defer { operation.close() }
        guard SalaryPayslipObservedStoreV2.saveDetail(nil, companyId: company.id, period: period, confirmed: true, expected: original) else { feedback = "Source modifiée : suppression annulée."; return }
        load(); feedback = "Transcription supprimée ; totaux importés conservés."
    }
    private func export() {
        guard canExport, let original, let record = original.record else { return }
        let operation = SalaryAccountSessionV2.pin(lease); defer { operation.close() }
        guard SalaryPayslipObservedStoreV2.read(companyId: company.id, period: period) == original else { feedback = "Source modifiée : rechargez les données avant export."; return }
        do { clearPDF(); pdf = try SalaryV2PdfExporter.exportObserved(record: record, company: company, lease: lease) }
        catch { feedback = "Export du bulletin constaté impossible." }
    }
}
