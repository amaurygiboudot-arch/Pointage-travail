import Foundation
import PDFKit
import Vision
import ImageIO

/// Apple on-device recognition; source text is discarded after parsing.
enum SalaryPayslipLocalImporterV2 {
    enum ImportError: LocalizedError {
        case unsupported, tooLarge, unreadable
        var errorDescription: String? {
            switch self {
            case .unsupported: return "Choisissez un PDF ou une image lisible."
            case .tooLarge: return "Limite : 10 Mo et 10 pages par bulletin."
            case .unreadable: return "Lecture impossible. Saisissez les montants manuellement."
            }
        }
    }
    static func read(url: URL) throws -> [SalaryPayslipImportProposalV2] {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0, size <= 10 * 1024 * 1024 else { throw ImportError.tooLarge }
        var pages: [String] = []
        if url.pathExtension.lowercased() == "pdf" {
            guard let pdf = PDFDocument(url: url), pdf.pageCount > 0 else { throw ImportError.unreadable }
            guard pdf.pageCount <= 10 else { throw ImportError.tooLarge }
            for index in 0..<pdf.pageCount {
                guard let page = pdf.page(at: index) else { throw ImportError.unreadable }
                let embedded = page.string ?? ""
                if embedded.trimmingCharacters(in: .whitespacesAndNewlines).count > 30 { guard embedded.count <= 30_000 else { throw ImportError.tooLarge }; pages.append(embedded) }
                else {
                    let bounds = page.bounds(for: .mediaBox)
                    guard bounds.width.isFinite, bounds.height.isFinite, bounds.width > 0, bounds.height > 0 else { throw ImportError.unreadable }
                    let scale = min(2000 / bounds.width, 2000 / bounds.height)
                    let thumbnail = page.thumbnail(of: CGSize(width: bounds.width * scale, height: bounds.height * scale), for: .mediaBox)
                    guard let image = thumbnail.cgImage else { throw ImportError.unreadable }
                    pages.append(try recognize(image))
                }
            }
        } else {
            guard let source = CGImageSourceCreateWithURL(url as CFURL, nil),
                  let image = CGImageSourceCreateThumbnailAtIndex(source, 0, [kCGImageSourceCreateThumbnailFromImageAlways: true, kCGImageSourceThumbnailMaxPixelSize: 2000, kCGImageSourceCreateThumbnailWithTransform: true] as CFDictionary) else { throw ImportError.unsupported }
            pages = [try recognize(image)]
        }
        return SalaryPayslipImportParserV2.parse(pages: pages)
    }
    private static func recognize(_ image: CGImage) throws -> String {
        let request = VNRecognizeTextRequest()
        request.recognitionLevel = .accurate
        request.recognitionLanguages = ["fr-FR"]
        request.usesLanguageCorrection = false
        try VNImageRequestHandler(cgImage: image).perform([request])
        let text = (request.results ?? []).compactMap { $0.topCandidates(1).first?.string }.joined(separator: "\n")
        guard text.count <= 30_000 else { throw ImportError.tooLarge }
        return text
    }
}
