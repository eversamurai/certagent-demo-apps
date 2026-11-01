import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.concurrent.Executors;

/**
 * Storefront — product catalogue. Plain HTTP on :8080 inside the docker network;
 * nginx terminates TLS for demo.lab and proxies here.
 */
public class Storefront {

    static final String SERVICE = "storefront";
    static final String DESCRIPTION = "Storefront — product catalogue";

    static final String PRODUCTS = """
            [{"sku":"TEA-001","name":"Sencha green tea, 100g","price":8.50},
             {"sku":"MUG-014","name":"Stoneware mug, 350ml","price":14.00},
             {"sku":"KTL-002","name":"Cast-iron kettle, 0.8l","price":49.90}]""";

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8080), 0);
        server.createContext("/", Storefront::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println(SERVICE + " listening on :8080");
    }

    static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        int status = 200;
        String type = "application/json";
        String body;
        switch (path) {
            case "/" -> {
                type = "text/html; charset=utf-8";
                body = page(host(ex));
            }
            case "/api/health" -> body = "{\"service\":\"" + SERVICE + "\",\"status\":\"UP\",\"time\":\"" + Instant.now() + "\"}";
            case "/api/products" -> body = PRODUCTS;
            default -> {
                status = 404;
                body = "{\"error\":\"not found\"}";
            }
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
        System.out.printf("%s %s %s %d host=%s%n", Instant.now(), ex.getRequestMethod(), path, status, host(ex));
    }

    static String host(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("X-Forwarded-Host");
        return h != null ? h : String.valueOf(ex.getRequestHeaders().getFirst("Host"));
    }

    static String page(String host) {
        return """
                <!doctype html>
                <html lang="en"><head><meta charset="utf-8"><title>%s</title>
                <style>
                  body { margin: 0; font: 18px/1.5 system-ui, "Segoe UI", sans-serif; background: #0d1117; color: #e6edf3; }
                  main { max-width: 640px; margin: 15vh auto; padding: 0 24px; }
                  h1 { font-size: 34px; margin: 0 0 4px; }
                  p { color: #8b98a8; margin: 4px 0; }
                  code { color: #58a6ff; }
                </style></head>
                <body><main>
                  <h1>%s</h1>
                  <p>Served as <code>%s</code></p>
                  <p>Time <code>%s</code></p>
                  <p>API <code>/api/products</code> · <code>/api/health</code></p>
                </main></body></html>
                """.formatted(DESCRIPTION, DESCRIPTION, host, Instant.now());
    }
}
