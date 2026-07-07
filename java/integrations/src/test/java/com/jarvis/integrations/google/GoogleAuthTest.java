package com.jarvis.integrations.google;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jarvis.memory.InMemoryStore;
import com.jarvis.memory.MemoryStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GoogleAuthTest {

    @Test
    void authUrlCarriesClientRedirectAndScopes() {
        GoogleAuth auth = new GoogleAuth("cid", "secret", new InMemoryStore<>(), (u, f) -> "{}");
        String url = auth.authUrl("http://127.0.0.1:9000");
        assertTrue(url.startsWith(GoogleAuth.AUTH_URL));
        assertTrue(url.contains("client_id=cid"));
        assertTrue(url.contains("access_type=offline"));
        assertTrue(url.contains("gmail.modify"));
        assertTrue(url.contains("calendar"));
    }

    @Test
    void exchangeStoresRefreshTokenAndConnects() throws Exception {
        MemoryStore<String> store = new InMemoryStore<>();
        GoogleAuth auth = new GoogleAuth("cid", "secret", store,
                (u, f) -> "{\"refresh_token\":\"R1\",\"access_token\":\"A1\",\"expires_in\":3600}");
        assertFalse(auth.isConnected());

        auth.exchangeCode("the-code", "http://127.0.0.1:9000");
        assertTrue(auth.isConnected());
        assertEquals("R1", store.get("google", "refresh_token").orElseThrow().value());
        assertEquals("A1", auth.accessToken());   // cached from the exchange, no extra call
    }

    @Test
    void exchangeWithoutRefreshTokenFails() {
        GoogleAuth auth = new GoogleAuth("cid", "secret", new InMemoryStore<>(),
                (u, f) -> "{\"access_token\":\"A1\"}");
        assertThrows(IOException.class, () -> auth.exchangeCode("c", "r"));
    }

    @Test
    void accessTokenRefreshesUsingStoredRefreshTokenAndCaches() throws Exception {
        MemoryStore<String> store = new InMemoryStore<>();
        store.put("google", "refresh_token", "R1");
        List<Map<String, String>> calls = new ArrayList<>();
        GoogleAuth auth = new GoogleAuth("cid", "secret", store, (u, f) -> {
            calls.add(f);
            return "{\"access_token\":\"A-fresh\",\"expires_in\":3600}";
        });

        assertEquals("A-fresh", auth.accessToken());
        assertEquals("A-fresh", auth.accessToken());        // second call served from cache
        assertEquals(1, calls.size());
        assertEquals("refresh_token", calls.get(0).get("grant_type"));
        assertEquals("R1", calls.get(0).get("refresh_token"));
    }

    @Test
    void accessTokenWithoutConnectionFails() {
        GoogleAuth auth = new GoogleAuth("cid", "secret", new InMemoryStore<>(), (u, f) -> "{}");
        assertThrows(IOException.class, auth::accessToken);
    }

    @Test
    void disconnectForgetsTheRefreshToken() throws Exception {
        MemoryStore<String> store = new InMemoryStore<>();
        GoogleAuth auth = new GoogleAuth("cid", "secret", store,
                (u, f) -> "{\"refresh_token\":\"R1\",\"access_token\":\"A1\",\"expires_in\":3600}");
        auth.exchangeCode("c", "r");
        assertTrue(auth.isConnected());
        auth.disconnect();
        assertFalse(auth.isConnected());
    }
}
