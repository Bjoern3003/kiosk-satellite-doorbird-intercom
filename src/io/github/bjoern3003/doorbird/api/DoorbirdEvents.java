// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.api;

import android.content.Context;
import android.net.wifi.WifiManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
        default void onDiagnostic(String message) {}
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
    private final Context appContext;
    private final List<DatagramSocket> sockets = new ArrayList<>();
    private final List<Thread> threads = new ArrayList<>();
    private final Map<String, Long> recent = new ConcurrentHashMap<>();
    private volatile boolean running;
    private Thread starter;
    private volatile WifiManager.MulticastLock multicastLock;
    private volatile DoorbirdHttp.Exchange monitorExchange;
    private Thread monitorThread;

    public DoorbirdEvents(DoorbirdHttp api, String username, Listener listener) {
        this(api, username, null, listener);
    }

    public DoorbirdEvents(DoorbirdHttp api, String username, Context appContext, Listener listener) {
        this.api = api;
        this.username = username;
        this.appContext = appContext;
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
        DoorbirdHttp.Exchange monitor = monitorExchange;
        monitorExchange = null;
        if (monitor != null) monitor.close();
        Thread monitorWorker = monitorThread;
        monitorThread = null;
        if (monitorWorker != null && monitorWorker != Thread.currentThread()) {
            monitorWorker.interrupt();
            try { monitorWorker.join(500); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
        WifiManager.MulticastLock lock = multicastLock;
        multicastLock = null;
        if (lock != null) {
            try { lock.release(); } catch (RuntimeException ignored) {}
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
                        listener.onDiagnostic("events: listening on UDP port " + port);
                        synchronized (this) {
                            if (!running) { socket.close(); return; }
                            sockets.add(socket);
                            if (sockets.size() == 1) acquireMulticastLock();
                            threads.add(ThreadGuard.start("doorbird-events-" + port, () -> receive(socket, session.notificationKey)));
                        }
                    } catch (Exception refused) {
                        if (sockets.isEmpty()) listener.onConnection(false, describe(refused));
                    }
                }
                startMonitor();
                synchronized (this) {
                    if (!sockets.isEmpty()) {
                        listener.onConnection(true, "listening for encrypted DoorBird broadcasts and the HTTP monitor");
                        return;
                    }
                    if (monitorThread != null) {
                        listener.onConnection(true, "listening for DoorBird rings over the HTTP monitor");
                        return;
                    }
                }
            } catch (Exception error) {
                if (running) listener.onConnection(false, describe(error));
            }
            try { Thread.sleep(5_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    private synchronized void startMonitor() {
        if (monitorThread != null || !running) return;
        monitorThread = ThreadGuard.start("doorbird-http-monitor", this::monitor);
    }

    private void monitor() {
        while (running) {
            try {
                listener.onDiagnostic("monitor: connecting to DoorBird monitor.cgi");
                DoorbirdHttp.Exchange opened = api.openGet("/bha-api/monitor.cgi?ring=doorbell");
                if (!running) { opened.close(); return; }
                monitorExchange = opened;
                listener.onDiagnostic("monitor: connected");
                listener.onConnection(true, "DoorBird HTTP monitor connected");
                ByteArrayOutputStream line = new ByteArrayOutputStream();
                while (running) {
                    String text = readMonitorLine(opened.input, line);
                    if (text == null) continue;
                    if (text.trim().equalsIgnoreCase("doorbell:H")) {
                        listener.onDiagnostic("monitor: doorbell:H received");
                        listener.onRing("1");
                    }
                }
            } catch (Throwable error) {
                if (running) listener.onDiagnostic("monitor: " + describe(error));
            } finally {
                DoorbirdHttp.Exchange opened = monitorExchange;
                monitorExchange = null;
                if (opened != null) opened.close();
            }
            try { Thread.sleep(5_000); } catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return; }
        }
    }

    private static String readMonitorLine(java.io.InputStream input, ByteArrayOutputStream line) throws IOException {
        while (true) {
            int c;
            try { c = input.read(); } catch (SocketTimeoutException waiting) { return null; }
            if (c < 0) throw new IOException("The DoorBird closed monitor.cgi");
            if (c == '\r') continue;
            if (c == '\n') {
                String result = new String(line.toByteArray(), StandardCharsets.US_ASCII);
                line.reset();
                return result;
            }
            if (line.size() >= 8192) throw new IOException("DoorBird monitor line is too long");
            line.write(c);
        }
    }

    private void acquireMulticastLock() {
        if (multicastLock != null) return;
        try {
            WifiManager wifi = appContext == null ? null : (WifiManager) appContext.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifi == null) return;
            WifiManager.MulticastLock lock = wifi.createMulticastLock("doorbird-intercom-events");
            lock.setReferenceCounted(false);
            lock.acquire();
            multicastLock = lock;
            listener.onDiagnostic("events: Wi-Fi multicast/broadcast lock acquired");
        } catch (Throwable unavailable) {
            listener.onDiagnostic("events: no Wi-Fi multicast lock: " + unavailable.getClass().getSimpleName());
        }
    }

    private void receive(DatagramSocket socket, byte[] key) {
        byte[] buffer = new byte[128];
        while (running && !socket.isClosed()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
                byte[] data = Arrays.copyOfRange(packet.getData(), packet.getOffset(), packet.getOffset() + packet.getLength());
                boolean notification = data.length == 46 && (data[0] & 0xff) == 0xde && (data[1] & 0xff) == 0xad && (data[2] & 0xff) == 0xbe && data[3] == 2;
                if (notification) listener.onDiagnostic("events: UDP v2 packet on port " + socket.getLocalPort());
                Event event = decode(data, key, username);
                if (event == null) {
                    if (notification) listener.onDiagnostic("events: packet was not accepted for the configured user/key");
                    continue;
                }
                long now = System.currentTimeMillis() / 1000;
                if (!freshTimestamp(event, now)) {
                    listener.onDiagnostic("events: rejected ring timestamp " + event.timestamp + ", panel time " + now);
                    continue;
                }
                if (duplicate(event)) continue;
                listener.onDiagnostic("events: accepted ring, button " + event.button);
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

    private static String describe(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
