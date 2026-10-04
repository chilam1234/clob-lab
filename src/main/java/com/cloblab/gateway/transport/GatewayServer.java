package com.cloblab.gateway.transport;

import com.cloblab.gateway.Gateway;
import com.cloblab.gateway.GatewayFrame;
import com.cloblab.gateway.GatewayResult;
import com.cloblab.marketdata.BookLevel;
import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.Side;
import com.cloblab.model.Trade;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Trade UI server: static assets on the JDK {@link HttpServer}, RFC 6455 WebSocket
 * traffic on a dedicated plain {@link ServerSocket}, one shared {@link Gateway}.
 *
 * <p>Why two sockets: upgrading inside com.sun.net.httpserver requires reflective access
 * to sun.net.httpserver, which JDK 21+ denies (jdk.httpserver is not open —
 * InaccessibleObjectException). Moving the WS endpoint to a plain ServerSocket keeps the
 * zero-dependency decision (ADR-0002) with no JVM flags. The client discovers the WS port
 * at load time via {@code /wsport}; index.html ships with a {@value #WS_PORT_PLACEHOLDER}
 * placeholder replaced at serve time so the first paint already knows it.
 */
public final class GatewayServer {
    private static final int L2_DEPTH = 5;
    private static final int DEFAULT_SYMBOLS = 1;
    private static final int DEFAULT_RING = 1024;
    static final String WS_PORT_PLACEHOLDER = "__WS_PORT__";

    private final Gateway gateway;
    private final Path uiDir;
    private final CopyOnWriteArrayList<WsSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();

    private HttpServer httpServer;
    private ServerSocket wsListener;
    private ExecutorService executor;
    private int port;
    private int wsPort;

    public GatewayServer() {
        this(new Gateway(DEFAULT_SYMBOLS, DEFAULT_RING), defaultUiDir());
    }

    public GatewayServer(Gateway gateway, Path uiDir) {
        this.gateway = gateway;
        this.uiDir = uiDir;
    }

    /** Prefer the built React app (ui/dist), fall back to docs/ui stub. */
    public static Path defaultUiDir() {
        Path dist = Path.of("ui", "dist");
        if (Files.isDirectory(dist)) {
            return dist;
        }
        return Path.of("docs", "ui");
    }

    public int port() {
        return port;
    }

    public int wsPort() {
        return wsPort;
    }

    public Gateway gateway() {
        return gateway;
    }

    public synchronized void start(int listenPort) throws IOException {
        if (running.get()) {
            throw new IllegalStateException("already started");
        }
        if (subscribed.compareAndSet(false, true)) {
            gateway.subscribe(this::onGatewayFrame);
        }
        gateway.start();
        executor = Executors.newCachedThreadPool(namedThreads());

        httpServer = HttpServer.create(new InetSocketAddress(listenPort), 0);
        httpServer.createContext("/", this::handle);
        httpServer.setExecutor(executor);
        httpServer.start();
        port = httpServer.getAddress().getPort();

        // WS endpoint: listenPort+1 when a fixed port was requested (predictable for
        // docker/firewall), any free port when the HTTP port was auto-assigned (tests).
        wsListener = new ServerSocket(listenPort == 0 ? 0 : listenPort + 1);
        wsPort = wsListener.getLocalPort();
        Thread acceptor = new Thread(this::acceptLoop, "clob-ws-accept");
        acceptor.setDaemon(true);
        acceptor.start();

        running.set(true);
    }

    public synchronized void stop() {
        running.set(false);
        for (WsSession session : sessions) {
            session.closeQuietly();
        }
        sessions.clear();
        if (wsListener != null) {
            try {
                wsListener.close();
            } catch (IOException ignored) {
                // closing
            }
            wsListener = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        gateway.shutdown();
    }

    public static void main(String[] args) throws Exception {
        int listenPort = args.length > 0 ? Integer.parseInt(args[0]) : 8080;
        GatewayServer server = new GatewayServer();
        server.start(listenPort);
        System.out.println("http://localhost:" + server.port() + "  (ws port " + server.wsPort() + ")");
        Runtime.getRuntime().addShutdownHook(new Thread(server::stop, "clob-ui-stop"));
        Thread.currentThread().join();
    }

    static GatewayResult executeOp(Gateway gateway, int symbolId, String json) {
        try {
            Map<String, Object> o = MiniJson.object(json);
            String op = MiniJson.str(o, "op");
            if (op == null) {
                return GatewayResult.rejected("missing op");
            }
            return switch (op.toLowerCase(Locale.ROOT)) {
                case "limit" -> gateway.submitLimit(
                        symbolId, MiniJson.lng(o, "orderId"), side(o), MiniJson.lng(o, "px"), MiniJson.lng(o, "qty"));
                case "market" -> gateway.submitMarket(
                        symbolId, MiniJson.lng(o, "orderId"), side(o), MiniJson.lng(o, "qty"));
                case "ioc" -> gateway.submitIoc(
                        symbolId, MiniJson.lng(o, "orderId"), side(o), MiniJson.lng(o, "px"), MiniJson.lng(o, "qty"));
                case "fok" -> gateway.submitFok(
                        symbolId, MiniJson.lng(o, "orderId"), side(o), MiniJson.lng(o, "px"), MiniJson.lng(o, "qty"));
                case "cancel" -> gateway.cancel(symbolId, MiniJson.lng(o, "orderId"));
                default -> GatewayResult.rejected("unknown op: " + op);
            };
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return GatewayResult.rejected(reason);
        }
    }

    private static Side side(Map<String, Object> o) {
        String raw = MiniJson.str(o, "side");
        if (raw == null) {
            throw new IllegalArgumentException("missing side");
        }
        return Side.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (!"GET".equals(exchange.getRequestMethod())) {
            byte[] body = "method not allowed".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(405, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        if ("/wsport".equals(path)) {
            byte[] body = String.valueOf(wsPort).getBytes(StandardCharsets.US_ASCII);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            return;
        }
        handleStatic(exchange, path);
    }

    private void handleStatic(HttpExchange exchange, String path) throws IOException {
        String relative = allowedStaticPath(path);
        if (relative == null) {
            sendPlain(exchange, 404, "not found");
            return;
        }
        Path root = uiDir.toAbsolutePath().normalize();
        Path file = root.resolve(relative).normalize();
        if (!file.startsWith(root)) {
            sendPlain(exchange, 404, "not found");
            return;
        }
        if (!Files.isRegularFile(file)) {
            String hint = relative + " not found at " + file.toAbsolutePath()
                    + " — build the UI: cd ui && npm run build";
            sendPlain(exchange, 404, hint);
            return;
        }
        byte[] bytes = Files.readAllBytes(file);
        if (relative.endsWith(".html")) {
            bytes = new String(bytes, StandardCharsets.UTF_8)
                    .replace(WS_PORT_PLACEHOLDER, String.valueOf(wsPort))
                    .getBytes(StandardCharsets.UTF_8);
        }
        exchange.getResponseHeaders().set("Content-Type", contentType(relative));
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Allowlist: {@code index.html} at root, hashed bundle files under {@code assets/}.
     * Rejects everything else (no traversal, no nested arbitrary paths).
     */
    static String allowedStaticPath(String path) {
        if ("/".equals(path) || "/index.html".equals(path)) {
            return "index.html";
        }
        if (path.startsWith("/assets/")) {
            String name = path.substring("/assets/".length());
            if (name.isEmpty()
                    || name.indexOf('/') >= 0
                    || name.indexOf('\\') >= 0
                    || name.contains("..")
                    || name.indexOf('.') < 0) {
                return null;
            }
            return "assets/" + name;
        }
        return null;
    }

    static String contentType(String relative) {
        String lower = relative.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".js") || lower.endsWith(".mjs")) {
            return "text/javascript; charset=UTF-8";
        }
        if (lower.endsWith(".css")) {
            return "text/css; charset=UTF-8";
        }
        if (lower.endsWith(".html")) {
            return "text/html; charset=UTF-8";
        }
        if (lower.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (lower.endsWith(".json") || lower.endsWith(".map")) {
            return "application/json";
        }
        return "application/octet-stream";
    }

    private void acceptLoop() {
        while (running.get()) {
            try {
                Socket socket = wsListener.accept();
                var unused = executor.submit(() -> {
                    try {
                        SocketTransport.Streams streams;
                        try {
                            streams = SocketTransport.of(socket);
                        } catch (Exception e) {
                            throw new IOException("ws streams failed", e);
                        }
                        WsSession session = new WsSession(0, streams.in(), streams.out(), socket);
                        sessions.add(session);
                        try {
                            sessionLoop(session);
                        } finally {
                            sessions.remove(session);
                            session.closeQuietly();
                        }
                    } catch (IOException e) {
                        try {
                            socket.close();
                        } catch (IOException ignored) {
                            // closing
                        }
                    }
                });
            } catch (IOException e) {
                if (running.get()) {
                    throw new UncheckedIOException("ws accept loop", e);
                }
            }
        }
    }

    private void sessionLoop(WsSession session) {
        try {
            String request = readHttpRequestHead(session.in);
            if (!request.contains("Upgrade: websocket") && !request.contains("Upgrade: WebSocket")) {
                session.open = false;
                return;
            }
            String key = wsKey(request);
            if (key == null) {
                session.open = false;
                return;
            }
            String accept = WsFrames.acceptKey(key);
            String handshake = "HTTP/1.1 101 Switching Protocols\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
            WsFrames.rawWrite(session.out, handshake.getBytes(StandardCharsets.US_ASCII));
            sendText(session, helloJson(session.symbolId, session.nextSeq()));
            while (running.get() && session.open) {
                WsFrames.Frame frame = WsFrames.read(session.in);
                switch (frame.opcode) {
                    case WsFrames.OP_TEXT -> onClientText(session, new String(frame.payload, StandardCharsets.UTF_8));
                    case WsFrames.OP_PING -> WsFrames.write(session.out, WsFrames.OP_PONG, frame.payload);
                    case WsFrames.OP_PONG -> {
                        // unsolicited pong is ignored
                    }
                    case WsFrames.OP_CLOSE -> {
                        session.open = false;
                    }
                    default -> session.open = false;
                }
            }
        } catch (IOException ignored) {
            session.open = false;
        }
    }

    /** Read the HTTP request head (headers + blank line) from the raw WS socket. */
    private static String readHttpRequestHead(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(256);
        while (sb.indexOf("\r\n\r\n") < 0) {
            int b = in.read();
            if (b < 0) {
                break;
            }
            sb.append((char) b);
            if (sb.length() > 8192) {
                throw new IOException("ws request head too large");
            }
        }
        return sb.toString();
    }

    private static String wsKey(String requestHead) {
        for (String line : requestHead.split("\\r\\n", -1)) {
            int colon = line.indexOf(':');
            if (colon > 0 && "Sec-WebSocket-Key".equalsIgnoreCase(line.substring(0, colon).trim())) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    private void onClientText(WsSession session, String json) {
        GatewayResult result;
        try {
            result = executeOp(gateway, session.symbolId, json);
        } catch (RuntimeException e) {
            result = GatewayResult.rejected(e.getMessage() == null ? "error" : e.getMessage());
        }
        sendText(session, ackJson(session.nextSeq(), result));
    }

    private void onGatewayFrame(GatewayFrame frame) {
        for (WsSession session : sessions) {
            if (!session.open) {
                continue;
            }
            try {
                for (Trade trade : frame.trades()) {
                    sendText(session, tradeJson(session.nextSeq(), trade));
                }
                sendText(session, l2Json(session.nextSeq(), snapshot(session.symbolId)));
            } catch (RuntimeException e) {
                session.closeQuietly();
                sessions.remove(session);
            }
        }
    }

    private L2Snapshot snapshot(int symbolId) {
        return gateway.exchange().router().shard(symbolId).engine().book().snapshot(L2_DEPTH);
    }

    private String helloJson(int symbolId, long seq) {
        var book = gateway.exchange().router().shard(symbolId).engine().book();
        L2Snapshot snap = book.snapshot(L2_DEPTH);
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"t\":\"hello\",\"sym\":").append(symbolId).append(",\"seq\":").append(seq);
        sb.append(",\"bid\":");
        appendLongOrNull(sb, book.bestBid());
        sb.append(",\"ask\":");
        appendLongOrNull(sb, book.bestAsk());
        sb.append(",\"bids\":");
        appendLevels(sb, snap.bids());
        sb.append(",\"asks\":");
        appendLevels(sb, snap.asks());
        sb.append('}');
        return sb.toString();
    }

    private static String l2Json(long seq, L2Snapshot snap) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"t\":\"l2\",\"seq\":").append(seq).append(",\"bids\":");
        appendLevels(sb, snap.bids());
        sb.append(",\"asks\":");
        appendLevels(sb, snap.asks());
        sb.append('}');
        return sb.toString();
    }

    private static String tradeJson(long seq, Trade trade) {
        return "{\"t\":\"trade\",\"seq\":" + seq
                + ",\"maker\":" + trade.makerOrderId()
                + ",\"taker\":" + trade.takerOrderId()
                + ",\"px\":" + trade.priceTicks()
                + ",\"qty\":" + trade.quantity()
                + '}';
    }

    static String ackJson(long seq, GatewayResult result) {
        if (result.accepted()) {
            return "{\"t\":\"ack\",\"seq\":" + seq + ",\"accepted\":true}";
        }
        return "{\"t\":\"ack\",\"seq\":" + seq + ",\"accepted\":false,\"reason\":"
                + MiniJson.quote(result.reason()) + '}';
    }

    private static void appendLevels(StringBuilder sb, List<BookLevel> levels) {
        sb.append('[');
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            BookLevel level = levels.get(i);
            sb.append("{\"px\":").append(level.priceTicks())
                    .append(",\"qty\":").append(level.quantity())
                    .append(",\"n\":").append(level.orderCount())
                    .append('}');
        }
        sb.append(']');
    }

    private static void appendLongOrNull(StringBuilder sb, Long value) {
        if (value == null) {
            sb.append("null");
        } else {
            sb.append(value);
        }
    }

    private void sendText(WsSession session, String json) {
        synchronized (session) {
            if (!session.open) {
                return;
            }
            try {
                WsFrames.write(session.out, WsFrames.OP_TEXT, json.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                session.closeQuietly();
                sessions.remove(session);
            }
        }
    }

    private static void sendPlain(HttpExchange exchange, int code, String text) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static ThreadFactory namedThreads() {
        AtomicLong n = new AtomicLong();
        return r -> {
            Thread t = new Thread(r, "clob-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    private static final class WsSession {
        final int symbolId;
        final InputStream in;
        final OutputStream out;
        private final Socket socket;
        final AtomicLong seq = new AtomicLong();
        volatile boolean open = true;

        WsSession(int symbolId, InputStream in, OutputStream out, Socket socket) {
            this.symbolId = symbolId;
            this.in = in;
            this.out = out;
            this.socket = socket;
        }

        long nextSeq() {
            return seq.incrementAndGet();
        }

        void closeQuietly() {
            open = false;
            try {
                WsFrames.write(out, WsFrames.OP_CLOSE, new byte[0]);
            } catch (IOException ignored) {
                // closing
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing
            }
        }
    }
}