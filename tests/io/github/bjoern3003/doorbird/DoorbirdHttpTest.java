// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.bjoern3003.doorbird.api.DoorbirdHttp;
import io.github.bjoern3003.doorbird.talk.DoorbirdTalk;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public final class DoorbirdHttpTest {
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> authorizations = new CopyOnWriteArrayList<>();
        AtomicInteger audioBytes = new AtomicInteger();
        server.createContext("/bha-api/", exchange -> handle(exchange, authorizations, audioBytes));
        server.start();
        try {
            DoorbirdHttp api = new DoorbirdHttp("127.0.0.1", server.getAddress().getPort(), "abc1230001", "secret");
            DoorbirdHttp.Session session = api.session();
            check(session.id, "session");
            check(session.notificationKey.length >= 32 && session.notificationKey.length <= 64, true);
            check(api.info().contains("DoorBird D101"), true);
            check(authorizations.size() >= 2, true);
            check(authorizations.get(1).startsWith("Digest username=\"abc1230001\""), true);
            check(authorizations.get(1).contains("qop=auth"), true);
            check(authorizations.get(1).contains("secret"), false);
            try (DoorbirdHttp.Exchange audio = api.openGet("/bha-api/audio-receive.cgi")) {
                check(audio.input.read(), 0xff);
            }
            List<String> states = new CopyOnWriteArrayList<>();
            DoorbirdTalk talk = new DoorbirdTalk(api, states::add);
            talk.start();
            talk.write(new short[320], 320);
            Await.until(() -> audioBytes.get() >= 160 && states.contains("open"), 5000, "DoorBird talk streaming");
            talk.stop();
            try {
                new DoorbirdHttp("http://127.0.0.1", 80, "u", "p");
                throw new AssertionError("URL instead of host accepted");
            } catch (IllegalArgumentException expected) {}
            System.out.println("DoorbirdHttpTest passed");
        } finally {
            server.stop(0);
        }
    }

    private static void handle(HttpExchange exchange, List<String> authorizations, AtomicInteger audioBytes) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (authorization != null) authorizations.add(authorization);
        if (authorization == null) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Digest realm=\"DoorBird\", nonce=\"abc123\", qop=\"auth\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
            return;
        }
        if (path.equals("/bha-api/audio-transmit.cgi")) {
            exchange.sendResponseHeaders(200, 0);
            byte[] buffer = new byte[160];
            int count = exchange.getRequestBody().read(buffer);
            if (count > 0) audioBytes.addAndGet(count);
            exchange.getResponseBody().close();
            exchange.close();
            return;
        }
        if (path.equals("/bha-api/audio-receive.cgi")) {
            exchange.sendResponseHeaders(200, 1);
            exchange.getResponseBody().write(0xff);
            exchange.close();
            return;
        }
        String body = path.equals("/bha-api/getsession.cgi")
            ? "{\"BHA\":{\"RETURNCODE\":\"1\",\"SESSIONID\":\"session\",\"NOTIFICATION_ENCRYPTION_KEY\":\"BHYGHyRKtGzBjku2t2jX2UKidXYQ3VqmfbKoCtxXJ6O4lgSzpgIwZ6onrSh\"}}"
            : "{\"BHA\":{\"RETURNCODE\":\"1\",\"VERSION\":[{\"DEVICE-TYPE\":\"DoorBird D101\"}]}}";
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
