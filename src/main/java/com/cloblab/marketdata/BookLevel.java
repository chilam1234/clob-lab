package com.cloblab.marketdata;

/** Top-of-book aggregate at one price level (L2). */
public record BookLevel(long priceTicks, long quantity, int orderCount) {}
