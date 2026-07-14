package com.jarvis.documents;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

class FileTextExtractorTest {

    private final FileTextExtractor extractor = new FileTextExtractor();

    @Test
    void plainTextIsReadAsIs() {
        ExtractedText r = extractor.extract("notes.txt", "Hello, sir.".getBytes(StandardCharsets.UTF_8));
        assertEquals("text", r.kind());
        assertEquals("Hello, sir.", r.text());
        assertFalse(r.truncated());
    }

    @Test
    void csvAndJsonKeepDistinctKinds() {
        assertEquals("csv", extractor.extract("a.csv", "x,y\n1,2".getBytes(StandardCharsets.UTF_8)).kind());
        assertEquals("json", extractor.extract("a.json", "{\"a\":1}".getBytes(StandardCharsets.UTF_8)).kind());
        assertEquals("markdown", extractor.extract("a.md", "# Hi".getBytes(StandardCharsets.UTF_8)).kind());
    }

    @Test
    void docxParagraphsBecomeLines() {
        String documentXml = "<?xml version=\"1.0\"?>"
                + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
                + "<w:body>"
                + "<w:p><w:r><w:t>Hello JARVIS</w:t></w:r></w:p>"
                + "<w:p><w:r><w:t>Second </w:t></w:r><w:r><w:t>line</w:t></w:r></w:p>"
                + "</w:body></w:document>";
        byte[] docx = zip(Map.of("word/document.xml", documentXml));
        ExtractedText r = extractor.extract("report.docx", docx);
        assertEquals("docx", r.kind());
        assertEquals("Hello JARVIS\nSecond line", r.text());
    }

    @Test
    void xlsxResolvesSharedStringsAndCells() {
        String sst = "<sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<si><t>Name</t></si><si><t>Alice</t></si></sst>";
        String sheet = "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<sheetData>"
                + "<row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c><c r=\"B1\"><v>42</v></c></row>"
                + "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row>"
                + "</sheetData></worksheet>";
        byte[] xlsx = zip(Map.of("xl/sharedStrings.xml", sst, "xl/worksheets/sheet1.xml", sheet));
        ExtractedText r = extractor.extract("data.xlsx", xlsx);
        assertEquals("xlsx", r.kind());
        assertEquals("Name\t42\nAlice", r.text());
    }

    @Test
    void pdfTextIsExtracted() throws Exception {
        byte[] pdf = onePagePdf("Hello from a PDF");
        ExtractedText r = extractor.extract("brief.pdf", pdf);
        assertEquals("pdf", r.kind());
        assertTrue(r.text().contains("Hello from a PDF"), () -> "was: " + r.text());
    }

    @Test
    void binaryContentIsRejectedNotDumped() {
        byte[] binary = {0x00, 0x01, 0x02, (byte) 0xFF, 0x00, 0x10};
        ExtractedText r = extractor.extract("mystery.bin", binary);
        assertEquals("unsupported", r.kind());
        assertTrue(r.text().isEmpty());
        assertFalse(r.note().isEmpty());
    }

    @Test
    void legacyOfficeIsRejectedWithGuidance() {
        ExtractedText r = extractor.extract("old.doc", "anything".getBytes(StandardCharsets.UTF_8));
        assertEquals("unsupported", r.kind());
        assertTrue(r.note().contains(".docx"));
    }

    @Test
    void oversizeTextIsTruncatedAndFlagged() {
        byte[] big = "a".repeat(FileTextExtractor.MAX_CHARS + 500).getBytes(StandardCharsets.UTF_8);
        ExtractedText r = extractor.extract("big.txt", big);
        assertTrue(r.truncated());
        assertEquals(FileTextExtractor.MAX_CHARS, r.text().length());
        assertTrue(r.note().toLowerCase().contains("truncated"));
    }

    @Test
    void emptyFileIsHandled() {
        ExtractedText r = extractor.extract("empty.txt", new byte[0]);
        assertEquals("unsupported", r.kind());
    }

    // ---- helpers ----

    private static byte[] zip(Map<String, String> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                zos.putNextEntry(new ZipEntry(e.getKey()));
                zos.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    private static byte[] onePagePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
