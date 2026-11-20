// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.talk;

import io.github.bjoern3003.doorbird.ThreadGuard;
import io.github.bjoern3003.doorbird.api.DoorbirdHttp;

/** Microphone audio to DoorBird's audio-transmit.cgi, paced as 8 kHz G.711 mu-law. */
public final class DoorbirdTalk implements TalkChannel {
    public interface Listener {
        void onTalkState(String state);
    }

    static final int PACKET_SAMPLES = 160;
    private static final int CONTENT_LENGTH = 2_000_000_000;
    private final DoorbirdHttp api;
    private final Listener listener;
    private final TalkQueue queue = new TalkQueue(320, 1600);
    private volatile boolean running;
    private DoorbirdHttp.Exchange exchange;
    private Thread thread;

    public DoorbirdTalk(DoorbirdHttp api, Listener listener) {
        this.api = api;
        this.listener = listener;
    }

    @Override public synchronized void start() {
        if (running) return;
        running = true;
        thread = ThreadGuard.start("doorbird-talk", this::run);
    }

    @Override public void stop() {
        running = false;
        DoorbirdHttp.Exchange current;
        synchronized (this) { current = exchange; }
        if (current != null) current.close();
        Thread active = thread;
        if (active != null && active != Thread.currentThread()) {
            active.interrupt();
            try { active.join(1_000); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    @Override public void write(short[] samples, int count) {
        queue.write(samples, count);
    }

    @Override public void clear() {
        queue.clear();
    }

    @Override public int rate() {
        return 8000;
    }

    void nextPacket(short[] out) {
        queue.next(out, PACKET_SAMPLES);
    }

    private void run() {
        try {
            listener.onTalkState("connecting");
            api.info();
            if (!running) return;
            DoorbirdHttp.Exchange opened = api.openPost("/bha-api/audio-transmit.cgi", CONTENT_LENGTH);
            synchronized (this) {
                if (!running) { opened.close(); return; }
                exchange = opened;
            }
            queue.dropQuietStart(500);
            listener.onTalkState("open");
            short[] samples = new short[PACKET_SAMPLES];
            byte[] encoded = new byte[PACKET_SAMPLES];
            long next = System.nanoTime();
            while (running) {
                nextPacket(samples);
                for (int i = 0; i < samples.length; i++) encoded[i] = G711.ulaw(samples[i]);
                opened.output.write(encoded);
                opened.output.flush();
                next += 20_000_000L;
                long sleep = next - System.nanoTime();
                if (sleep > 0) {
                    long millis = sleep / 1_000_000L;
                    Thread.sleep(millis, (int) (sleep % 1_000_000L));
                } else if (sleep < -500_000_000L) {
                    next = System.nanoTime();
                }
            }
        } catch (Exception error) {
            if (running) listener.onTalkState("talk failed: " + (error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()));
        } finally {
            DoorbirdHttp.Exchange current;
            synchronized (this) { current = exchange; exchange = null; }
            if (current != null) current.close();
        }
    }
}
