// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.api;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import io.github.bjoern3003.doorbird.ThreadGuard;

/** DoorBird event monitoring v2: authenticated UDP broadcasts on ports 6524 and 35344. */
public final class DoorbirdEvents {
    public interface Listener {
        void onRing(String button);
        void onConnection(boolean connected, String detail);
    }

    public static final class Event {
        public final String intercomId, button;
        public final long timestamp;

        Event(String intercomId, String button, long timestamp) {
            this.intercomId = intercomId;
            this.button = button;
            this.timestamp = timestamp;
        }
    }

    private static final int[] PORTS = {6524, 35344};
    private final DoorbirdHttp api;
    private final String username;
    private final Listener listener;
    private final List<DatagramSocket> sockets = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread starter;

    public DoorbirdEvents(DoorbirdHttp api, String username, Listener listener) {
        this.api = api;
        this.username = username;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        starter = ThreadGuard.start("doorbird-events-setup", this::open);
    }

    public void stop() {
        running = false;
        List<DatagramSocket> open;
        List<Thread> active;
        synchronized (this) {
            open = new ArrayList<>(sockets);
            active = new ArrayList<>(threads);
            sockets.clear();
            threads.clear();
            if (starter != null) starter.interrupt();
        }
        for (DatagramSocket socket : open) socket.close();
        for (Thread thread : active) {
            thread.interrupt();
            if (thread != Thread.currentThread()) {
                try { thread.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            }
        }
    }

    private void open() {
        while (running) {
            try {
                listener.onConnection(false, "connecting");
                DoorbirdHttp.Session session = api.session();
                if (!running) return;
                for (int port : PORTS) {
                    try {
                        DatagramSocket socket = new DatagramSocket(null);
                        socket.setReuseAddress(true);
                        socket.setBroadcast(true);
                        socket.bind(new InetSocketAddress("0.0.0.0", port));
                        socket.setSoTimeout(1_000);
                        synchronized (this) {
                            if (!running) { socket.close(); return; }
                            sockets.add(socket);
                            threads.add(ThreadGuard.start("doorbird-events-" + port, () -> receive(socket, session.notificationKey)));
                        }
                    } catch (Exception refused) {
                        if (sockets.isEmpty()) listener.onConnection(false, describe(refused));
                    }
                }
                synchronized (this) {
                    if (!sockets.isEmpty()) {
                        listener.onConnection(true, "listening for encrypted DoorBird broadcasts");
                        return;
                    }
                }
            } catch (Exception error) {
                if (running) listener.onConnection(false, describe(error));
            }
            try { Thread.sleep(5_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    private void receive(DatagramSocket socket, byte[] key) {
        byte[] buffer = new byte[128];
        while (running && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                Event event = decode(Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength()), key, username);
                if (event == null || !freshTimestamp(event, System.currentTimeMillis() / 1000) || duplicate(event)) continue;
                listener.onRing(event.button);
            } catch (SocketTimeoutException quiet) {
                // Poll running once a second.
            } catch (Exception closed) {
                if (running && !socket.isClosed()) listener.onConnection(false, describe(closed));
            }
        }
    }

    public static boolean freshTimestamp(Event event, long nowSeconds) {
        long age = nowSeconds - event.timestamp;
        return age >= -300 && age <= 300;
    }

    private boolean duplicate(Event event) {
        long now = System.nanoTime() / 1_000_000;
        String key = event.intercomId + ":" + event.button + ":" + event.timestamp;
        Long previous = recent.put(key, now);
        if (recent.size() > 64) {
            Iterator<Map.Entry<String, Long>> iterator = recent.entrySet().iterator();
            while (iterator.hasNext()) if (now - iterator.next().getValue() > 60_000) iterator.remove();
        }
        return previous != null && now - previous < 60_000;
    }

    public static Event decode(byte[] packet, byte[] key, String username) {
        if (packet.length != 46 || (packet[0] & 0xff) != 0xde || (packet[1] & 0xff) != 0xad || (packet[2] & 0xff) != 0xbe || packet[3] != 2) return null;
        try {
            byte[] plain = ChaCha20Poly1305.decrypt(key, Arrays.copyOfRange(packet, 4, 12), Arrays.copyOfRange(packet, 12, packet.length));
            if (plain.length != 18) return null;
            String intercom = new String(plain, 0, 6, StandardCharsets.US_ASCII);
            if (!intercom.equals(username.substring(0, Math.min(6, username.length())))) return null;
            String event = new String(plain, 6, 8, StandardCharsets.US_ASCII).trim();
            if (!event.equalsIgnoreCase("doorbell") && !event.matches("[0-9]+")) return null;
            long timestamp = ((plain[14] & 0xffL) << 24) | ((plain[15] & 0xffL) << 16) | ((plain[16] & 0xffL) << 8) | (plain[17] & 0xffL);
            return new Event(intercom, event, timestamp);
        } catch (GeneralSecurityException | RuntimeException invalid) {
            return null;
        }
    }

    private static String describe(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
