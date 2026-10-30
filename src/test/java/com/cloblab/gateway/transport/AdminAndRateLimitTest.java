package com.cloblab.gateway.transport;

import com.cloblab.gateway.Gateway;
import com.cloblab.model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminAndRateLimitTest {
    private GatewayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void adminHaltResumeCancelAllReturnTinyJson() throws Exception {
        Path uiDir = Files.createTempDirectory("ui-admin");
        Files.writeString(uiDir.resolve("index.html"), "<html/>");
        Gateway gateway = new Gateway(2, 64);
        CountDownLatch resting = new CountDownLatch(1);
        gateway.subscribe(frame -> resting.countDown());
        server = new GatewayServer(gateway, uiDir);
        server.start(0);

        assertTrue(gateway.submitLimit(1, 42, Side.BUY, 100, 5).accepted());
        assertTrue(resting.await(2, TimeUnit.SECONDS));

        HttpClient http = HttpClient.newHttpClient();
        String base = "http://localhost:" + server.port();

        HttpResponse<String> halt = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/halt"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, halt.statusCode());
        Map<String, Object> haltJson = MiniJson.object(halt.body());
        assertEquals(true, haltJson.get("ok"));
        assertEquals(true, haltJson.get("halted"));
        assertTrue(gateway.halted());

        HttpResponse<String> resume = http.send(HttpRequest.newBuilder(URI.create(base + "/admin/resume"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resume.statusCode());
        assertEquals(false, MiniJson.object(resume.body()).get("halted"));
        assertFalse(gateway.halted());

        HttpResponse<String> cancel = http.send(HttpRequest.newBuilder(
                        URI.create(base + "/admin/cancelall?symbol=1"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, cancel.statusCode());
        Map<String, Object> cancelJson = MiniJson.object(cancel.body());
        assertEquals(true, cancelJson.get("ok"));
        assertTrue(((Number) cancelJson.get("cancelled")).intValue() >= 1);
        assertFalse(gateway.exchange().router().shard(1).engine().book().contains(42));

        HttpResponse<String> health = http.send(HttpRequest.newBuilder(URI.create(base + "/health"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode());
        assertEquals(true, MiniJson.object(health.body()).get("ok"));

        HttpResponse<String> stats = http.send(HttpRequest.newBuilder(URI.create(base + "/stats"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, stats.statusCode());
        Map<String, Object> statsJson = MiniJson.object(stats.body());
        assertTrue(statsJson.containsKey("p50us"));
        assertTrue(statsJson.containsKey("wsSessions"));
        assertTrue(statsJson.containsKey("rateLimitedSessions"));
        assertTrue(statsJson.containsKey("shards"));
    }

    @Test
    void tokenBucketExceedsAtBurst() {
        GatewayServer.TokenBucket bucket = new GatewayServer.TokenBucket(4);
        int ok = 0;
        int rejected = 0;
        for (int i = 0; i < 8; i++) {
            if (bucket.tryConsume(0)) {
                ok++;
            } else {
                rejected++;
            }
        }
        assertEquals(4, ok);
        assertEquals(4, rejected);
    }
}
