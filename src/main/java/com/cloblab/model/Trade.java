package com.cloblab.model;

public record Trade(long makerOrderId, long takerOrderId, long priceTicks, long quantity) {}
