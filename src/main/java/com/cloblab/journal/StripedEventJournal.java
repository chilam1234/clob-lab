package com.cloblab.journal;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * N unsynchronized journal stripes (one per symbolId). Each stripe is SPSC: only the
 * owning shard thread appends. Replay merges stripes by {@link OrderEvent#eventId()}
 * ascending so order equals global ingress / execution order (ADR-0001).
 *
 * <p>File mode ({@link #StripedEventJournal(Path, int)}): appends binary {@link OrderEvent}
 * records to {@code dir/shard-N.log}, buffered, flush every
 * {@code journal.flushIntervalEvents} (default 500) events plus shutdown hook and
 * {@link #flush()}. Crash may lose ≤ flush-interval events per stripe (ADR-0011 trade).
 */
public final class StripedEventJournal {
    private static final int DEFAULT_FLUSH_INTERVAL = 500;

    private final Stripe[] stripes;
    private final Path dir;
    private final int flushInterval;
    private final boolean fileMode;

    public StripedEventJournal(int stripeCount) {
        this(null, stripeCount, DEFAULT_FLUSH_INTERVAL);
    }

    public StripedEventJournal(Path dir) {
        this(dir, countExistingOrDefault(dir), DEFAULT_FLUSH_INTERVAL);
    }

    public StripedEventJournal(Path dir, int stripeCount) {
        this(dir, stripeCount, flushIntervalProperty());
    }

    StripedEventJournal(Path dir, int stripeCount, int flushInterval) {
        if (stripeCount <= 0) {
            throw new IllegalArgumentException("stripeCount must be positive: " + stripeCount);
        }
        this.dir = dir;
        this.flushInterval = Math.max(1, flushInterval);
        this.fileMode = dir != null;
        this.stripes = new Stripe[stripeCount];
        if (fileMode) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new UncheckedIOException("create journal dir " + dir, e);
            }
        }
        for (int i = 0; i < stripeCount; i++) {
            stripes[i] = fileMode ? new FileStripe(i, dir.resolve("shard-" + i + ".log"), this.flushInterval)
                    : new MemoryStripe();
        }
        if (fileMode) {
            Runtime.getRuntime().addShutdownHook(new Thread(this::flushQuietly, "clob-journal-flush"));
        }
    }

    /**
     * Build from {@code -Dclob.journal=memory|file} (default {@code memory}) and
     * {@code -Dclob.journal.dir} (default {@code ./journal}).
     */
    public static StripedEventJournal fromSystemProperties(int stripeCount) {
        String mode = System.getProperty("clob.journal", "memory");
        if ("file".equalsIgnoreCase(mode)) {
            Path path = Path.of(System.getProperty("clob.journal.dir", "./journal"));
            return new StripedEventJournal(path, stripeCount);
        }
        return new StripedEventJournal(stripeCount);
    }

    public boolean isFileMode() {
        return fileMode;
    }

    public Path dir() {
        return dir;
    }

    public int stripeCount() {
        return stripes.length;
    }

    /**
     * Sink for {@code symbolId}. Append is unsynchronized; call only from the shard
     * thread that owns that symbol.
     */
    public OrderEventSink stripe(int symbolId) {
        return stripes[symbolId];
    }

    /** Total events across all stripes. */
    public int size() {
        int total = 0;
        for (Stripe stripe : stripes) {
            total += stripe.size();
        }
        return total;
    }

    /** All events on one stripe (memory list or file read). */
    public List<OrderEvent> readAllEvents(int stripe) {
        return stripes[stripe].readAll();
    }

    /** Flush every file stripe (no-op in memory mode). */
    public void flush() {
        for (Stripe stripe : stripes) {
            stripe.flush();
        }
    }

    /** Flush and close file stripes (no-op in memory mode). */
    public void close() {
        for (Stripe stripe : stripes) {
            stripe.close();
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException ignored) {
            // shutdown best-effort
        }
    }

    /**
     * Merge stripes by eventId ascending (global ingress sequence). Safe to call after
     * shards have quiesced; not a concurrent snapshot.
     */
    public List<OrderEvent> events() {
        PriorityQueue<StripeCursor> heap = new PriorityQueue<>(
                Comparator.comparingLong(c -> c.event.eventId()));
        for (int i = 0; i < stripes.length; i++) {
            List<OrderEvent> list = stripes[i].readAll();
            if (!list.isEmpty()) {
                heap.offer(new StripeCursor(list, 0));
            }
        }
        List<OrderEvent> merged = new ArrayList<>(size());
        while (!heap.isEmpty()) {
            StripeCursor cursor = heap.poll();
            merged.add(cursor.event);
            int next = cursor.index + 1;
            if (next < cursor.list.size()) {
                heap.offer(new StripeCursor(cursor.list, next));
            }
        }
        return Collections.unmodifiableList(merged);
    }

    private static int flushIntervalProperty() {
        return Integer.getInteger("journal.flushIntervalEvents", DEFAULT_FLUSH_INTERVAL);
    }

    private static int countExistingOrDefault(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            throw new IllegalArgumentException("journal dir required when stripeCount omitted: " + dir);
        }
        int found = 0;
        try (var stream = Files.list(dir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString();
                if (name.startsWith("shard-") && name.endsWith(".log")) {
                    found++;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (found <= 0) {
            throw new IllegalArgumentException("no shard-N.log files in " + dir);
        }
        return found;
    }

    private interface Stripe extends OrderEventSink {
        int size();

        List<OrderEvent> readAll();

        void flush();

        void close();
    }

    /** Unsynchronized SPSC buffer owned by one shard thread. */
    private static final class MemoryStripe implements Stripe {
        private final List<OrderEvent> events = new ArrayList<>();

        @Override
        public OrderEvent append(OrderEvent event) {
            events.add(event);
            return event;
        }

        @Override
        public int size() {
            return events.size();
        }

        @Override
        public List<OrderEvent> readAll() {
            return List.copyOf(events);
        }

        @Override
        public void flush() {
            // memory
        }

        @Override
        public void close() {
            // memory
        }
    }

    private static final class FileStripe implements Stripe {
        private final Path path;
        private final int flushInterval;
        private final List<OrderEvent> mirror = new ArrayList<>();
        private final DataOutputStream out;
        private int sinceFlush;

        FileStripe(int symbolId, Path path, int flushInterval) {
            this.path = path;
            this.flushInterval = flushInterval;
            try {
                OutputStream raw = Files.newOutputStream(path,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                this.out = new DataOutputStream(new BufferedOutputStream(raw, 64 * 1024));
                // Seed mirror from existing file so size()/events() see prior boot.
                mirror.addAll(readFile(path));
            } catch (IOException e) {
                throw new UncheckedIOException("open journal stripe " + symbolId + " at " + path, e);
            }
        }

        @Override
        public OrderEvent append(OrderEvent event) {
            try {
                OrderEventCodec.write(out, event);
                mirror.add(event);
                sinceFlush++;
                if (sinceFlush >= flushInterval) {
                    out.flush();
                    sinceFlush = 0;
                }
                return event;
            } catch (IOException e) {
                throw new UncheckedIOException("journal append " + path, e);
            }
        }

        @Override
        public int size() {
            return mirror.size();
        }

        @Override
        public List<OrderEvent> readAll() {
            return List.copyOf(mirror);
        }

        @Override
        public void flush() {
            try {
                out.flush();
                sinceFlush = 0;
            } catch (IOException e) {
                throw new UncheckedIOException("journal flush " + path, e);
            }
        }

        @Override
        public void close() {
            try {
                out.flush();
                out.close();
            } catch (IOException e) {
                throw new UncheckedIOException("journal close " + path, e);
            }
        }

        static List<OrderEvent> readFile(Path path) throws IOException {
            if (!Files.isRegularFile(path) || Files.size(path) == 0) {
                return List.of();
            }
            List<OrderEvent> events = new ArrayList<>();
            try (DataInputStream in = new DataInputStream(
                    new BufferedInputStream(Files.newInputStream(path)))) {
                OrderEvent event;
                while ((event = OrderEventCodec.read(in)) != null) {
                    events.add(event);
                }
            }
            return events;
        }
    }

    private static final class StripeCursor {
        final List<OrderEvent> list;
        final int index;
        final OrderEvent event;

        StripeCursor(List<OrderEvent> list, int index) {
            this.list = list;
            this.index = index;
            this.event = list.get(index);
        }
    }
}
