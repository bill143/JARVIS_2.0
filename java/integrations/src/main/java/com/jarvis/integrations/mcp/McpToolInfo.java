package com.jarvis.integrations.mcp;

/**
 * A tool advertised by an MCP server's {@code tools/list} response.
 *
 * @param name        the tool's name (its JSON-RPC {@code tools/call} identifier)
 * @param description human-readable description (may be empty)
 */
public record McpToolInfo(String name, String description) {
}
