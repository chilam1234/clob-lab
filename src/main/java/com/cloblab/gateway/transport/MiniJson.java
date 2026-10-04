package com.cloblab.gateway.transport;

import java.util.LinkedHashMap;
import java.util.Map;

/** Tiny JSON object parser/encoder for the WS wire format (no arrays as roots). */
final class MiniJson {
    static Map<String, Object> object(String json) {
        return new Parser(json).parseObject();
    }

    static String quote(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    static String str(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v == null ? null : String.valueOf(v);
    }

    static long lng(Map<String, Object> o, String key) {
        Object v = o.get(key);
        if (v instanceof Long l) {
            return l;
        }
        if (v == null) {
            throw new IllegalArgumentException("missing " + key);
        }
        throw new IllegalArgumentException("expected number for " + key);
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        Map<String, Object> parseObject() {
            skipWs();
            expect('{');
            Map<String, Object> map = new LinkedHashMap<>();
            skipWs();
            if (peek() == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                skipWs();
                map.put(key, parseValue());
                skipWs();
                char c = next();
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected comma");
                }
            }
        }

        private Object parseValue() {
            char c = peek();
            if (c == '"') {
                return parseString();
            }
            if (c == 't') {
                consume("true");
                return Boolean.TRUE;
            }
            if (c == 'f') {
                consume("false");
                return Boolean.FALSE;
            }
            if (c == 'n') {
                consume("null");
                return null;
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '{') {
                return parseObject();
            }
            return parseNumber();
        }

        private java.util.List<Object> parseArray() {
            expect('[');
            java.util.List<Object> out = new java.util.ArrayList<>();
            skipWs();
            if (peek() == ']') {
                i++;
                return out;
            }
            while (true) {
                skipWs();
                out.add(parseValue());
                skipWs();
                char c = next();
                if (c == ']') {
                    return out;
                }
                if (c != ',') {
                    throw new IllegalArgumentException("expected comma in array");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    sb.append(switch (e) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        default -> e;
                    });
                } else {
                    sb.append(c);
                }
            }
        }

        private Long parseNumber() {
            int start = i;
            if (peek() == '-') {
                i++;
            }
            while (i < s.length() && Character.isDigit(s.charAt(i))) {
                i++;
            }
            if (start == i || (s.charAt(start) == '-' && i == start + 1)) {
                throw new IllegalArgumentException("bad number");
            }
            return Long.parseLong(s.substring(start, i));
        }

        private void consume(String lit) {
            if (!s.startsWith(lit, i)) {
                throw new IllegalArgumentException("expected " + lit);
            }
            i += lit.length();
        }

        private void skipWs() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        private char peek() {
            if (i >= s.length()) {
                throw new IllegalArgumentException("unexpected end");
            }
            return s.charAt(i);
        }

        private char next() {
            char c = peek();
            i++;
            return c;
        }

        private void expect(char c) {
            if (next() != c) {
                throw new IllegalArgumentException("expected " + c);
            }
        }
    }

    private MiniJson() {}
}
