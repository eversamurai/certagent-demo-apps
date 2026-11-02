import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reasoning relay: sits between CertAgent and Ollama (OLLAMA_URL=http://localhost:11435) so the model's
 * reasoning can be shown live. CertAgent asks for a single non-streamed answer; the relay asks Ollama for a
 * stream instead, pushes every thinking token to /live (server-sent events), and hands CertAgent the same
 * single answer Ollama would have returned. Every other Ollama endpoint is passed through unchanged.
 */
public class ReasoningRelay {

    static final String UPSTREAM = System.getenv().getOrDefault("UPSTREAM", "http://host.docker.internal:11434");
    static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    static final List<BlockingQueue<String>> LISTENERS = new CopyOnWriteArrayList<>();
    static final Pattern CASE = Pattern.compile("Case: target (\\S+)");

    // What a page that connects mid-thought needs to catch up.
    static volatile boolean busy;
    static volatile String label = "";
    static final StringBuilder TAIL = new StringBuilder();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(11435), 0);
        server.createContext("/live", ReasoningRelay::live);
        server.createContext("/", ReasoningRelay::proxy);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("reasoning-relay on :11435 -> " + UPSTREAM);
    }

    // ---------------------------------------------------------------- live feed (server-sent events)

    static void live(HttpExchange ex) throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> q = new LinkedBlockingQueue<>();
        String tail;
        synchronized (TAIL) {
            tail = TAIL.toString();
        }
        q.add(event(Map.of("type", "hello", "busy", busy, "label", label, "tail", tail)));
        LISTENERS.add(q);
        try (OutputStream out = ex.getResponseBody()) {
            while (true) {
                String e = q.poll(15, TimeUnit.SECONDS);
                out.write((e != null ? e : ": keep-alive\n\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
        } catch (IOException | InterruptedException gone) {
            // browser closed the page
        } finally {
            LISTENERS.remove(q);
        }
    }

    static String event(Map<String, Object> data) {
        return "data: " + Json.write(data) + "\n\n";
    }

    static void broadcast(Map<String, Object> data) {
        String e = event(data);
        for (BlockingQueue<String> q : LISTENERS) q.offer(e);
    }

    // ---------------------------------------------------------------- proxy

    static void proxy(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            String path = ex.getRequestURI().toString();
            if (ex.getRequestMethod().equals("POST") && path.startsWith("/api/chat")) {
                chat(ex, body);
            } else {
                passThrough(ex, path, body);
            }
        } catch (Exception e) {
            System.out.println("relay error: " + e);
            byte[] b = Json.write(Map.of("error", "reasoning-relay: " + e.getMessage())).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(502, b.length);
            ex.getResponseBody().write(b);
        } finally {
            ex.close();
        }
    }

    static void passThrough(HttpExchange ex, String path, byte[] body) throws Exception {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(UPSTREAM + path))
                .method(ex.getRequestMethod(), body.length == 0
                        ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        String type = ex.getRequestHeaders().getFirst("Content-Type");
        if (type != null) req.header("Content-Type", type);
        HttpResponse<byte[]> resp = HTTP.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
        resp.headers().firstValue("Content-Type").ifPresent(t -> ex.getResponseHeaders().set("Content-Type", t));
        ex.sendResponseHeaders(resp.statusCode(), resp.body().length == 0 ? -1 : resp.body().length);
        ex.getResponseBody().write(resp.body());
    }

    @SuppressWarnings("unchecked")
    static void chat(HttpExchange ex, byte[] body) throws Exception {
        Map<String, Object> request = (Map<String, Object>) Json.read(new String(body, StandardCharsets.UTF_8));
        boolean clientStreams = !Boolean.FALSE.equals(request.get("stream"));
        request.put("stream", true);

        String model = String.valueOf(request.get("model"));
        label = labelFor(request);
        busy = true;
        synchronized (TAIL) {
            TAIL.setLength(0);
        }
        broadcast(Map.of("type", "start", "model", model, "label", label));
        long started = System.nanoTime();

        HttpResponse<java.util.stream.Stream<String>> resp = HTTP.send(
                HttpRequest.newBuilder(URI.create(UPSTREAM + "/api/chat"))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(Json.write(request)))
                        .build(),
                HttpResponse.BodyHandlers.ofLines());

        if (resp.statusCode() != 200) {
            byte[] err = String.join("\n", (Iterable<String>) resp.body()::iterator).getBytes(StandardCharsets.UTF_8);
            finish(List.of(), started, "error " + resp.statusCode());
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(resp.statusCode(), err.length);
            ex.getResponseBody().write(err);
            return;
        }

        OutputStream out = null;
        if (clientStreams) {
            ex.getResponseHeaders().set("Content-Type", "application/x-ndjson");
            ex.sendResponseHeaders(200, 0);
            out = ex.getResponseBody();
        }

        StringBuilder thinking = new StringBuilder();
        StringBuilder content = new StringBuilder();
        List<Object> toolCalls = new ArrayList<>();
        Map<String, Object> last = null;
        for (Iterator<String> it = resp.body().iterator(); it.hasNext(); ) {
            String line = it.next();
            if (line.isBlank()) continue;
            if (out != null) {
                out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                out.flush();
            }
            Map<String, Object> chunk = (Map<String, Object>) Json.read(line);
            last = chunk;
            Map<String, Object> msg = (Map<String, Object>) chunk.get("message");
            if (msg == null) continue;
            if (msg.get("thinking") instanceof String t && !t.isEmpty()) {
                thinking.append(t);
                synchronized (TAIL) {
                    TAIL.append(t);
                    if (TAIL.length() > 600) TAIL.delete(0, TAIL.length() - 600);
                }
                broadcast(Map.of("type", "think", "text", t));
            }
            if (msg.get("content") instanceof String c && !c.isEmpty()) {
                content.append(c);
                broadcast(Map.of("type", "say", "text", c));
            }
            if (msg.get("tool_calls") instanceof List<?> calls) {
                for (Object call : calls) {
                    toolCalls.add(call);
                    Map<String, Object> fn = (Map<String, Object>) ((Map<String, Object>) call).get("function");
                    Map<String, Object> a = fn.get("arguments") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
                    broadcast(Map.of("type", "tool", "name", String.valueOf(fn.get("name")),
                            "target", String.valueOf(a.getOrDefault("target", ""))));
                }
            }
        }
        finish(toolCalls, started, "done");
        if (out != null) return;

        // One answer, shaped like Ollama's own non-streamed reply: the final chunk's stats plus the whole message.
        Map<String, Object> answer = last != null ? new LinkedHashMap<>(last) : new LinkedHashMap<>();
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("role", "assistant");
        message.put("content", content.toString());
        if (!thinking.isEmpty()) message.put("thinking", thinking.toString());
        if (!toolCalls.isEmpty()) message.put("tool_calls", toolCalls);
        answer.put("message", message);
        answer.put("done", true);
        byte[] b = Json.write(answer).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
    }

    static void finish(List<Object> toolCalls, long started, String how) {
        busy = false;
        long secs = (System.nanoTime() - started) / 1_000_000_000L;
        broadcast(Map.of("type", "end", "how", how, "seconds", secs, "tools", toolCalls.size()));
        System.out.printf("chat %s: %s in %ds, %d tool call(s)%n", label, how, secs, toolCalls.size());
    }

    /** "demo.lab" for a case prompt, "run summary" for the summary prompt. */
    @SuppressWarnings("unchecked")
    static String labelFor(Map<String, Object> request) {
        if (request.get("messages") instanceof List<?> msgs) {
            for (Object o : msgs) {
                Map<String, Object> m = (Map<String, Object>) o;
                if ("user".equals(m.get("role")) && m.get("content") instanceof String c) {
                    Matcher mt = CASE.matcher(c);
                    if (mt.find()) return mt.group(1);
                }
            }
        }
        return "run summary";
    }

    // ---------------------------------------------------------------- minimal JSON (no dependencies)

    /** Objects → LinkedHashMap, arrays → ArrayList, numbers kept as written (Num) so they round-trip exactly. */
    static final class Json {
        record Num(String raw) {}

        final String s;
        int i;

        Json(String s) { this.s = s; }

        static Object read(String s) {
            Json j = new Json(s);
            Object v = j.value();
            j.ws();
            if (j.i != s.length()) throw new IllegalArgumentException("trailing data at " + j.i);
            return v;
        }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            char c = s.charAt(i);
            switch (c) {
                case '{' -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    i++;
                    ws();
                    if (s.charAt(i) == '}') { i++; return m; }
                    while (true) {
                        ws();
                        String k = string();
                        ws();
                        expect(':');
                        m.put(k, value());
                        ws();
                        if (s.charAt(i) == ',') { i++; continue; }
                        expect('}');
                        return m;
                    }
                }
                case '[' -> {
                    List<Object> l = new ArrayList<>();
                    i++;
                    ws();
                    if (s.charAt(i) == ']') { i++; return l; }
                    while (true) {
                        l.add(value());
                        ws();
                        if (s.charAt(i) == ',') { i++; continue; }
                        expect(']');
                        return l;
                    }
                }
                case '"' -> { return string(); }
                case 't' -> { i += 4; return true; }
                case 'f' -> { i += 5; return false; }
                case 'n' -> { i += 4; return null; }
                default -> {
                    int start = i;
                    while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
                    return new Num(s.substring(start, i));
                }
            }
        }

        void expect(char c) {
            if (s.charAt(i) != c) throw new IllegalArgumentException("expected " + c + " at " + i);
            i++;
        }

        String string() {
            expect('"');
            StringBuilder b = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return b.toString();
                if (c != '\\') { b.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> b.append('\n');
                    case 't' -> b.append('\t');
                    case 'r' -> b.append('\r');
                    case 'b' -> b.append('\b');
                    case 'f' -> b.append('\f');
                    case 'u' -> { b.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    default -> b.append(e);   // " \ /
                }
            }
        }

        static String write(Object v) {
            StringBuilder b = new StringBuilder();
            write(v, b);
            return b.toString();
        }

        static void write(Object v, StringBuilder b) {
            if (v == null) b.append("null");
            else if (v instanceof Num n) b.append(n.raw());
            else if (v instanceof Number || v instanceof Boolean) b.append(v);
            else if (v instanceof Map<?, ?> m) {
                b.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) b.append(',');
                    first = false;
                    quote(String.valueOf(e.getKey()), b);
                    b.append(':');
                    write(e.getValue(), b);
                }
                b.append('}');
            } else if (v instanceof List<?> l) {
                b.append('[');
                for (int k = 0; k < l.size(); k++) {
                    if (k > 0) b.append(',');
                    write(l.get(k), b);
                }
                b.append(']');
            } else quote(v.toString(), b);
        }

        static void quote(String s, StringBuilder b) {
            b.append('"');
            for (char c : s.toCharArray()) {
                switch (c) {
                    case '"' -> b.append("\\\"");
                    case '\\' -> b.append("\\\\");
                    case '\n' -> b.append("\\n");
                    case '\r' -> b.append("\\r");
                    case '\t' -> b.append("\\t");
                    default -> {
                        if (c < 0x20) b.append(String.format("\\u%04x", (int) c));
                        else b.append(c);
                    }
                }
            }
            b.append('"');
        }
    }
}
