package com.jarvis.integrations.mark;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for the Step 21 awareness tools: weather, web search, file read, hardware. */
class NewToolsTest {

    // ---- Weather ----
    private static final String GEO = """
            {"results":[{"name":"Dallas","admin1":"Texas","latitude":32.78,"longitude":-96.8}]}""";
    private static final String FORECAST = """
            {"current":{"temperature_2m":81.0,"weather_code":2,"wind_speed_10m":9.0}}""";

    @Test
    void weatherReportsCurrentConditions() {
        WeatherTool tool = new WeatherTool(url -> url.contains("geocoding") ? GEO : FORECAST);
        ToolResult result = tool.execute(new ToolCall("weather", Map.of("city", "Dallas")));
        assertTrue(result.success());
        assertTrue(result.output().contains("Dallas, Texas"));
        assertTrue(result.output().contains("81"));
        assertTrue(result.output().contains("partly cloudy"));
    }

    @Test
    void weatherUnknownCityFailsGracefully() {
        WeatherTool tool = new WeatherTool(url -> "{\"results\":[]}");
        assertFalse(tool.execute(new ToolCall("weather", Map.of("city", "Xyzzy"))).success());
    }

    @Test
    void weatherNeedsACity() {
        assertFalse(new WeatherTool(url -> GEO).execute(ToolCall.of("weather")).success());
    }

    // ---- Web search ----
    private static final String DDG_HTML = """
            <html><body>
            <a rel="nofollow" class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa">First Result</a>
            <a rel="nofollow" class="result-link" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fb">Second Result</a>
            </body></html>""";

    @Test
    void webSearchParsesAndUnwrapsResults() {
        WebSearchTool tool = new WebSearchTool(url -> DDG_HTML);
        ToolResult result = tool.execute(new ToolCall("web_search", Map.of("query", "java records")));
        assertTrue(result.success());
        assertTrue(result.output().contains("First Result"));
        assertTrue(result.output().contains("https://example.com/a"));
        assertTrue(result.output().contains("[search]"));
    }

    @Test
    void webSearchModeShapesTheQuery() {
        StringBuilder seenUrl = new StringBuilder();
        WebSearchTool tool = new WebSearchTool(url -> {
            seenUrl.append(url);
            return DDG_HTML;
        });
        tool.execute(new ToolCall("web_search", Map.of("query", "laptop", "mode", "price")));
        assertTrue(seenUrl.toString().contains("price"));
        assertTrue(seenUrl.toString().contains("buy"));
    }

    @Test
    void webSearchNeedsAQuery() {
        assertFalse(new WebSearchTool(url -> DDG_HTML).execute(ToolCall.of("web_search")).success());
    }

    // ---- File read ----
    @TempDir
    Path dir;

    @Test
    void fileReadReturnsTextContent() throws IOException {
        Path file = dir.resolve("notes.txt");
        Files.writeString(file, "hello from a file");
        ToolResult result = new FileTool().execute(
                new ToolCall("file_read", Map.of("path", file.toString())));
        assertTrue(result.success());
        assertTrue(result.output().contains("hello from a file"));
    }

    @Test
    void fileReadRejectsBinaryAndMissing() throws IOException {
        Path binary = dir.resolve("blob.bin");
        Files.write(binary, new byte[] {1, 0, 2, 0, 3});
        assertFalse(new FileTool().execute(
                new ToolCall("file_read", Map.of("path", binary.toString()))).success());
        assertFalse(new FileTool().execute(
                new ToolCall("file_read", Map.of("path", dir.resolve("nope.txt").toString()))).success());
    }

    // ---- Hardware ----
    @Test
    void hardwareStatusReportsCpuAndRam() {
        ToolResult result = new HardwareTool().execute(ToolCall.of("hardware_status"));
        assertTrue(result.success());
        assertTrue(result.output().contains("CPU"));
        assertTrue(result.output().contains("RAM"));
    }
}
