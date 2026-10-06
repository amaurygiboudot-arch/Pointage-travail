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
        let name = "AGKGMG_Salaire_\(snapshot.period.description)_\(suffix).pdf"
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
            let green = UIColor(red: 0.13, green: 0.34, blue: 0.27, alpha: 1)
            let paleGreen = UIColor(red: 0.92, green: 0.96, blue: 0.93, alpha: 1)
            let ink = UIColor(red: 0.15, green: 0.20, blue: 0.18, alpha: 1)
            var y: CGFloat = 112
            var pageNumber = 0
            var rowNumber = 0

            func fill(_ rect: CGRect, _ color: UIColor) {
                context.cgContext.setFillColor(color.cgColor)
                context.cgContext.fill(rect)
            }

            func beginPage() {
                context.beginPage()
                pageNumber += 1
                y = 112
                fill(CGRect(x: 0, y: 0, width: 595, height: 92), green)
                draw(
                    "AGKGMG",
                    x: 32, y: 24, width: 531,
                    font: .boldSystemFont(ofSize: 21), color: .white
                )
                draw(
                    "ESTIMATION DE SALAIRE • \(snapshot.period.description)",
                    x: 32, y: 55, width: 531,
                    font: .boldSystemFont(ofSize: 11), color: .white
                )
                fill(CGRect(x: 32, y: 803, width: 531, height: 1), paleGreen)
                draw(
                    "Document personnel d'estimation — non officiel",
                    x: 32, y: 812, width: 440,
                    font: .systemFont(ofSize: 8), color: ink
                )
                draw(
                    "Page \(pageNumber)",
                    x: 475, y: 812, width: 88,
                    font: .systemFont(ofSize: 8), alignment: .right, color: ink
                )
            }

            func ensure(_ height: CGFloat) {
                if y + height > 788 {
                    beginPage()
                }
            }

            func row(_ label: String, _ value: String) {
                let labelFont = UIFont.systemFont(ofSize: 10)
                let valueFont = UIFont.boldSystemFont(ofSize: 10)
                let height = max(
                    28,
                    max(textHeight(label, width: 244, font: labelFont),
                        textHeight(value, width: 259, font: valueFont)) + 14
                )
                ensure(height)
                if rowNumber.isMultiple(of: 2) {
                    fill(CGRect(x: 32, y: y, width: 531, height: height), paleGreen)
                }
                draw(label, x: 40, y: y + 7, width: 244, font: labelFont, color: ink)
                draw(value, x: 296, y: y + 7, width: 259, font: valueFont,
                     alignment: .right, color: ink)
                rowNumber += 1
                y += height
            }

            func section(_ title: String) {
                // Reserve room for the title and the first row together.
                ensure(76)
                y += 12
                fill(CGRect(x: 32, y: y, width: 531, height: 26), green)
                draw(title, x: 40, y: y + 6, width: 515,
                     font: .boldSystemFont(ofSize: 10), color: .white)
                y += 26
                rowNumber = 0
            }

            beginPage()

            section("PÉRIODE")
            row("Mois", snapshot.period.description)
            row(
                "Entreprise",
                company.map(companyLabel) ?? "À confirmer"
            )

            section("SYNTHÈSE DU SALAIRE")
            row("Brut social estimé", money(snapshot.socialGross))
            row("Net estimé avant impôt", money(snapshot.netBeforeIncomeTax))
            row("Net imposable estimé", money(snapshot.netTaxable))
            row(
                "Prélèvement à la source",
                snapshot.incomeTax.map { "-\(money($0))" } ?? "À confirmer"
            )
            row("Net après impôt", money(snapshot.netAfterIncomeTax))

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
                "AGKGMG n'utilise aucune valeur de remplacement lorsqu'une donnée nécessaire n'est pas certifiable.",
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
        alignment: NSTextAlignment = .left,
        color: UIColor = UIColor(red: 0.15, green: 0.20, blue: 0.18, alpha: 1)
    ) {
        let paragraph = NSMutableParagraphStyle()
        paragraph.alignment = alignment
        paragraph.lineBreakMode = .byWordWrapping
        (value as NSString).draw(
            in: CGRect(x: x, y: y, width: width, height: 10_000),
            withAttributes: [
                .font: font,
                .foregroundColor: color,
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
