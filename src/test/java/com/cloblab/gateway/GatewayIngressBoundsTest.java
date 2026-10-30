package com.cloblab.gateway;

import com.cloblab.model.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class GatewayIngressBoundsTest {

    @AfterEach
    void clearProps() {
        System.clearProperty("clob.maxNotional");
        System.clearProperty("clob.maxPrice");
    }

    @Test
    void rejectsPriceOutOfRange() {
        System.setProperty("clob.maxPrice", "1000");
        Gateway gateway = new Gateway(1, 8);
        GatewayResult high = gateway.submitLimit(0, 1, Side.BUY, 1001, 1);
        assertEquals(GatewayResult.Status.REJECTED, high.status());
        assertEquals("PRICE_OUT_OF_RANGE", high.reason());

        GatewayResult zero = gateway.submitLimit(0, 2, Side.BUY, 0, 1);
        assertEquals(GatewayResult.Status.REJECTED, zero.status());
        // existing positive-price check fires first for LIMIT at 0
        assertFalse(zero.accepted());
    }

    @Test
    void rejectsNotionalExceeded() {
        System.setProperty("clob.maxNotional", "1000");
        Gateway gateway = new Gateway(1, 8);
        GatewayResult result = gateway.submitLimit(0, 1, Side.BUY, 100, 11);
        assertEquals(GatewayResult.Status.REJECTED, result.status());
        assertEquals("NOTIONAL_EXCEEDED", result.reason());
    }
}
