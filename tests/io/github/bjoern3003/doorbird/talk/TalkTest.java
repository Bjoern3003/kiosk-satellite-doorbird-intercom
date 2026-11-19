// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.talk;

import java.security.MessageDigest;
import java.util.Arrays;

public final class TalkTest {
    public static void main(String[] args) throws Exception {
        g711MatchesReference();
        downsamplerFilters();
        queueCushionCapAndClear();
        System.out.println("TalkTest passed");
    }

    static void g711MatchesReference() throws Exception {
        byte[] ulaw = new byte[65536], alaw = new byte[65536];
        for (int s = -32768; s < 32768; s++) {
            ulaw[s + 32768] = G711.ulaw(s);
            alaw[s + 32768] = G711.alaw(s);
        }
        check(sha256(ulaw), "81d633c9e6972a18c74a58720b96cb8ca0bdd096d4060b646dd708c3b846019a");
        check(sha256(alaw), "38488f6fd710f4686360edc4d38639f96c491595ef93f8eb8d62d5e07ca6ce7b");
        check((int) G711.ulawToLinear(0xff), 0);
        check(G711.ulawToLinear(0x00) < 0, true);
    }

    static void downsamplerFilters() {
        for (int rate : new int[] {48000, 16000}) {
            check(peakAfter(rate, 1000) > 9000, true);
            check(peakAfter(rate, 6000) < 1500, true);
        }
        check(peakAfter(8000, 1000) > 9900, true);
    }

    static void queueCushionCapAndClear() {
        TalkQueue q = new TalkQueue(320, 1600);
        short[] out = new short[160];
        q.write(filled(160, (short) 100), 160);
        q.next(out, 160);
        check(out[0], (short) 0);
        q.write(filled(160, (short) 200), 160);
        q.next(out, 160);
        check(out[0], (short) 100);
        q.next(out, 160);
        check(out[0], (short) 200);
        q.next(out, 160);
        check(out[0], (short) 0);
        q.write(filled(8000, (short) 7), 8000);
        int played = 0;
        for (int i = 0; i < 20; i++) {
            q.next(out, 160);
            if (out[0] == 7) played++;
        }
        check(played, 10);
        q.write(filled(800, (short) 9), 800);
        q.clear();
        q.next(out, 160);
        check(out[0], (short) 0);
    }

    private static int peakAfter(int rate, int frequency) {
        Downsampler d = new Downsampler(rate, 8000);
        short[] in = new short[rate], out = new short[8000];
        for (int i = 0; i < in.length; i++) in[i] = (short) (10000 * Math.sin(2 * Math.PI * frequency * i / rate));
        int n = d.process(in, in.length, out, 0);
        check(n, 8000);
        int peak = 0;
        for (int i = 1000; i < n; i++) peak = Math.max(peak, Math.abs(out[i]));
        return peak;
    }

    private static short[] filled(int count, short value) {
        short[] samples = new short[count];
        Arrays.fill(samples, value);
        return samples;
    }

    private static String sha256(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data)) hex.append(String.format("%02x", b & 0xFF));
        return hex.toString();
    }

    private static void check(Object actual, Object expected) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + " but got " + actual);
    }
}
