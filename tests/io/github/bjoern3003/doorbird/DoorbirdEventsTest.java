// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird;

import io.github.bjoern3003.doorbird.api.ChaCha20Poly1305;
import io.github.bjoern3003.doorbird.api.DoorbirdEvents;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

public final class DoorbirdEventsTest {
    private static final String KEY = "BHYGHyRKtGzBjku2t2jX2UKidXYQ3VqmfbKoCtxXJ6O4lgSzpgIwZ6onrSh";

    public static void main(String[] args) throws Exception {
        byte[] packet = hex("deadbe02961380d4622ebee72a9fc3ff0bef6264f2ae919492148bbd30eb05bdce367c33d4293fafe060459e6510");
        byte[] plain = ChaCha20Poly1305.decrypt(KEY.getBytes(StandardCharsets.US_ASCII), Arrays.copyOfRange(packet, 4, 12), Arrays.copyOfRange(packet, 12, 46));
        check(hex(plain), "6768696b7a693120202020202020654d1351");
        DoorbirdEvents.Event event = DoorbirdEvents.decode(packet, KEY.getBytes(StandardCharsets.US_ASCII), "ghikzi0001");
        check(event.intercomId, "ghikzi");
        check(event.button, "1");
        check(event.timestamp, 1699550033L);
        check(DoorbirdEvents.decode(packet, KEY.getBytes(StandardCharsets.US_ASCII), "abcdef0001") == null, true);
        byte[] changed = packet.clone();
        changed[20] ^= 1;
        check(DoorbirdEvents.decode(changed, KEY.getBytes(StandardCharsets.US_ASCII), "ghikzi0001") == null, true);
        check(DoorbirdEvents.decode(new byte[46], KEY.getBytes(StandardCharsets.US_ASCII), "ghikzi0001") == null, true);
        try {
            ChaCha20Poly1305.decrypt(KEY.getBytes(StandardCharsets.US_ASCII), new byte[7], new byte[20]);
            throw new AssertionError("invalid nonce accepted");
        } catch (GeneralSecurityException expected) {}
        System.out.println("DoorbirdEventsTest passed");
    }

    static byte[] hex(String text) {
        byte[] bytes = new byte[text.length() / 2];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        return bytes;
    }

    static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder();
        for (byte b : bytes) text.append(String.format("%02x", b & 0xff));
        return text.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
