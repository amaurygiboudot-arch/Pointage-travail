import Foundation
import UIKit

/// Export PDF iOS de Salaire V2.
///
/// Le PDF ne calcule aucun montant : il rend uniquement le snapshot canonique déjà exposé
/// par SalaryV2Store. Une valeur absente reste affichée « À confirmer ».
enum SalaryV2PdfExporter {
    static func export(
        snapshot: SalaryWorkspaceSnapshotV2,
        company: SalaryCompanyV2?
    ) throws -> URL {
        let data = render(snapshot: snapshot, company: company)
        let companyToken = company?.id
            .trimmingCharacters(in: .whitespacesAndNewlines)
            .replacingOccurrences(
                of: #"[^A-Za-z0-9_-]+"#,
                with: "_",
                options: .regularExpression
            )
            .trimmingCharacters(in: CharacterSet(charactersIn: "_"))
        let suffix = (companyToken?.isEmpty == false ? companyToken! : "entreprise")
        let name = "HoraTrack_Salaire_\(snapshot.period.description)_\(suffix).pdf"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(name)
        try data.write(to: url, options: .atomic)
        return url
    }

    static func render(
        snapshot: SalaryWorkspaceSnapshotV2,
        company: SalaryCompanyV2?
    ) -> Data {
        let page = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: page)
        return renderer.pdfData { context in
            var y: CGFloat = 36

            func beginPage() {
                context.beginPage()
                y = 36
                draw(
                    "HORATRACK — ESTIMATION DE SALAIRE",
                    x: 32,
                    y: y,
                    width: 531,
                    font: .boldSystemFont(ofSize: 16)
                )
                y += 28
                draw(
                    "Document personnel d'estimation — non officiel",
                    x: 32,
                    y: y,
                    width: 531,
                    font: .systemFont(ofSize: 9)
                )
                y += 28
            }

            func ensure(_ height: CGFloat) {
                if y + height > 800 {
                    beginPage()
                }
            }

            func row(_ label: String, _ value: String) {
                ensure(30)
                draw(
                    label,
                    x: 32,
                    y: y,
                    width: 260,
                    font: .systemFont(ofSize: 10)
                )
                draw(
                    value,
                    x: 300,
                    y: y,
                    width: 263,
                    font: .boldSystemFont(ofSize: 10),
                    alignment: .right
                )
                y += 24
            }

            func section(_ title: String) {
                ensure(38)
                y += 8
                draw(
                    title,
                    x: 32,
                    y: y,
                    width: 531,
                    font: .boldSystemFont(ofSize: 11)
                )
                y += 22
            }

            beginPage()

            section("PÉRIODE")
            row("Mois", snapshot.period.description)
            row(
                "Entreprise",
                company.map(companyLabel) ?? "À confirmer"
            )

            section("RÉFÉRENCE CANONIQUE")
            row("Brut social estimé", money(snapshot.socialGross))
            row("Net estimé avant impôt", money(snapshot.netBeforeIncomeTax))
            row("Net imposable estimé", money(snapshot.netTaxable))
            row(
                "Prélèvement à la source",
                snapshot.incomeTax.map { "-\(money($0))" } ?? "À confirmer"
            )
            row("Net après impôt", money(snapshot.netAfterIncomeTax))

            section("COÛT EMPLOYEUR")
            row(
                "Cotisations patronales connues",
                money(snapshot.knownEmployerContributions)
            )
            row(
                snapshot.employerCostComplete
                    ? "Coût employeur total"
                    : "Coût employeur connu (partiel)",
                money(snapshot.knownEmployerCost)
            )
            row(
                "Coût employeur complet",
                snapshot.employerCostComplete ? "Oui" : "Non"
            )

            if !snapshot.employerCostWarnings.isEmpty {
                section("COÛT EMPLOYEUR — À VÉRIFIER")
                for warning in snapshot.employerCostWarnings {
                    let height = textHeight(
                        "• \(warning)",
                        width: 531,
                        font: .systemFont(ofSize: 9)
                    ) + 8
                    ensure(height)
                    draw(
                        "• \(warning)",
                        x: 32,
                        y: y,
                        width: 531,
                        font: .systemFont(ofSize: 9)
                    )
                    y += height
                }
            }

            section("FIABILITÉ")
            row(
                "Sources canoniques",
                snapshot.sourceReady ? "Raccordées" : "À confirmer"
            )
            row(
                "Brut fiable",
                snapshot.hasReliableGross ? "Oui" : "Non"
            )
            row(
                "Net avant impôt fiable",
                snapshot.hasReliableNet ? "Oui" : "Non"
            )

            if !snapshot.warnings.isEmpty {
                section("ÉLÉMENTS À VÉRIFIER")
                for warning in snapshot.warnings {
                    let height = textHeight(
                        "• \(warning)",
                        width: 531,
                        font: .systemFont(ofSize: 9)
                    ) + 8
                    ensure(height)
                    draw(
                        "• \(warning)",
                        x: 32,
                        y: y,
                        width: 531,
                        font: .systemFont(ofSize: 9)
                    )
                    y += height
                }
            }

            ensure(52)
            y += 18
            draw(
                "HoraTrack n'utilise aucune valeur de remplacement lorsqu'une donnée nécessaire n'est pas certifiable.",
                x: 32,
                y: y,
                width: 531,
                font: .italicSystemFont(ofSize: 8)
            )
        }
    }

    private static func draw(
        _ value: String,
        x: CGFloat,
        y: CGFloat,
        width: CGFloat,
        font: UIFont,
        alignment: NSTextAlignment = .left
    ) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byWordWrapping
        (value as NSString).draw(
            in: CGRect(x: x, y: y, width: width, height: 10_000),
            withAttributes: [
                .font: font,
                .paragraphStyle: paragraph
            ]
        )
    }

    private static func textHeight(
        _ value: String,
        width: CGFloat,
        font: UIFont
    ) -> CGFloat {
        let paragraph = NSMutableParagraphStyle()
        paragraph.lineBreakMode = .byWordWrapping
        return ceil(
            (value as NSString).boundingRect(
                with: CGSize(width: width, height: .greatestFiniteMagnitude),
                options: [.usesLineFragmentOrigin, .usesFontLeading],
                attributes: [
                    .font: font,
                    .paragraphStyle: paragraph
                ],
                context: nil
            ).height
        )
    }

    private static func money(_ value: Double?) -> String {
        guard let value, value.isFinite, value >= 0 else {
            return "À confirmer"
        }
        return String(
            format: "%.2f €",
            locale: Locale(identifier: "fr_FR"),
            value
        )
    }

    private static func companyLabel(_ company: SalaryCompanyV2) -> String {
        let name = company.name.trimmingCharacters(in: .whitespacesAndNewlines)
        let siret = company.siret.filter(\.isNumber)
        if !name.isEmpty, siret.count == 14 {
            return "\(name) — SIRET \(siret)"
        }
        if !name.isEmpty {
            return name
        }
        return company.id
    }
}
