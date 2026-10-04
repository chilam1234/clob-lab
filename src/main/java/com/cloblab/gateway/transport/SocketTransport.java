package com.cloblab.gateway.transport;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.channels.SocketChannel;

/**
 * WebSocket transport on a plain {@link java.net.ServerSocket}.
 *
 * <p>The original design upgraded connections inside the JDK's {@code com.sun.net.httpserver}
 * by reflecting at {@code sun.net.httpserver} internals; that requires
 * {@code --add-opens jdk.httpserver/sun.net.httpserver=ALL-UNNAMED} on JDK 21+ (module is not
 * open — InaccessibleObjectException otherwise). Rather than depend on JVM flags, the
 * WebSocket endpoint is a dedicated ServerSocket and HTTP/static files stay on HttpServer
 * (ADR-0002 transport layout updated accordingly).
 */
final class SocketTransport {
    static Streams of(Socket socket) throws Exception {
        return new Streams(socket.getInputStream(), socket.getOutputStream(), socket);
    }

    record Streams(InputStream in, OutputStream out, Socket socket) {}

    private SocketTransport() {}
}