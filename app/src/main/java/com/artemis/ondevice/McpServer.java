package com.artemis.ondevice;

import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * MCP server over Streamable HTTP: JSON-RPC requests are POSTed to {@code /mcp} and answered with
 * a plain JSON body (no SSE stream). Every request needs {@code Authorization: Bearer <token>};
 * requests from web pages (an Origin header that is not localhost) are refused, against DNS
 * rebinding. Logcat gets method and tool names only, never arguments or the token.
 */
final class McpServer {

    static final String PATH = "/mcp";
    private static final List<String> PROTOCOL_VERSIONS = Arrays.asList("2025-11-25", "2025-06-18", "2025-03-26");
    private static final int MAX_HEADER_BYTES = 16 * 1024;
    private static final int MAX_BODY_BYTES = 1024 * 1024;
    private static final int READ_TIMEOUT_MS = 30_000;

    private final McpTools tools;
    private final String version;
    private final byte[] token;
    private final InetAddress bind;
    private final int port;
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private volatile ServerSocket socket;

    McpServer(McpTools tools, String version, String token, InetAddress bind, int port) {
        this.tools = tools;
        this.version = version;
        this.token = token.getBytes(StandardCharsets.UTF_8);
        this.bind = bind;
        this.port = port;
    }

    void start() throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(bind, port));
        socket = s;
        Thread accept = new Thread(() -> {
            while (!s.isClosed()) {
                try {
                    Socket c = s.accept();
                    pool.execute(() -> serve(c));
                } catch (IOException e) {
                    if (!s.isClosed()) Log.w(AgentService.TAG, "mcp accept failed: " + e.getMessage());
                }
            }
        }, "artemis-mcp");
        accept.start();
    }

    void stop() {
        try {
            ServerSocket s = socket;
            if (s != null) s.close();
        } catch (IOException ignored) {
            // Closing is best effort; the port is released with the process anyway.
        }
        pool.shutdownNow();
    }

    // ------------------------------------------------------------------ HTTP

    private void serve(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(READ_TIMEOUT_MS);
            InputStream in = new BufferedInputStream(sock.getInputStream());
            OutputStream out = sock.getOutputStream();
            String head = readHead(in);
            if (head == null) return;
            String[] lines = head.split("\r\n");
            String[] request = lines[0].split(" ");
            if (request.length < 2) {
                respond(out, 400, null, "");
                return;
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) headers.put(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
            String method = request[0];
            String path = request[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);
            if (!PATH.equals(path)) {
                respond(out, 404, null, "");
                return;
            }
            if (!localOrigin(headers.get("origin"))) {
                respond(out, 403, null, "");
                return;
            }
            if (!authorized(headers.get("authorization"))) {
                respond(out, 401, null, "{\"error\":\"missing or wrong bearer token\"}");
                return;
            }
            if (!"POST".equals(method)) {
                // No server-initiated stream (GET) and nothing to clean up per session (DELETE).
                respond(out, "DELETE".equals(method) ? 200 : 405, null, "");
                return;
            }
            int length;
            try {
                length = Integer.parseInt(headers.getOrDefault("content-length", "-1"));
            } catch (NumberFormatException e) {
                length = -1;
            }
            if (length < 0 || length > MAX_BODY_BYTES) {
                respond(out, 413, null, "");
                return;
            }
            String body = new String(readBody(in, length), StandardCharsets.UTF_8);
            handle(out, body);
        } catch (Exception e) {
            Log.w(AgentService.TAG, "mcp connection: " + e.getClass().getSimpleName());
        }
    }

    private void handle(OutputStream out, String body) throws IOException {
        JSONObject msg;
        try {
            msg = new JSONObject(body);
        } catch (Exception e) {
            respond(out, 400, null, error(null, -32700, "Parse error").toString());
            return;
        }
        // Notifications and responses from the client need no answer.
        if (!msg.has("id") || !msg.has("method")) {
            respond(out, 202, null, "");
            return;
        }
        Object id = msg.opt("id");
        String method = msg.optString("method");
        JSONObject params = msg.optJSONObject("params");
        if (params == null) params = new JSONObject();
        Log.i(AgentService.TAG, "mcp " + method + ("tools/call".equals(method) ? " " + params.optString("name") : ""));
        String session = null;
        JSONObject reply;
        try {
            switch (method) {
                case "initialize":
                    session = UUID.randomUUID().toString();
                    reply = result(id, initialize(params));
                    break;
                case "ping":
                    reply = result(id, new JSONObject());
                    break;
                case "tools/list":
                    reply = result(id, new JSONObject().put("tools", tools.list()));
                    break;
                case "tools/call":
                    reply = result(id, tools.call(params.optString("name"), params.optJSONObject("arguments")));
                    break;
                default:
                    reply = error(id, -32601, "Method not found: " + method);
            }
        } catch (Exception e) {
            reply = error(id, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        respond(out, 200, session, reply.toString());
    }

    private JSONObject initialize(JSONObject params) throws Exception {
        String asked = params.optString("protocolVersion");
        return new JSONObject()
                .put("protocolVersion", PROTOCOL_VERSIONS.contains(asked) ? asked : PROTOCOL_VERSIONS.get(0))
                .put("capabilities", new JSONObject().put("tools", new JSONObject().put("listChanged", false)))
                .put("serverInfo", new JSONObject().put("name", "artemis-ondevice").put("version", version))
                .put("instructions", "Controls the Android phone this server runs on. Use run_task to let the phone's own "
                        + "agent finish a task, or observe + tap/type_text/swipe/... to operate the phone yourself. "
                        + "Element indices refer to the latest observe call; observe again after acting.");
    }

    private static JSONObject result(Object id, JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result);
    }

    private static JSONObject error(Object id, int code, String message) {
        try {
            return new JSONObject().put("jsonrpc", "2.0").put("id", id == null ? JSONObject.NULL : id)
                    .put("error", new JSONObject().put("code", code).put("message", message));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean authorized(String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        return MessageDigest.isEqual(token, header.substring(7).trim().getBytes(StandardCharsets.UTF_8));
    }

    /** No Origin (desktop clients) or a localhost one; any other origin is a web page. */
    private static boolean localOrigin(String origin) {
        if (origin == null || origin.isEmpty()) return true;
        String host = origin.replaceFirst("^[a-zA-Z]+://", "").replaceFirst("[:/].*$", "");
        return host.equals("localhost") || host.equals("127.0.0.1") || host.equals("[::1]");
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int last = 0; // the last four bytes, to spot the blank line that ends the head
        while (buf.size() < MAX_HEADER_BYTES) {
            int b = in.read();
            if (b < 0) return null;
            buf.write(b);
            last = (last << 8) | b;
            if (last == 0x0d0a0d0a) return buf.toString("ISO-8859-1");
        }
        return null;
    }

    private static byte[] readBody(InputStream in, int length) throws IOException {
        byte[] body = new byte[length];
        int off = 0;
        while (off < length) {
            int n = in.read(body, off, length - off);
            if (n < 0) throw new IOException("body cut short");
            off += n;
        }
        return body;
    }

    private static void respond(OutputStream out, int code, String session, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (bytes.length > 0) h.append("Content-Type: application/json\r\n");
        if (session != null) h.append("Mcp-Session-Id: ").append(session).append("\r\n");
        h.append("Content-Length: ").append(bytes.length).append("\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(bytes);
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            default: return "Error";
        }
    }
}
