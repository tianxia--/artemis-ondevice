package com.artemis.ondevice;

import android.util.Base64;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A minimal WebSocket client (RFC 6455) on platform sockets, for realtime voice models: text
 * messages only, one reader thread, writes from any thread. No extensions, no compression.
 */
final class RealtimeSocket {

    interface Listener {
        /** A complete text message (on the reader thread). */
        void onMessage(String text);

        /** The connection ended; error is null when it closed normally. Called once. */
        void onClosed(Exception error);
    }

    /** The server refused the upgrade (bad key, unknown model, …): HTTP status and body. */
    static final class HandshakeException extends IOException {
        final int status;

        HandshakeException(int status, String body) {
            super("HTTP " + status + (body.isEmpty() ? "" : ": " + body));
            this.status = status;
        }
    }

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final java.util.concurrent.locks.ReentrantLock writeLock = new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean closed;
    private volatile Listener listener;

    private RealtimeSocket(Socket socket, InputStream in) throws IOException {
        this.socket = socket;
        this.in = in;
        this.out = socket.getOutputStream();
    }

    /** Opens the connection and completes the handshake; throws on refusal or timeout. */
    static RealtimeSocket connect(String url, Map<String, String> headers, int timeoutMs) throws IOException {
        URI u = URI.create(url);
        boolean tls = "wss".equalsIgnoreCase(u.getScheme());
        if (!tls && !"ws".equalsIgnoreCase(u.getScheme())) throw new IOException("Not a WebSocket URL");
        String host = u.getHost();
        int port = u.getPort() > 0 ? u.getPort() : tls ? 443 : 80;
        Socket s;
        if (tls) {
            SSLSocket ssl = (SSLSocket) SSLSocketFactory.getDefault().createSocket();
            ssl.connect(new InetSocketAddress(host, port), timeoutMs);
            // createSocket() without a host skips SNI and hostname checks: do both explicitly.
            javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
            params.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(host)));
            ssl.setSSLParameters(params);
            ssl.setSoTimeout(timeoutMs);
            ssl.startHandshake();
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, ssl.getSession())) {
                ssl.close();
                throw new IOException("Certificate does not match " + host);
            }
            s = ssl;
        } else {
            s = new Socket();
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
        }
        s.setTcpNoDelay(true);
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            byte[] nonce = new byte[16];
            RANDOM.nextBytes(nonce);
            String key = Base64.encodeToString(nonce, Base64.NO_WRAP);
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath())
                    + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            StringBuilder req = new StringBuilder()
                    .append("GET ").append(path).append(" HTTP/1.1\r\n")
                    .append("Host: ").append(host).append(u.getPort() > 0 ? ":" + port : "").append("\r\n")
                    .append("Upgrade: websocket\r\nConnection: Upgrade\r\n")
                    .append("Sec-WebSocket-Key: ").append(key).append("\r\n")
                    .append("Sec-WebSocket-Version: 13\r\n");
            for (Map.Entry<String, String> h : headers.entrySet()) {
                req.append(h.getKey()).append(": ").append(h.getValue()).append("\r\n");
            }
            req.append("\r\n");
            s.getOutputStream().write(req.toString().getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();

            String status = readLine(in);
            int code = status.length() >= 12 ? Integer.parseInt(status.substring(9, 12)) : 0;
            String accept = null;
            int length = 0;
            for (String line; !(line = readLine(in)).isEmpty(); ) {
                int colon = line.indexOf(':');
                if (colon < 0) continue;
                String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                String value = line.substring(colon + 1).trim();
                if (name.equals("sec-websocket-accept")) accept = value;
                if (name.equals("content-length")) length = Math.min(4096, Integer.parseInt(value));
            }
            if (code != 101) {
                byte[] body = new byte[length];
                int n = 0;
                while (n < length) {
                    int r = in.read(body, n, length - n);
                    if (r < 0) break;
                    n += r;
                }
                throw new HandshakeException(code, new String(body, 0, n, StandardCharsets.UTF_8).trim());
            }
            String expected = Base64.encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key + GUID).getBytes(StandardCharsets.US_ASCII)), Base64.NO_WRAP);
            if (!expected.equals(accept)) throw new IOException("Bad WebSocket handshake");
            s.setSoTimeout(0); // the reader blocks until the next message; the caller decides when to give up
            return new RealtimeSocket(s, in);
        } catch (IOException e) {
            s.close();
            throw e;
        } catch (Exception e) {
            s.close();
            throw new IOException(e.getMessage(), e);
        }
    }

    /** Starts the reader thread; messages and the close go to the listener. */
    void start(Listener l) {
        listener = l;
        Thread t = new Thread(this::readLoop, "artemis-realtime");
        t.setDaemon(true);
        t.start();
    }

    void send(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Closes politely when it can; the listener's onClosed follows. Never waits for a write that is
     * stuck on a slow network: closing the socket is what unblocks it.
     */
    void close() {
        if (closed) return;
        if (writeLock.tryLock()) {
            try {
                writeFrame(0x8, new byte[]{0x03, (byte) 0xE8}); // 1000: normal closure
            } catch (IOException ignored) {
                // The connection is going away anyway.
            } finally {
                writeLock.unlock();
            }
        }
        shutdown(null);
    }

    boolean isOpen() { return !closed; }

    // ------------------------------------------------------------------ frames

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        writeLock.lock();
        try {
            writeFrame(opcode, payload);
        } finally {
            writeLock.unlock();
        }
    }

    /** Caller holds writeLock. */
    private void writeFrame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("Connection closed");
        byte[] mask = new byte[4];
        RANDOM.nextBytes(mask);
        ByteArrayOutputStream f = new ByteArrayOutputStream(payload.length + 14);
        f.write(0x80 | opcode);
        int n = payload.length;
        if (n < 126) {
            f.write(0x80 | n);
        } else if (n < 65536) {
            f.write(0x80 | 126);
            f.write(n >>> 8);
            f.write(n);
        } else {
            f.write(0x80 | 127);
            for (int shift = 56; shift >= 0; shift -= 8) f.write((int) ((long) n >>> shift));
        }
        f.write(mask, 0, 4);
        byte[] masked = new byte[n];
        for (int i = 0; i < n; i++) masked[i] = (byte) (payload[i] ^ mask[i & 3]);
        f.write(masked, 0, n);
        out.write(f.toByteArray());
        out.flush();
    }

    private void readLoop() {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        try {
            while (!closed) {
                int b0 = readByte(), b1 = readByte();
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = (readByte() << 8) | readByte();
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len > MAX_MESSAGE) throw new IOException("Message too large: " + len);
                byte[] payload = readFully((int) len); // servers never mask
                switch (opcode) {
                    case 0x0: // continuation
                    case 0x1: // text
                        message.write(payload, 0, payload.length);
                        if (message.size() > MAX_MESSAGE) throw new IOException("Message too large");
                        if (fin) {
                            String text = message.toString("UTF-8");
                            message.reset();
                            listener.onMessage(text);
                        }
                        break;
                    case 0x8: // close
                        shutdown(null);
                        return;
                    case 0x9: // ping
                        sendFrame(0xA, payload);
                        break;
                    default: // pong, binary: not used by these APIs
                        break;
                }
            }
        } catch (Exception e) {
            shutdown(closed ? null : e);
        }
    }

    private void shutdown(Exception error) {
        synchronized (this) {
            if (closed) return;
            closed = true;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Already gone.
        }
        Listener l = listener;
        if (l != null) l.onClosed(error);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new EOFException("Connection closed by the server");
        return b;
    }

    private byte[] readFully(int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("Connection closed by the server");
            off += r;
        }
        return buf;
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int c; (c = in.read()) != '\n'; ) {
            if (c < 0) throw new EOFException("Connection closed during the handshake");
            if (c != '\r') sb.append((char) c);
        }
        return sb.toString();
    }
}
