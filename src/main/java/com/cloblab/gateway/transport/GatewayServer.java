package com.cloblab.gateway.transport;

import com.cloblab.gateway.Gateway;
import com.cloblab.gateway.GatewayFrame;
import com.cloblab.gateway.GatewayResult;
import com.cloblab.gateway.MockMarketMaker;
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
    private static final int DEFAULT_RING = 1024;
    static final String WS_PORT_PLACEHOLDER = "__WS_PORT__";

    /** Watchlist pairs (ADR-0007): base symbols the gateway mocks. */
    private static final String[] PAIR_BASES = {"clob", "eth", "sol", "bnb", "aave", "blur"};
    private static final long[] PAIR_START_MIDS = {1000, 300_000, 20_000, 60_000, 9_000, 2_500};
    private static final double[] PAIR_VOLATILITY = {2.0, 6.0, 5.0, 4.5, 7.5, 9.0};

    private final Gateway gateway;
    private final Path uiDir;
    private final CopyOnWriteArrayList<WsSession> sessions = new CopyOnWriteArrayList<>();
    private final AtomicBoolean subscribed = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final java.util.List<MockMarketMaker> mockMakers = new java.util.ArrayList<>();
    private final boolean startMock;

    private HttpServer httpServer;
    private ServerSocket wsListener;
    private ExecutorService executor;
    private int port;
    private int wsPort;

    public GatewayServer() {
        this(new Gateway(PAIR_BASES.length, DEFAULT_RING), defaultUiDir(), resolveMockArg());
    }

    public GatewayServer(Gateway gateway, Path uiDir) {
        this(gateway, uiDir, false);
    }

    public GatewayServer(Gateway gateway, Path uiDir, boolean startMock) {
        this.gateway = gateway;
        this.uiDir = uiDir;
        this.startMock = startMock;
    }

    /** CLI: --no-mock disables the synthetic market maker. */
    static boolean resolveMockArg() {
        for (String arg : System.getProperty("clob.mock", "").split(",", -1)) {
            if (arg.isBlank()) {
                continue;
            }
            if (arg.equals("no-mock") || arg.equals("false")) {
                return false;
            }
        }
        return true;
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

        if (startMock) {
            for (int i = 0; i < PAIR_BASES.length; i++) {
                MockMarketMaker maker = new MockMarketMaker(gateway, i, 100,
                        PAIR_VOLATILITY[i], PAIR_START_MIDS[i]);
                maker.start();
                mockMakers.add(maker);
            }
        }

        // Publish running BEFORE the acceptor starts: the accept loop checks this flag and
        // would otherwise exit immediately in a start/accept race (observed once).
        running.set(true);
        Thread acceptor = new Thread(this::acceptLoop, "clob-ws-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public synchronized void stop() {
        running.set(false);
        for (MockMarketMaker maker : mockMakers) {
            maker.stop();
        }
        mockMakers.clear();
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
        Thread.currentThread().setUncaughtExceptionHandler(
                (t, e) -> System.err.println("[clob-ws] acceptor died: " + e));
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
        int symbolId = frame.symbolId() < 0 ? 0 : frame.symbolId();
        for (WsSession session : sessions) {
            if (!session.open) {
                continue;
            }
            try {
                long bookSeq = session.nextSeq();
                for (Trade trade : frame.trades()) {
                    long tradeSeq = session.nextSeq();
                    sendText(session, tradeJson(tradeSeq, symbolId, trade));
                    sendText(session, binanceAggTradeJson(symbolId, tradeSeq,
                            trade.makerOrderId(), trade.takerOrderId(),
                            trade.priceTicks(), trade.quantity()));
                    sendKlineThrottled(session, symbolId, trade);
                }
                sendText(session, l2Json(bookSeq, symbolId, snapshot(symbolId)));
                sendBinanceDepthThrottled(session, symbolId);
            } catch (RuntimeException e) {
                session.closeQuietly();
                sessions.remove(session);
            }
        }
    }

    private static final long DEPTH_INTERVAL_NANOS = 500_000_000L; // 500 ms
    private static final long KLINE_INTERVAL_NANOS = 100_000_000L; // 100 ms

    /** Binance depth10 + bookTicker at most every 500ms per session. */
    private void sendBinanceDepthThrottled(WsSession session, int symbolId) {
        long now = System.nanoTime();
        long last = session.lastDepthSentNano;
        if (last != 0 && now - last < DEPTH_INTERVAL_NANOS) {
            return;
        }
        session.lastDepthSentNano = now;
        var book = gateway.exchange().router().shard(symbolId).engine().book();
        long frameSeq = session.nextSeq();
        sendText(session, binanceDepth10Json(symbolId, frameSeq, book.snapshot(L2_DEPTH)));
        sendText(session, binanceBookTickerJson(symbolId, frameSeq, book));
    }

    /** Live 1m candle update at most every 100ms per session; klines close at minute boundaries. */
    private void sendKlineThrottled(WsSession session, int symbolId, Trade trade) {
        long nowWall = System.currentTimeMillis();
        CandleAggregator agg = session.candles[symbolId];
        if (agg == null) {
            agg = new CandleAggregator();
            session.candles[symbolId] = agg;
        } else if (agg.hasCandle() && agg.bucketOpen != nowWall - (nowWall % CandleAggregator.BUCKET_MILLIS)) {
            // emit the closed previous candle first
            sendText(session, binanceKlineJson(symbolId,
                    agg.klineJson(binanceSymbolUpper(symbolId), true)));
        }
        String kline = agg.onTrade(trade.priceTicks(), trade.quantity(), nowWall,
                binanceSymbolUpper(symbolId));
        long now = System.nanoTime();
        if (session.lastKlineSentNano == 0 || now - session.lastKlineSentNano >= KLINE_INTERVAL_NANOS) {
            session.lastKlineSentNano = now;
            sendText(session, binanceKlineJson(symbolId, kline));
        }
    }

    /** Wrap a raw kline payload in the combined-stream envelope. */
    static String binanceKlineJson(int symbolId, String klinePayload) {
        return "{\"stream\":\"" + binanceSymbol(symbolId) + "@kline_1m\",\"data\":" + klinePayload + "}";
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

    private static String l2Json(long seq, int symbolId, L2Snapshot snap) {
        StringBuilder sb = new StringBuilder(96);
        sb.append("{\"t\":\"l2\",\"sym\":").append(symbolId).append(",\"seq\":").append(seq).append(",\"bids\":");
        appendLevels(sb, snap.bids());
        sb.append(",\"asks\":");
        appendLevels(sb, snap.asks());
        sb.append('}');
        return sb.toString();
    }

    private static String tradeJson(long seq, int symbolId, Trade trade) {
        return "{\"t\":\"trade\",\"seq\":" + seq
                + ",\"sym\":" + symbolId
                + ",\"maker\":" + trade.makerOrderId()
                + ",\"taker\":" + trade.takerOrderId()
                + ",\"px\":" + trade.priceTicks()
                + ",\"qty\":" + trade.quantity()
                + '}';
    }

    /**
     * Binance-dialect frames (ADR-0006): let Binance-compatible clients
     * (rust-project/trading-ui) consume the same event stream.
     * Envelope: {"stream":"clobusdt@aggTrade","data":{...aggTrade...}}
     */
    static String binanceSymbol(int symbolId) {
        return PAIR_BASES[Math.floorMod(symbolId, PAIR_BASES.length)] + "usdt";
    }

    static String binanceSymbolUpper(int symbolId) {
        return binanceSymbol(symbolId).toUpperCase(Locale.ROOT);
    }
    private static final long SYNTH_BASE = 1_000_000L;
    static final long MOCK_ID_BASE = 1_000_000L; // MockMarketMaker's BASE_ID; keep in sync

    /**
     * "m" (buyer-is-maker) semantics: with clob-lab's UI id scheme (odd=BUY, even=SELL)
     * the resting maker's side is decodable only for UI ids; synthetic (mock) ids are
     * assumed to be standard two-sided quotes so we use the taker parity heuristic
     * (taker odd = BUY). Good enough for tape coloring; exact side tagging is a
     * follow-up if needed (would require carrying taker side on Trade).
     */
    static String binanceAggTradeJson(int symbolId, long seq, long makerOrderId, long takerOrderId,
                                      long priceTicks, long quantity) {
        boolean takerIsBuy = takerOrderId < SYNTH_BASE ? takerOrderId % 2 == 1 : true;
        // m = buyerIsMaker = NOT takerIsBuy (taker bought => buyer is the taker => buyer not maker)
        String m = Boolean.toString(!takerIsBuy);
        return "{\"stream\":\"" + binanceSymbol(symbolId) + "@aggTrade\",\"data\":{"
                + "\"e\":\"aggTrade\""
                + ",\"a\":" + seq
                + ",\"s\":\"" + binanceSymbolUpper(symbolId) + "\""
                + ",\"p\":\"" + priceTicks + "\""
                + ",\"q\":\"" + quantity + "\""
                + ",\"m\":" + m
                + ",\"E\":" + System.currentTimeMillis()
                + "}}";
    }

    /** Binance bookTicker: {u,s,b,B,a,A} — best bid/ask with quantities (ADR-0007). */
    static String binanceBookTickerJson(int symbolId, long updateId, com.cloblab.book.OrderBookView book) {
        String b = String.valueOf(book.bestBid());
        String a = String.valueOf(book.bestAsk());
        return "{\"stream\":\"" + binanceSymbol(symbolId) + "@bookTicker\",\"data\":{"
                + "\"u\":" + updateId
                + ",\"s\":\"" + binanceSymbolUpper(symbolId) + "\""
                + ",\"b\":\"" + (book.bestBid() == null ? "0" : b) + "\""
                + ",\"B\":\"" + book.totalBidQuantity() + "\""
                + ",\"a\":\"" + (book.bestAsk() == null ? "0" : a) + "\""
                + ",\"A\":\"" + book.totalAskQuantity() + "\""
                + "}}";
    }

    static String binanceDepth10Json(int symbolId, long seq, L2Snapshot snap) {
        StringBuilder sb = new StringBuilder(192);
        sb.append("{\"stream\":\"").append(binanceSymbol(symbolId)).append("@depth10@100ms\",\"data\":{");
        sb.append("\"lastUpdateId\":").append(seq);
        sb.append(",\"bids\":");
        appendLevelRows(sb, snap.bids());
        sb.append(",\"asks\":");
        appendLevelRows(sb, snap.asks());
        sb.append("}}");
        return sb.toString();
    }

    /** Binance depth rows: [px, qty] as strings. */
    private static void appendLevelRows(StringBuilder sb, List<BookLevel> levels) {
        sb.append('[');
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            BookLevel level = levels.get(i);
            sb.append('[')
                    .append('"').append(level.priceTicks()).append('"').append(',')
                    .append('"').append(level.quantity()).append('"')
                    .append(']');
        }
        sb.append(']');
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
        final CandleAggregator[] candles = new CandleAggregator[PAIR_BASES.length];
        volatile long lastDepthSentNano = 0;
        volatile long lastKlineSentNano = 0;
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