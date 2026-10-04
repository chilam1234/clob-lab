package com.cloblab.engine;

/** Order book implementation backing {@link MatchingEngine}. */
public enum BookKind {
    /** Array-backed levels, pooled orders — default, industry-shaped. */
    FAST,
    /** TreeMap + ArrayDeque — original learning implementation. */
    TREE
}
