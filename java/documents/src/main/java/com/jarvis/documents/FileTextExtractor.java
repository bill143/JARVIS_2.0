package com.jarvis.documents;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Turns an uploaded file's bytes into plain text the assistant can read.
 *
 * <p>Routing is by file extension: {@code .txt/.md/.csv/.json} and other plain-text formats are read
 * natively as UTF-8; {@code .docx/.xlsx} go through the JDK-only {@link OoxmlExtractor}; {@code .pdf}
 * goes through {@link PdfExtractor} (Apache PDFBox). Anything else is attempted as UTF-8 text and,
 * if it looks binary, reported as unsupported rather than dumping garbage.
 *
 * <p>Two caps bound the work: a maximum input size and a maximum number of extracted characters
 * (beyond which the text is truncated and flagged). The extractor never throws — every failure path
 * returns an {@link ExtractedText} carrying an explanatory note.
 */
public final class FileTextExtractor {

    /** Largest upload accepted, in bytes (25 MiB). */
    public static final int MAX_INPUT_BYTES = 25 * 1024 * 1024;

    /** Largest amount of text returned, in characters; beyond this the result is truncated. */
    public static final int MAX_CHARS = 400_000;

    private static final Set<String> TEXT_EXT = Set.of(
            "txt", "text", "log", "md", "markdown", "csv", "tsv", "json", "xml", "yaml", "yml",
            "ini", "properties", "conf", "cfg", "html", "htm", "java", "py", "js", "ts", "sh",
            "sql", "css", "rtf");

    /**
     * Extracts text from {@code bytes}, using {@code filename} only to pick a reader.
     *
     * @return a never-{@code null} result; check {@link ExtractedText#kind()} and
     *     {@link ExtractedText#note()} for unsupported/partial cases
     */
    public ExtractedText extract(String filename, byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return ExtractedText.unsupported("The file was empty.");
        }
        if (bytes.length > MAX_INPUT_BYTES) {
            return ExtractedText.unsupported("That file is larger than the "
                    + (MAX_INPUT_BYTES / (1024 * 1024)) + " MB limit, sir.");
        }
        String ext = extensionOf(filename);
        ExtractedText result = switch (ext) {
            case "pdf" -> PdfExtractor.extract(bytes);
            case "docx" -> OoxmlExtractor.docx(bytes);
            case "xlsx" -> OoxmlExtractor.xlsx(bytes);
            case "doc", "xls", "ppt", "pptx" -> ExtractedText.unsupported(
                    "Legacy/binary Office files (." + ext + ") aren't supported — please save as "
                    + "the modern format (.docx/.xlsx) or PDF.");
            default -> {
                if (TEXT_EXT.contains(ext) || looksTextual(bytes)) {
                    yield ExtractedText.of(kindForExt(ext), new String(bytes, StandardCharsets.UTF_8));
                }
                yield ExtractedText.unsupported("I can't read ." + (ext.isEmpty() ? "(unknown)" : ext)
                        + " files — try text, Markdown, CSV, JSON, PDF, .docx or .xlsx.");
            }
        };
        return cap(result);
    }

    private static ExtractedText cap(ExtractedText r) {
        if (r.text().length() <= MAX_CHARS) {
            return r;
        }
        String cut = r.text().substring(0, MAX_CHARS);
        String note = (r.note().isEmpty() ? "" : r.note() + " ")
                + "Truncated to the first " + MAX_CHARS + " characters.";
        return new ExtractedText(r.kind(), cut, true, note);
    }

    /** Lowercased extension without the dot, or empty when there is none. */
    public static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        String name = filename;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase();
    }

    private static String kindForExt(String ext) {
        return switch (ext) {
            case "md", "markdown" -> "markdown";
            case "csv", "tsv" -> "csv";
            case "json" -> "json";
            default -> "text";
        };
    }

    /** Heuristic: treat the sample as text when it has no NUL bytes and is mostly printable. */
    private static boolean looksTextual(byte[] bytes) {
        int sample = Math.min(bytes.length, 4096);
        int suspicious = 0;
        for (int i = 0; i < sample; i++) {
            int b = bytes[i] & 0xFF;
            if (b == 0) {
                return false;
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20)) {
                suspicious++;
            }
        }
        return suspicious <= sample / 20;
    }
}
