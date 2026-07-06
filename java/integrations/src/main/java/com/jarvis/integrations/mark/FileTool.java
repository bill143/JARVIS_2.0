package com.jarvis.integrations.mark;

import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Reads a local text file so the model can summarize or answer questions about it (Code Helper /
 * File Processor). Read-only, size-capped, and refuses obviously binary content. The model does the
 * summarizing — this tool just supplies the bytes.
 */
public final class FileTool implements Tool {

    private static final long MAX_BYTES = 200_000;

    @Override
    public String name() {
        return "file_read";
    }

    @Override
    public String description() {
        return "Read a local text or code file so you can summarize, review, or answer questions "
                + "about it. Args: path (absolute or user-relative).";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object rawPath = call.arguments().get("path");
        if (rawPath == null || String.valueOf(rawPath).isBlank()) {
            return ToolResult.error("file_read needs a 'path' argument");
        }
        String raw = String.valueOf(rawPath).strip();
        Path path = raw.startsWith("~")
                ? Path.of(System.getProperty("user.home"), raw.substring(1))
                : Path.of(raw);
        try {
            if (!Files.exists(path)) {
                return ToolResult.error("no file at: " + path);
            }
            if (Files.isDirectory(path)) {
                return ToolResult.error(path + " is a folder, not a file");
            }
            long size = Files.size(path);
            byte[] bytes = Files.readAllBytes(path);
            if (isBinary(bytes)) {
                return ToolResult.error("that looks like a binary file; I can only read text/code");
            }
            String content = new String(bytes, StandardCharsets.UTF_8);
            boolean truncated = size > MAX_BYTES;
            if (truncated) {
                content = content.substring(0, (int) Math.min(content.length(), MAX_BYTES));
            }
            return ToolResult.ok("File: " + path + " (" + size + " bytes"
                    + (truncated ? ", showing first " + MAX_BYTES + ")" : ")") + "\n\n" + content);
        } catch (IOException e) {
            return ToolResult.error("could not read " + path + ": " + e.getMessage());
        }
    }

    /** Heuristic: a NUL byte in the first chunk means binary. */
    private static boolean isBinary(byte[] bytes) {
        int limit = Math.min(bytes.length, 8_000);
        for (int i = 0; i < limit; i++) {
            if (bytes[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
