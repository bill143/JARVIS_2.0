package com.jarvis.integrations.mark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarvis.tools.Tool;
import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Live weather via Open-Meteo (free, no API key). Geocodes a city name, then reports current
 * conditions. Stdlib HTTP + Jackson; fetch seam keeps tests offline.
 */
public final class WeatherTool implements Tool {

    /** Fetch seam: URL in, JSON body out. */
    @FunctionalInterface
    public interface UrlFetcher {
        String fetch(String url) throws IOException, InterruptedException;
    }

    private static final Map<Integer, String> WMO = Map.ofEntries(
            Map.entry(0, "clear sky"), Map.entry(1, "mainly clear"), Map.entry(2, "partly cloudy"),
            Map.entry(3, "overcast"), Map.entry(45, "fog"), Map.entry(48, "rime fog"),
            Map.entry(51, "light drizzle"), Map.entry(53, "drizzle"), Map.entry(55, "dense drizzle"),
            Map.entry(56, "freezing drizzle"), Map.entry(57, "freezing drizzle"),
            Map.entry(61, "light rain"), Map.entry(63, "rain"), Map.entry(65, "heavy rain"),
            Map.entry(66, "freezing rain"), Map.entry(67, "freezing rain"),
            Map.entry(71, "light snow"), Map.entry(73, "snow"), Map.entry(75, "heavy snow"),
            Map.entry(77, "snow grains"), Map.entry(80, "rain showers"), Map.entry(81, "rain showers"),
            Map.entry(82, "violent rain showers"), Map.entry(85, "snow showers"),
            Map.entry(86, "heavy snow showers"), Map.entry(95, "thunderstorm"),
            Map.entry(96, "thunderstorm with hail"), Map.entry(99, "thunderstorm with hail"));

    private final UrlFetcher fetcher;
    private final ObjectMapper mapper = new ObjectMapper();

    public WeatherTool() {
        this(defaultFetcher());
    }

    public WeatherTool(UrlFetcher fetcher) {
        this.fetcher = Objects.requireNonNull(fetcher, "fetcher");
    }

    @Override
    public String name() {
        return "weather";
    }

    @Override
    public String description() {
        return "Current weather for a city. Args: city (e.g. 'Dallas').";
    }

    @Override
    public ToolResult execute(ToolCall call) {
        Object rawCity = call.arguments().get("city");
        if (rawCity == null || String.valueOf(rawCity).isBlank()) {
            return ToolResult.error("weather needs a 'city' argument");
        }
        String city = String.valueOf(rawCity).strip();
        try {
            JsonNode geo = mapper.readTree(fetcher.fetch(
                    "https://geocoding-api.open-meteo.com/v1/search?count=1&name="
                            + URLEncoder.encode(city, StandardCharsets.UTF_8)));
            JsonNode place = geo.path("results").path(0);
            if (place.isMissingNode()) {
                return ToolResult.error("could not find a city named '" + city + "'");
            }
            double lat = place.path("latitude").asDouble();
            double lon = place.path("longitude").asDouble();
            String name = place.path("name").asText(city);
            String region = place.path("admin1").asText("");

            JsonNode wx = mapper.readTree(fetcher.fetch(
                    "https://api.open-meteo.com/v1/forecast?temperature_unit=fahrenheit"
                            + "&wind_speed_unit=mph&current=temperature_2m,weather_code,wind_speed_10m"
                            + "&latitude=" + lat + "&longitude=" + lon)).path("current");
            double temp = wx.path("temperature_2m").asDouble();
            double wind = wx.path("wind_speed_10m").asDouble();
            String sky = WMO.getOrDefault(wx.path("weather_code").asInt(-1), "unclear conditions");

            String where = region.isBlank() ? name : name + ", " + region;
            return ToolResult.ok(String.format(
                    "%s: %.0f°F, %s, wind %.0f mph.", where, temp, sky, wind));
        } catch (IOException | RuntimeException e) {
            return ToolResult.error("weather lookup failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.error("weather lookup interrupted");
        }
    }

    private static UrlFetcher defaultFetcher() {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8)).build();
        return url -> {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> response =
                    client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IOException("weather backend returned " + response.statusCode());
            }
            return response.body();
        };
    }
}
