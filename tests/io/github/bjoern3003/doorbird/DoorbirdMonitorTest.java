// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bjoern3003.doorbird.api.DoorbirdEvents;
import io.github.bjoern3003.doorbird.api.DoorbirdHttp;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DoorbirdMonitorTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/bha-api/", DoorbirdMonitorTest::handle);
        server.start();
        DoorbirdEvents events = null;
        try {
            DoorbirdHttp api = new DoorbirdHttp("127.0.0.1", server.getAddress().getPort(), "abc1230001", "secret");
            List<String> rings = new CopyOnWriteArrayList<>();
            List<String> diagnostics = new CopyOnWriteArrayList<>();
            events = new DoorbirdEvents(api, "abc1230001", new DoorbirdEvents.Listener() {
                @Override public void onRing(String button) { rings.add(button); }
                @Override public void onConnection(boolean connected, String detail) {}
                @Override public void onDiagnostic(String message) { diagnostics.add(message); }
            });
            events.start();
            Await.until(() -> rings.size() == 1, 5000, "doorbell:H over monitor.cgi");
            check(rings.get(0), "1");
            check(diagnostics.contains("monitor: doorbell:H received"), true);
            System.out.println("DoorbirdMonitorTest passed");
        } finally {
            if (events != null) events.stop();
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (exchange.getRequestHeaders().getFirst("Authorization") == null) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"DoorBird\", nonce=\"abc123\", qop=\"auth\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (path.equals("/bha-api/monitor.cgi")) {
            exchange.sendResponseHeaders(200, 0);
            try { Thread.sleep(1500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.getResponseBody().write("--ioboundary\r\nContent-Type: text/plain\r\n\r\ndoorbell:H\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            exchange.getResponseBody().flush();
            try { Thread.sleep(2000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            exchange.close();
            return;
        }
        byte[] body = "{\"BHA\":{\"RETURNCODE\":\"1\",\"SESSIONID\":\"session\",\"NOTIFICATION_ENCRYPTION_KEY\":\"BHYGHyRKtGzBjku2t2jX2UKidXYQ3VqmfbKoCtxXJ6O4lgSzpgIwZ6onrSh\"}}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
