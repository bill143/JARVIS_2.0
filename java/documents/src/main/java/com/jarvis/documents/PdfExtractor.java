package com.jarvis.documents;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * Extracts text from a PDF using Apache PDFBox (Apache-2.0). This is the single class that touches
 * the PDFBox dependency; every other reader in this module is JDK-only or native, so the third-party
 * surface stays contained here.
 */
final class PdfExtractor {

    private PdfExtractor() {
    }

    static ExtractedText extract(byte[] bytes) {
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                // Loader opens empty-password PDFs; a real password lands here.
                return ExtractedText.unsupported("This PDF is password-protected, sir — I can't read it.");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc).strip();
            if (text.isEmpty()) {
                return new ExtractedText("pdf", "", false,
                        "No selectable text found — this looks like a scanned/image PDF (OCR isn't available).");
            }
            return ExtractedText.of("pdf", text);
        } catch (Exception e) {
            return ExtractedText.unsupported("Could not read the PDF: " + e.getMessage());
        }
    }
}
