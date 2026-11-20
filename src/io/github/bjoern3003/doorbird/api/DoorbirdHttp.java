// SPDX-License-Identifier: Apache-2.0
package io.github.bjoern3003.doorbird.api;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small DoorBird LAN HTTP client with Digest authentication and bounded non-streaming responses. */
public final class DoorbirdHttp {
    public static final int MAX_RESPONSE = 64 * 1024;
    private static final Pattern FIELD = Pattern.compile("(\\w+)=(?:\"([^\"]*)\"|([^,\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final SecureRandom RANDOM = new SecureRandom();

    public static final class Session {
        public final String id;
        public final byte[] notificationKey;

        Session(String id, byte[] notificationKey) {
            this.id = id;
            this.notificationKey = notificationKey;
        }
    }

    public static final class Exchange implements Closeable {
        public final InputStream input;
        public final OutputStream output;
        private final HttpURLConnection connection;
        private final Socket socket;

        Exchange(HttpURLConnection connection, InputStream input, OutputStream output) {
            this(connection, null, input, output);
        }

        Exchange(Socket socket, InputStream input, OutputStream output) {
            this(null, socket, input, output);
        }

        private Exchange(HttpURLConnection connection, Socket socket, InputStream input, OutputStream output) {
            this.connection = connection;
            this.socket = socket;
            this.input = input;
            this.output = output;
        }

        @Override public void close() {
            try { if (input != null) input.close(); } catch (IOException ignored) {}
            try { if (output != null) output.close(); } catch (IOException ignored) {}
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            if (connection != null) connection.disconnect();
        }
    }

    private final String host, username, password;
    private final int port;
    private String realm, nonce, qop;

    public DoorbirdHttp(String host, int port, String username, String password) {
        if (host == null || host.isEmpty() || host.matches(".*[\\s/@?#].*")) throw new IllegalArgumentException("Invalid DoorBird address");
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
    }

    public byte[] get(String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = connection("GET", path, 5_000, 5_000);
            try {
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0 && challenge(connection)) continue;
                checkStatus(status);
                return readLimited(connection.getInputStream(), MAX_RESPONSE);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("The DoorBird refused the username or password");
    }

    public Exchange openGet(String path) throws IOException {
        for (int attempt = 0; attempt < 2; attempt++) {
            HttpURLConnection connection = connection("GET", path, 5_000, 10_000);
            if (path.startsWith("/bha-api/monitor.cgi")) {
                connection.setReadTimeout(0);
                connection.setRequestProperty("Connection", "Keep-Alive");
            }
            try {
                int status = connection.getResponseCode();
                if (status == 401 && attempt == 0 && challenge(connection)) {
                    connection.disconnect();
                    continue;
                }
                checkStatus(status);
                return new Exchange(connection, connection.getInputStream(), null);
            } catch (IOException error) {
                connection.disconnect();
                throw error;
            }
        }
        throw new IOException("The DoorBird refused the username or password");
    }

    /** The caller preflights info.cgi. HTTP/1.0 lets DoorBird accept the stream before any audio is sent. */
    public Exchange openPost(String path, int contentLength) throws IOException {
        if (!path.startsWith("/bha-api/")) throw new IOException("Invalid DoorBird API path");
        String authorization = authorization("POST", path);
        if (authorization == null) throw new IOException("DoorBird authentication has not been checked");
        Socket socket = new Socket();
        try {
            socket.setTcpNoDelay(true);
            socket.connect(new InetSocketAddress(host, port), 5_000);
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            String request = "POST " + path + " HTTP/1.0\r\n"
                + "Host: " + host + ":" + port + "\r\n"
                + "User-Agent: ks-doorbird-intercom\r\n"
                + "Authorization: " + authorization + "\r\n"
                + "Content-Type: audio/basic\r\n"
                + "Content-Length: " + contentLength + "\r\n"
                + "Connection: Keep-Alive\r\n"
                + "Cache-Control: no-cache\r\n\r\n";
            output.write(request.getBytes(StandardCharsets.US_ASCII));
            output.flush();
            InputStream input = socket.getInputStream();
            String statusLine = readLine(input);
            String[] parts = statusLine.split(" ");
            if (parts.length < 2 || !parts[0].startsWith("HTTP/")) throw new IOException("Invalid DoorBird HTTP response");
            int status;
            try { status = Integer.parseInt(parts[1]); } catch (NumberFormatException invalid) { throw new IOException("Invalid DoorBird HTTP status"); }
            int headers = 0;
            for (String line = readLine(input); !line.isEmpty(); line = readLine(input)) if (++headers > 100) throw new IOException("Too many DoorBird response headers");
            checkStatus(status);
            socket.setSoTimeout(0);
            return new Exchange(socket, null, output);
        } catch (IOException error) {
            try { socket.close(); } catch (IOException ignored) {}
            throw error;
        }
    }

    public Session session() throws IOException {
        String body = new String(get("/bha-api/getsession.cgi"), StandardCharsets.UTF_8);
        String id = jsonString(body, "SESSIONID");
        String key = jsonString(body, "NOTIFICATION_ENCRYPTION_KEY");
        if (!"1".equals(jsonString(body, "RETURNCODE")) || id == null || key == null || key.length() < 32) {
            throw new IOException("The DoorBird returned no notification session");
        }
        return new Session(id, key.getBytes(StandardCharsets.US_ASCII));
    }

    public String info() throws IOException {
        String body = new String(get("/bha-api/info.cgi"), StandardCharsets.UTF_8);
        if (!"1".equals(jsonString(body, "RETURNCODE"))) throw new IOException("The DoorBird info request failed");
        return body;
    }

    private HttpURLConnection connection(String method, String path, int connectTimeout, int readTimeout) throws IOException {
        if (!path.startsWith("/bha-api/")) throw new IOException("Invalid DoorBird API path");
        HttpURLConnection connection = (HttpURLConnection) new URL("http", host, port, path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(connectTimeout);
        connection.setReadTimeout(readTimeout);
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setRequestProperty("User-Agent", "ks-doorbird-intercom");
        connection.setRequestProperty("Connection", method.equals("POST") ? "Keep-Alive" : "close");
        String authorization = authorization(method, path);
        if (authorization != null) connection.setRequestProperty("Authorization", authorization);
        return connection;
    }

    private boolean challenge(HttpURLConnection connection) throws IOException {
        String header = connection.getHeaderField("WWW-Authenticate");
        if (header == null || !header.toLowerCase(Locale.ROOT).startsWith("digest")) return false;
        String realm = field(header, "realm"), nonce = field(header, "nonce"), qop = field(header, "qop");
        if (realm == null || nonce == null) return false;
        synchronized (this) {
            this.realm = realm;
            this.nonce = nonce;
            this.qop = qop != null && qop.toLowerCase(Locale.ROOT).contains("auth") ? "auth" : null;
        }
        return true;
    }

    private synchronized String authorization(String method, String path) {
        if (realm == null || nonce == null) return null;
        String cnonce = null, count = null;
        String response;
        if (qop == null) {
            response = md5(md5(username + ":" + realm + ":" + password) + ":" + nonce + ":" + md5(method + ":" + path));
        } else {
            byte[] random = new byte[8];
            RANDOM.nextBytes(random);
            StringBuilder hex = new StringBuilder();
            for (byte b : random) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            cnonce = hex.toString();
            count = "00000001";
            response = md5(md5(username + ":" + realm + ":" + password) + ":" + nonce + ":" + count + ":" + cnonce + ":" + qop + ":" + md5(method + ":" + path));
        }
        StringBuilder value = new StringBuilder("Digest username=\"").append(username).append("\", realm=\"").append(realm)
            .append("\", nonce=\"").append(nonce).append("\", uri=\"").append(path).append("\", response=\"").append(response).append('"');
        if (qop != null) value.append(", qop=").append(qop).append(", nc=").append(count).append(", cnonce=\"").append(cnonce).append('"');
        return value.toString();
    }

    private static String field(String header, String name) {
        Matcher matcher = FIELD.matcher(header);
        while (matcher.find()) if (matcher.group(1).equalsIgnoreCase(name)) return matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
        return null;
    }

    private static String jsonString(String body, String key) {
        Matcher matcher = Pattern.compile("\\\"" + Pattern.quote(key) + "\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"").matcher(body);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static void checkStatus(int status) throws IOException {
        if (status == 200) return;
        if (status == 401) throw new IOException("The DoorBird refused the username or password");
        if (status == 204) throw new IOException("Live access is currently not permitted; enable Watch always or use a recent ring");
        if (status == 423) throw new IOException("The DoorBird temporarily blocked login after wrong credentials");
        if (status == 503) throw new IOException("The DoorBird is busy with another call");
        throw new IOException("DoorBird HTTP " + status);
    }

    static byte[] readLimited(InputStream input, int maximum) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int total = 0;
        for (int count = input.read(buffer); count >= 0; count = input.read(buffer)) {
            total += count;
            if (total > maximum) throw new IOException("DoorBird response is too large");
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int c = input.read(); c >= 0 && c != '\n'; c = input.read()) {
            if (c == '\r') continue;
            if (line.size() >= 8192) throw new IOException("DoorBird response header is too long");
            line.write(c);
        }
        return new String(line.toByteArray(), StandardCharsets.US_ASCII);
    }

    private static String md5(String text) {
        try {
            StringBuilder hex = new StringBuilder();
            for (byte b : MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8))) hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
