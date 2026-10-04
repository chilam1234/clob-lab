package com.cloblab.gateway.transport;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * Minimal RFC 6455 framing: handshake accept, text/close/ping/pong. No RSV, no
 * fragmentation, no extensions, no subprotocols. Server frames are unmasked;
 * client frames are masked (unmask on read).
 */
final class WsFrames {
    static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    static final int OP_TEXT = 0x1;
    static final int OP_CLOSE = 0x8;
    static final int OP_PING = 0x9;
    static final int OP_PONG = 0xA;
    private static final int MAX_PAYLOAD = 1 << 20;

    static final class Frame {
        final int opcode;
        final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }
    }

    static String acceptKey(String clientKey) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest((clientKey + GUID).getBytes(StandardCharsets.US_ASCII));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 required by RFC 6455", e);
        }
    }

    static byte[] encode(int opcode, byte[] payload) {
        return encode(opcode, payload, null);
    }

    static byte[] encodeText(String text) {
        return encode(OP_TEXT, text.getBytes(StandardCharsets.UTF_8), null);
    }

    static byte[] encode(int opcode, byte[] payload, byte[] mask) {
        int len = payload.length;
        boolean masked = mask != null;
        int header = 2 + (masked ? 4 : 0) + (len <= 125 ? 0 : len <= 0xFFFF ? 2 : 8);
        byte[] out = new byte[header + len];
        out[0] = (byte) (0x80 | (opcode & 0x0F));
        int pos = 2;
        if (len <= 125) {
            out[1] = (byte) len;
        } else if (len <= 0xFFFF) {
            out[1] = 126;
            out[2] = (byte) (len >>> 8);
            out[3] = (byte) len;
            pos = 4;
        } else {
            out[1] = 127;
            for (int i = 7; i >= 0; i--) {
                out[2 + (7 - i)] = (byte) (len >>> (i * 8));
            }
            pos = 10;
        }
        if (masked) {
            out[1] |= (byte) 0x80;
            System.arraycopy(mask, 0, out, pos, 4);
            pos += 4;
            for (int i = 0; i < len; i++) {
                out[pos + i] = (byte) (payload[i] ^ mask[i & 3]);
            }
        } else {
            System.arraycopy(payload, 0, out, pos, len);
        }
        return out;
    }

    static Frame read(InputStream in) throws IOException {
        int b0 = in.read();
        int b1 = in.read();
        if (b0 < 0 || b1 < 0) {
            throw new EOFException("websocket closed");
        }
        if ((b0 & 0x70) != 0) {
            throw new IOException("RSV not supported");
        }
        if ((b0 & 0x80) == 0) {
            throw new IOException("fragmentation not supported");
        }
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = readUint(in, 2);
        } else if (len == 127) {
            len = readUint(in, 8);
        }
        if (len > MAX_PAYLOAD) {
            throw new IOException("payload too large: " + len);
        }
        byte[] mask = null;
        if (masked) {
            mask = in.readNBytes(4);
            if (mask.length != 4) {
                throw new EOFException("short mask");
            }
        }
        byte[] payload = in.readNBytes((int) len);
        if (payload.length != len) {
            throw new EOFException("short payload");
        }
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
        }
        return new Frame(opcode, payload);
    }

    static void write(OutputStream out, int opcode, byte[] payload) throws IOException {
        out.write(encode(opcode, payload, null));
        out.flush();
    }

    /** Write raw bytes (the HTTP 101 handshake) verbatim. */
    static void rawWrite(OutputStream out, byte[] bytes) throws IOException {
        out.write(bytes);
        out.flush();
    }

    private static long readUint(InputStream in, int bytes) throws IOException {
        long value = 0;
        for (int i = 0; i < bytes; i++) {
            int b = in.read();
            if (b < 0) {
                throw new EOFException("short length");
            }
            value = (value << 8) | b;
        }
        return value;
    }

    private WsFrames() {}
}
