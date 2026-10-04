package com.cloblab.gateway.transport;

import com.cloblab.gateway.Gateway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Loopback integration test: raw-socket WS handshake, submit an order, assert
 * ack + l2/trade frames with increasing seq (ADR-0002 validation).
 */
class GatewayServerTest {
    private GatewayServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void wsHandshakeAckAndMarketDataFlow() throws Exception {
        Path uiDir = Files.createTempDirectory("ui");
        Files.writeString(uiDir.resolve("index.html"), "<html>ui</html>");
        server = new GatewayServer(new Gateway(1, 64), uiDir);
        server.start(0); // random free port

        // Static UI serving
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> page = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("ui"));

        Files.createDirectories(uiDir.resolve("assets"));
        Files.writeString(uiDir.resolve("assets").resolve("app-hash.js"), "export default 1");
        HttpResponse<String> asset = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/assets/app-hash.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, asset.statusCode());
        assertTrue(asset.headers().firstValue("Content-Type").orElse("").contains("javascript"));
        assertEquals("export default 1", asset.body());

        HttpResponse<String> nested = http.send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + server.port() + "/assets/nested/x.js")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(404, nested.statusCode());

        // Raw-socket WS handshake on the dedicated WS port
        try (Socket sock = new Socket("localhost", server.wsPort())) {
            sock.setSoTimeout(10_000);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();

            String wsKey = Base64.getEncoder().encodeToString(new byte[16]);
            out.write(("GET /ws?symbol=0 HTTP/1.1\r\n"
                    + "Host: localhost\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            String response = readUntil(in, "\r\n\r\n");
            assertTrue(response.contains("101"), "expected 101 switching protocols, got: " + response);
            String expected = WsAcceptClient.compute(wsKey);
            assertTrue(response.contains(expected), "Sec-WebSocket-Accept mismatch:\n" + response);

            // hello frame arrives right after handshake
            Map<String, Object> hello = MiniJson.object(readTextFrame(in));
            assertEquals("hello", hello.get("t"));
            assertEquals(1L, ((Number) hello.get("seq")).longValue());

            // submit a resting order -> ack, then l2 frame reflecting the book
            clientText(out, "{\"op\":\"limit\",\"side\":\"SELL\",\"px\":100,\"qty\":5,\"orderId\":1}");
            Map<String, Object> ack = MiniJson.object(readTextFrame(in));
            assertEquals("ack", ack.get("t"));
            assertEquals(true, ack.get("accepted"));

            Map<String, Object> l2 = MiniJson.object(readTextFrame(in));
            assertEquals("l2", l2.get("t"));
            assertTrue(((Number) l2.get("seq")).longValue() > 1);
            String l2raw = l2.toString();
            assertTrue(l2raw.contains("100"), "l2 must contain the resting price");

            // cross it -> trade frame
            clientText(out, "{\"op\":\"limit\",\"side\":\"BUY\",\"px\":100,\"qty\":5,\"orderId\":2}");
            Map<String, Object> ack2 = MiniJson.object(readTextFrame(in));
            assertEquals(true, ack2.get("accepted"));

            Map<String, Object> tradeOrL2 = MiniJson.object(readTextFrame(in));
            long previousSeq = ((Number) l2.get("seq")).longValue();
            if ("trade".equals(tradeOrL2.get("t"))) {
                assertEquals(2L, ((Number) tradeOrL2.get("taker")).longValue());
                assertEquals(1L, ((Number) tradeOrL2.get("maker")).longValue());
            } else {
                // l2 flush preceded trade; ensure seq keeps increasing
                assertTrue(((Number) tradeOrL2.get("seq")).longValue() > previousSeq);
            }
        }
    }

    @Test
    void rejectOverWsDoesNotThrowAndCarriesReason() throws Exception {
        Path uiDir = Files.createTempDirectory("ui");
        server = new GatewayServer(new Gateway(1, 64), uiDir);
        server.start(0);

        try (Socket sock = new Socket("localhost", server.wsPort())) {
            sock.setSoTimeout(10_000);
            OutputStream out = sock.getOutputStream();
            InputStream in = sock.getInputStream();
            String wsKey = Base64.getEncoder().encodeToString(new byte[16]);
            out.write(("GET /ws?symbol=0 HTTP/1.1\r\nHost: h\r\nUpgrade: websocket\r\n"
                    + "Connection: Upgrade\r\nSec-WebSocket-Key: " + wsKey + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            readUntil(in, "\r\n\r\n");
            readTextFrame(in); // hello

            clientText(out, "{\"op\":\"limit\",\"side\":\"BUY\",\"px\":100,\"qty\":0,\"orderId\":1}");
            Map<String, Object> ack = MiniJson.object(readTextFrame(in));
            assertEquals(false, ack.get("accepted"));
            assertNotNull(ack.get("reason"));
        }
    }

    @Test
    void frameCodecRoundTripWithClientMask() throws IOException {
        byte[] mask = {1, 2, 3, 4};
        String text = "{\"op\":\"limit\",\"qty\":9007199254740993}";
        byte[] encoded = WsFrames.encode(WsFrames.OP_TEXT, text.getBytes(StandardCharsets.UTF_8), mask);

        InputStream in = new java.io.ByteArrayInputStream(encoded);
        WsFrames.Frame frame = WsFrames.read(in);
        assertEquals(WsFrames.OP_TEXT, frame.opcode);
        assertEquals(text, new String(frame.payload, StandardCharsets.UTF_8));

        // 16-bit length path
        byte[] big = new byte[300];
        java.util.Arrays.fill(big, (byte) 'x');
        byte[] enc2 = WsFrames.encode(WsFrames.OP_TEXT, big, null);
        WsFrames.Frame f2 = WsFrames.read(new java.io.ByteArrayInputStream(enc2));
        assertEquals(300, f2.payload.length);
    }

    @Test
    void staticAllowlistAcceptsIndexAndAssetFilenamesOnly() {
        assertEquals("index.html", GatewayServer.allowedStaticPath("/"));
        assertEquals("index.html", GatewayServer.allowedStaticPath("/index.html"));
        assertEquals("assets/index-abc.js", GatewayServer.allowedStaticPath("/assets/index-abc.js"));
        assertEquals("assets/index-abc.css", GatewayServer.allowedStaticPath("/assets/index-abc.css"));
        assertEquals(null, GatewayServer.allowedStaticPath("/assets/../index.html"));
        assertEquals(null, GatewayServer.allowedStaticPath("/assets/nested/x.js"));
        assertEquals(null, GatewayServer.allowedStaticPath("/app.js"));
        assertEquals("text/javascript; charset=UTF-8", GatewayServer.contentType("assets/a.js"));
        assertEquals("text/css; charset=UTF-8", GatewayServer.contentType("assets/a.css"));
    }

    private static void clientText(OutputStream out, String json) throws IOException {
        // client frames must be masked per RFC 6455 (browsers always mask; so do we)
        byte[] mask = {0x11, 0x22, 0x33, 0x44};
        out.write(WsFrames.encode(WsFrames.OP_TEXT, json.getBytes(StandardCharsets.UTF_8), mask));
        out.flush();
    }

    private static String readTextFrame(InputStream in) throws IOException {
        WsFrames.Frame frame = WsFrames.read(in);
        assertEquals(WsFrames.OP_TEXT, frame.opcode);
        return new String(frame.payload, StandardCharsets.UTF_8);
    }

    private static String readUntil(InputStream in, String terminator) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            sb.append((char) c);
            if (sb.toString().endsWith(terminator)) {
                break;
            }
        }
        return sb.toString();
    }

    /** Client-side replica of the accept-key computation (validates the server). */
    private static final class WsAcceptClient {
        static String compute(String key) {
            try {
                return Base64.getEncoder().encodeToString(
                        MessageDigest.getInstance("SHA-1")
                                .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11")
                                        .getBytes(StandardCharsets.US_ASCII)));
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}