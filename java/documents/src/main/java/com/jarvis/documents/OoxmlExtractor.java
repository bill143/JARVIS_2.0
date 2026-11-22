package com.jarvis.documents;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;

/**
 * Extracts readable text from Office Open XML documents ({@code .docx} / {@code .xlsx}) using only
 * the JDK — a {@link ZipInputStream} to unpack the package and StAX to walk the XML parts. No third
 * party library is involved, so Office support adds no dependency to the whitelist.
 *
 * <p>The parser is hardened against zip bombs (a decompressed-size cap) and XXE (DTDs and external
 * entities are disabled on the {@link XMLInputFactory}).
 */
final class OoxmlExtractor {

    /** Hard ceiling on total decompressed bytes read from a single package (zip-bomb guard). */
    private static final long MAX_TOTAL_DECOMPRESSED = 80L * 1024 * 1024;

    private OoxmlExtractor() {
    }

    static ExtractedText docx(byte[] zipBytes) {
        Map<String, byte[]> parts;
        try {
            parts = readEntries(zipBytes);
        } catch (IOException e) {
            return ExtractedText.unsupported("Could not read the .docx package: " + e.getMessage());
        }
        byte[] doc = parts.get("word/document.xml");
        if (doc == null) {
            return ExtractedText.unsupported("Not a Word document (missing word/document.xml).");
        }
        StringBuilder out = new StringBuilder();
        try {
            XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(doc));
            boolean inText = false;
            while (r.hasNext()) {
                int ev = r.next();
                if (ev == XMLStreamConstants.START_ELEMENT) {
                    String ln = r.getLocalName();
                    if (ln.equals("t")) {
                        inText = true;
                    } else if (ln.equals("tab")) {
                        out.append('\t');
                    } else if (ln.equals("br") || ln.equals("cr")) {
                        out.append('\n');
                    }
                } else if (ev == XMLStreamConstants.CHARACTERS) {
                    if (inText) {
                        out.append(r.getText());
                    }
                } else if (ev == XMLStreamConstants.END_ELEMENT) {
                    String ln = r.getLocalName();
                    if (ln.equals("t")) {
                        inText = false;
                    } else if (ln.equals("p")) {
                        out.append('\n');
                    }
                }
            }
            r.close();
        } catch (Exception e) {
            return ExtractedText.unsupported("Could not parse the Word document: " + e.getMessage());
        }
        return ExtractedText.of("docx", out.toString().strip());
    }

    static ExtractedText xlsx(byte[] zipBytes) {
        Map<String, byte[]> parts;
        try {
            parts = readEntries(zipBytes);
        } catch (IOException e) {
            return ExtractedText.unsupported("Could not read the .xlsx package: " + e.getMessage());
        }
        List<String> shared = new ArrayList<>();
        byte[] sst = parts.get("xl/sharedStrings.xml");
        if (sst != null) {
            try {
                readSharedStrings(sst, shared);
            } catch (Exception e) {
                return ExtractedText.unsupported("Could not parse the workbook's shared strings: "
                        + e.getMessage());
            }
        }
        // Worksheets in filename order (sheet1.xml, sheet2.xml, ...).
        List<String> sheetNames = new ArrayList<>(parts.keySet());
        sheetNames.removeIf(n -> !(n.startsWith("xl/worksheets/") && n.endsWith(".xml")));
        sheetNames.sort(String::compareTo);
        if (sheetNames.isEmpty()) {
            return ExtractedText.unsupported("Not a spreadsheet (no worksheets found).");
        }
        StringBuilder out = new StringBuilder();
        int idx = 0;
        for (String sheet : sheetNames) {
            idx++;
            if (sheetNames.size() > 1) {
                out.append(out.isEmpty() ? "" : "\n").append("# Sheet ").append(idx).append('\n');
            }
            try {
                readSheet(parts.get(sheet), shared, out);
            } catch (Exception e) {
                return ExtractedText.unsupported("Could not parse a worksheet: " + e.getMessage());
            }
        }
        return ExtractedText.of("xlsx", out.toString().strip());
    }

    /** Reads {@code <si>} shared-string entries, concatenating every {@code <t>} run inside each. */
    private static void readSharedStrings(byte[] xml, List<String> out) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        StringBuilder cur = null;
        boolean inT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if (ln.equals("si")) {
                    cur = new StringBuilder();
                } else if (ln.equals("t")) {
                    inT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS && inT && cur != null) {
                cur.append(r.getText());
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if (ln.equals("t")) {
                    inT = false;
                } else if (ln.equals("si")) {
                    out.add(cur == null ? "" : cur.toString());
                    cur = null;
                }
            }
        }
        r.close();
    }

    /** Walks a worksheet, emitting one tab-separated line per row. */
    private static void readSheet(byte[] xml, List<String> shared, StringBuilder out) throws Exception {
        XMLStreamReader r = factory().createXMLStreamReader(new ByteArrayInputStream(xml));
        List<String> row = new ArrayList<>();
        String cellType = null;
        StringBuilder value = null;
        boolean inV = false;
        boolean inInlineT = false;
        while (r.hasNext()) {
            int ev = r.next();
            if (ev == XMLStreamConstants.START_ELEMENT) {
                String ln = r.getLocalName();
                if (ln.equals("row")) {
                    row.clear();
                } else if (ln.equals("c")) {
                    cellType = r.getAttributeValue(null, "t");
                    value = new StringBuilder();
                } else if (ln.equals("v")) {
                    inV = true;
                } else if (ln.equals("t")) {
                    inInlineT = true;
                }
            } else if (ev == XMLStreamConstants.CHARACTERS) {
                if ((inV || inInlineT) && value != null) {
                    value.append(r.getText());
                }
            } else if (ev == XMLStreamConstants.END_ELEMENT) {
                String ln = r.getLocalName();
                if (ln.equals("v")) {
                    inV = false;
                } else if (ln.equals("t")) {
                    inInlineT = false;
                } else if (ln.equals("c")) {
                    row.add(resolveCell(cellType, value, shared));
                    cellType = null;
                    value = null;
                } else if (ln.equals("row")) {
                    while (!row.isEmpty() && row.get(row.size() - 1).isEmpty()) {
                        row.remove(row.size() - 1);
                    }
                    if (!row.isEmpty()) {
                        out.append(String.join("\t", row)).append('\n');
                    }
                }
            }
        }
        r.close();
    }

    private static String resolveCell(String type, StringBuilder value, List<String> shared) {
        String raw = value == null ? "" : value.toString();
        if ("s".equals(type)) {
            try {
                int i = Integer.parseInt(raw.trim());
                return i >= 0 && i < shared.size() ? shared.get(i) : "";
            } catch (NumberFormatException e) {
                return "";
            }
        }
        return raw;
    }

    private static Map<String, byte[]> readEntries(byte[] zipBytes) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        long total = 0;
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            byte[] buf = new byte[8192];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                String name = e.getName();
                // Only the parts we actually read, to bound work on hostile archives.
                boolean wanted = name.equals("word/document.xml")
                        || name.equals("xl/sharedStrings.xml")
                        || (name.startsWith("xl/worksheets/") && name.endsWith(".xml"));
                if (!wanted) {
                    continue;
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                int n;
                while ((n = zin.read(buf)) != -1) {
                    total += n;
                    if (total > MAX_TOTAL_DECOMPRESSED) {
                        throw new IOException("archive too large when decompressed");
                    }
                    bos.write(buf, 0, n);
                }
                out.put(name, bos.toByteArray());
            }
        }
        return out;
    }

    private static XMLInputFactory factory() {
        XMLInputFactory f = XMLInputFactory.newInstance();
        f.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        f.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        return f;
    }
}
