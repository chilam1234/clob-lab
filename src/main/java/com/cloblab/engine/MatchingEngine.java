package com.cloblab.engine;

import com.cloblab.book.FastOrderBook;
import com.cloblab.book.OrderBook;
import com.cloblab.book.OrderBookView;
import com.cloblab.journal.EventJournal;
import com.cloblab.journal.EventReplayer;
import com.cloblab.marketdata.L2Snapshot;
import com.cloblab.model.MatchResult;
import com.cloblab.model.OrderType;
import com.cloblab.model.Side;

/**
 * Thin orchestration layer around {@link OrderBookView}.
 * Default book: {@link FastOrderBook} (array ticks + object pool).
 */
public final class MatchingEngine implements OrderBookView.MatchSink {
    private static final long BUY_MARKET_PRICE = Long.MAX_VALUE;
    private static final long SELL_MARKET_PRICE = Long.MIN_VALUE;

    private final OrderBookView book;
    private long sequence = 1;
    private final TradeBuffer tradeBuffer = new TradeBuffer();
    private final MatchResult matchResult = new MatchResult();
    private long currentTakerOrderId;

    public MatchingEngine() {
        this(BookKind.FAST);
    }

    public MatchingEngine(BookKind kind) {
        this.book = kind == BookKind.TREE ? new OrderBook() : FastOrderBook.createDefault();
    }

    public static MatchingEngine create(BookKind kind) {
        return new MatchingEngine(kind);
    }

    public BookKind bookKind() {
        return book instanceof FastOrderBook ? BookKind.FAST : BookKind.TREE;
    }

    public OrderBookView book() {
        return book;
    }

    public L2Snapshot snapshot(int depth) {
        return book.snapshot(depth);
    }

    public MatchResult submitLimitOrder(long orderId, Side side, long priceTicks, long quantity) {
        return submit(orderId, side, priceTicks, quantity, true, OrderType.LIMIT);
    }

    public MatchResult submitMarketOrder(long orderId, Side side, long quantity) {
        long price = side == Side.BUY ? BUY_MARKET_PRICE : SELL_MARKET_PRICE;
        return submit(orderId, side, price, quantity, false, OrderType.MARKET);
    }

    public MatchResult submitIocOrder(long orderId, Side side, long priceTicks, long quantity) {
        return submit(orderId, side, priceTicks, quantity, false, OrderType.IOC);
    }

    public MatchResult submitFokOrder(long orderId, Side side, long priceTicks, long quantity) {
        if (book.availableAtOrBetter(side, priceTicks) < quantity) {
            return matchResult.captureEmpty(quantity, false, OrderType.FOK);
        }
        return submit(orderId, side, priceTicks, quantity, false, OrderType.FOK);
    }

    public boolean cancel(long orderId) {
        return book.cancel(orderId);
    }

    @Override
    public void onTrade(long makerOrderId, long priceTicks, long quantity) {
        tradeBuffer.add(makerOrderId, currentTakerOrderId, priceTicks, quantity);
    }

    private MatchResult submit(
            long orderId,
            Side side,
            long limitPriceTicks,
            long quantity,
            boolean allowRest,
            OrderType orderType) {
        tradeBuffer.clear();
        long takerSequence = sequence++;
        currentTakerOrderId = orderId;

        long remaining = book.matchAggressive(side, limitPriceTicks, quantity, this);

        if (remaining > 0 && allowRest) {
            book.addResting(orderId, side, limitPriceTicks, remaining, takerSequence);
            return matchResult.capture(
                    tradeBuffer.makerIds(), tradeBuffer.takerIds(),
                    tradeBuffer.priceTicks(), tradeBuffer.quantities(),
                    tradeBuffer.size(), remaining, true, orderType);
        }

        return matchResult.capture(
                tradeBuffer.makerIds(), tradeBuffer.takerIds(),
                tradeBuffer.priceTicks(), tradeBuffer.quantities(),
                tradeBuffer.size(), remaining, false, orderType);
    }

    public static final class Journaled {
        private final MatchingEngine engine;
        private final EventJournal journal = new EventJournal();

        public Journaled() {
            this(BookKind.FAST);
        }

        public Journaled(BookKind kind) {
            this.engine = new MatchingEngine(kind);
        }

        public OrderBookView book() {
            return engine.book();
        }

        public EventJournal journal() {
            return journal;
        }

        public L2Snapshot snapshot(int depth) {
            return engine.snapshot(depth);
        }

        public MatchResult submitLimitOrder(long orderId, Side side, long priceTicks, long quantity) {
            journal.nextSubmitLimit(orderId, side, priceTicks, quantity);
            return engine.submitLimitOrder(orderId, side, priceTicks, quantity);
        }

        public MatchResult submitMarketOrder(long orderId, Side side, long quantity) {
            journal.nextSubmitMarket(orderId, side, quantity);
            return engine.submitMarketOrder(orderId, side, quantity);
        }

        public MatchResult submitIocOrder(long orderId, Side side, long priceTicks, long quantity) {
            journal.nextSubmitIoc(orderId, side, priceTicks, quantity);
            return engine.submitIocOrder(orderId, side, priceTicks, quantity);
        }

        public MatchResult submitFokOrder(long orderId, Side side, long priceTicks, long quantity) {
            journal.nextSubmitFok(orderId, side, priceTicks, quantity);
            return engine.submitFokOrder(orderId, side, priceTicks, quantity);
        }

        public boolean cancel(long orderId) {
            journal.nextCancel(orderId);
            return engine.cancel(orderId);
        }

        public MatchingEngine replay() {
            return EventReplayer.replay(journal.events());
        }
    }
}
