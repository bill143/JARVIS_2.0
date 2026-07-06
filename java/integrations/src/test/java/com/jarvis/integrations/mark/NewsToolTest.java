package com.jarvis.integrations.mark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.tools.ToolCall;
import com.jarvis.tools.ToolResult;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NewsToolTest {

    private static final String FEED = """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0"><channel>
              <item><title>Local team wins big</title>
                <link>https://example.com/articles/team-wins</link>
                <source url="https://example.com">Example News</source></item>
              <item><title>Markets rally on jobs report</title>
                <link>https://example.com/articles/markets-rally</link></item>
            </channel></rss>""";

    @Test
    void parsesRealArticlesWithTitlesSourcesAndLinks() {
        NewsTool tool = new NewsTool(url -> FEED);

        ToolResult result = tool.execute(new ToolCall("news_search", Map.of("query", "sports")));
        assertTrue(result.success());
        assertTrue(result.output().contains("1. Local team wins big (Example News)"));
        assertTrue(result.output().contains("https://example.com/articles/team-wins"));
        assertTrue(result.output().contains("2. Markets rally on jobs report"));
    }

    @Test
    void firstWorkingBackendWinsWhenTheOtherFails() {
        // One backend hard-fails; the race must still produce results from the other.
        NewsTool tool = new NewsTool(url -> {
            if (url.contains("news.google.com")) {
                throw new IOException("503 from google");
            }
            return FEED;
        });

        ToolResult result = tool.execute(ToolCall.of("news_search"));
        assertTrue(result.success());
        assertTrue(result.output().contains("Local team wins big"));
    }

    @Test
    void slowBackendDoesNotBlockAFastOne() {
        NewsTool tool = new NewsTool(url -> {
            if (url.contains("bing.com")) {
                Thread.sleep(5_000);
                throw new IOException("too slow anyway");
            }
            return FEED;
        });

        long start = System.nanoTime();
        ToolResult result = tool.execute(ToolCall.of("news_search"));
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(result.success());
        assertTrue(elapsedMillis < 4_000, "fast backend should win quickly, took " + elapsedMillis + "ms");
    }

    @Test
    void bothBackendsFailingIsAGracefulError() {
        NewsTool tool = new NewsTool(url -> {
            throw new IOException("backend down");
        });

        ToolResult result = tool.execute(ToolCall.of("news_search"));
        assertFalse(result.success());
        assertTrue(result.error().contains("no news backend"));
    }

    @Test
    void emptyFeedCountsAsFailureNotSuccess() {
        String empty = "<?xml version=\"1.0\"?><rss><channel></channel></rss>";
        NewsTool tool = new NewsTool(url -> empty);

        assertFalse(tool.execute(ToolCall.of("news_search")).success());
    }

    @Test
    void toolIdentity() {
        NewsTool tool = new NewsTool(url -> FEED);
        assertEquals("news_search", tool.name());
        assertTrue(tool.description().contains("news"));
    }
}
