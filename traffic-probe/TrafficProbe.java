import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Traffic probe: behaves like real clients of the two services. Every 2 seconds it calls each business
 * endpoint over HTTPS, trusting ONLY the lab root CA, with hostname verification on. It records whether the
 * call worked, the TLS error if not, and which certificate was served. Status page on :8090.
 */
public class TrafficProbe {

    static final String ROOT_CA = System.getenv().getOrDefault("ROOT_CA", "/certs/root_ca.crt");
    static final Duration TIMEOUT = Duration.ofMillis(1500);
    static final Duration WARN_EXPIRY = Duration.ofHours(6);
    static final int MIN_RSA_BITS = 2048;
    static final int KEEP = 150;           // 5 minutes at one check every 2s

    record Target(String name, String host, int port, String path, String env) {
        URI uri() { return URI.create("https://" + host + ":" + port + path); }
    }

    record CertInfo(String issuerCn, String key, int keyBits, Instant notAfter, String fp) {}

    /** One check. state is OK, WARN or FAIL. */
    record Check(Instant time, String state, int status, long latencyMs, String error, String plain,
                 List<String> warnings, CertInfo cert) {}

    static final List<Target> TARGETS = List.of(
            new Target("demo.lab", "demo.lab", 443, "/api/products", "lab"),
            new Target("shop.prod", "shop.prod", 8444, "/api/orders", "prod"),
            new Target("accounts.lab", "accounts.lab", 8445, "/api/users", "lab"));

    static final List<Deque<Check>> HISTORY = new ArrayList<>();

    public static void main(String[] args) throws IOException {
        for (int i = 0; i < TARGETS.size(); i++) HISTORY.add(new ArrayDeque<>());

        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(TrafficProbe::checkAll, 1, 2, TimeUnit.SECONDS);

        HttpServer server = HttpServer.create(new InetSocketAddress(8090), 0);
        server.createContext("/", TrafficProbe::serve);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        System.out.println("traffic-probe on :8090, trusting only " + ROOT_CA);
    }

    // ---------------------------------------------------------------- checks

    static void checkAll() {
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < TARGETS.size(); i++) {
                int idx = i;
                pool.submit(() -> record(idx, check(TARGETS.get(idx))));
            }
        }
    }

    static void record(int idx, Check c) {
        Deque<Check> h = HISTORY.get(idx);
        synchronized (h) {
            h.addLast(c);
            while (h.size() > KEEP) h.removeFirst();
        }
        Target t = TARGETS.get(idx);
        System.out.printf("%s %-9s %-4s %3d %4dms %s%s%n", c.time(), t.name(), c.state(), c.status(), c.latencyMs(),
                c.cert() == null ? "-" : c.cert().fp(), c.error() == null ? "" : "  " + c.error());
    }

    static Check check(Target t) {
        Instant start = Instant.now();
        HttpClient client = null;
        try {
            // A fresh client and SSLContext every time: no pooled connection or resumed TLS session can keep
            // showing the certificate nginx served before a reload, and a re-issued root_ca.crt is picked up.
            client = HttpClient.newBuilder()
                    .sslContext(strictContext())
                    .version(HttpClient.Version.HTTP_1_1)
                    .connectTimeout(TIMEOUT)
                    .build();
            // Hard deadline on top of the request timeout, so a stuck server can never freeze the probe.
            HttpResponse<String> resp;
            try {
                resp = client.sendAsync(HttpRequest.newBuilder(t.uri()).timeout(TIMEOUT).GET().build(),
                                HttpResponse.BodyHandlers.ofString())
                        .get(2 * TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (ExecutionException e) {
                throw e.getCause();
            } catch (TimeoutException e) {
                throw new IOException("no answer: timed out after " + 2 * TIMEOUT.toMillis() + " ms");
            }
            long ms = Duration.between(start, Instant.now()).toMillis();
            CertInfo cert = resp.sslSession()
                    .map(s -> { try { return info((X509Certificate) s.getPeerCertificates()[0]); }
                                catch (Exception e) { return null; } })
                    .orElse(null);
            if (resp.statusCode() >= 500) {
                return new Check(start, "FAIL", resp.statusCode(), ms, "HTTP " + resp.statusCode(),
                        "service behind nginx is not answering", List.of(), cert);
            }
            List<String> warnings = warnings(cert);
            return new Check(start, warnings.isEmpty() ? "OK" : "WARN", resp.statusCode(), ms, null, null, warnings, cert);
        } catch (Throwable e) {
            long ms = Duration.between(start, Instant.now()).toMillis();
            String err = shortError(e);
            return new Check(start, "FAIL", 0, ms, err, plain(err), List.of(), captureServedCert(t));
        } finally {
            if (client != null) client.shutdownNow();
        }
    }

    /** Trusts the lab root and nothing else. Read from disk on every check. */
    static SSLContext strictContext() throws Exception {
        X509Certificate root;
        try (InputStream in = new FileInputStream(ROOT_CA)) {
            root = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        } catch (IOException e) {
            throw new IOException("lab root CA not readable at " + ROOT_CA);
        }
        KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
        ks.load(null, null);
        ks.setCertificateEntry("lab-root", root);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, tmf.getTrustManagers(), null);
        return ctx;
    }

    /** After a failed handshake: connect again accepting anything, only to see which cert is served. */
    static CertInfo captureServedCert(Target t) {
        TrustManager acceptAll = new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{acceptAll}, null);
            try (SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket()) {
                s.connect(new InetSocketAddress(t.host(), t.port()), (int) TIMEOUT.toMillis());
                s.setSoTimeout((int) TIMEOUT.toMillis());
                SSLParameters p = s.getSSLParameters();
                p.setServerNames(List.of(new SNIHostName(t.host())));
                s.setSSLParameters(p);
                s.startHandshake();
                Certificate[] chain = s.getSession().getPeerCertificates();
                return info((X509Certificate) chain[0]);
            }
        } catch (Exception e) {
            return null;   // nothing answering at all
        }
    }

    static CertInfo info(X509Certificate c) throws Exception {
        String key;
        int bits;
        if (c.getPublicKey() instanceof RSAPublicKey rsa) {
            key = "RSA";
            bits = rsa.getModulus().bitLength();
        } else if (c.getPublicKey() instanceof ECPublicKey ec) {
            key = "EC";
            bits = ec.getParams().getOrder().bitLength();
        } else {
            key = c.getPublicKey().getAlgorithm();
            bits = 0;
        }
        // Same format as CertAgent: lowercase hex SHA-256 over the DER encoding (shown as the first 12 chars).
        String fp = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
        return new CertInfo(cn(c.getIssuerX500Principal().getName()), key, bits, c.getNotAfter().toInstant(),
                fp.substring(0, 12));
    }

    static String cn(String dn) {
        try {
            for (Rdn r : new LdapName(dn).getRdns()) {
                if (r.getType().equalsIgnoreCase("CN")) return r.getValue().toString();
            }
        } catch (Exception ignored) {
        }
        return dn;
    }

    /** The request worked, but would a careful client be worried? Java accepts RSA 1024, so we check. */
    static List<String> warnings(CertInfo c) {
        List<String> w = new ArrayList<>();
        if (c == null) return w;
        if (c.key().equals("RSA") && c.keyBits() < MIN_RSA_BITS) {
            w.add("weak key: RSA " + c.keyBits() + " bits");
        }
        Duration left = Duration.between(Instant.now(), c.notAfter());
        if (left.compareTo(WARN_EXPIRY) < 0) {
            w.add("expires in " + left.toHours() + "h " + left.toMinutesPart() + "m");
        }
        return w;
    }

    /** The first line of the TLS error, without the Java exception class tail. */
    static String shortError(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && !m.isBlank()) {
                return m.replaceAll("(sun|java|javax)\\.[\\w.$]+(Exception|Error):\\s*", "")
                        .replaceFirst("^\\(\\w+\\)\\s*", "")   // TLS alert name, e.g. "(certificate_unknown) "
                        .strip();
            }
        }
        return e.getClass().getSimpleName();
    }

    static String plain(String err) {
        String e = err.toLowerCase();
        if (e.contains("pkix path building failed")) return "certificate not trusted — unknown issuer";
        if (e.contains("no subject alternative")) return "certificate is for a different hostname";
        if (e.contains("validity check failed") || e.contains("expired")) return "certificate expired";
        if (e.contains("connection refused") || e.contains("connectexception")) return "nothing listening — connection refused";
        if (e.contains("timed out")) return "no answer — timed out";
        if (e.contains("lab root ca not readable")) return "probe has no lab root CA yet — run demo/reset.sh";
        return "TLS handshake failed";
    }

    // ---------------------------------------------------------------- web

    static void serve(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            try (InputStream in = TrafficProbe.class.getResourceAsStream("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", in.readAllBytes());
            }
        } else if (path.equals("/api/status")) {
            send(ex, 200, "application/json", statusJson().getBytes(StandardCharsets.UTF_8));
        } else if (path.startsWith("/api/state/")) {
            // Plain text for the demo scripts: OK, WARN, FAIL or NONE.
            String name = path.substring("/api/state/".length());
            int idx = TARGETS.stream().map(Target::name).toList().indexOf(name);
            if (idx < 0) {
                send(ex, 404, "text/plain", "unknown target\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            Check last = last(idx);
            send(ex, 200, "text/plain", ((last == null ? "NONE" : last.state()) + "\n").getBytes(StandardCharsets.UTF_8));
        } else {
            send(ex, 404, "text/plain", "not found\n".getBytes(StandardCharsets.UTF_8));
        }
    }

    static void send(HttpExchange ex, int status, String type, byte[] body) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, body.length);
        try (var out = ex.getResponseBody()) {
            out.write(body);
        }
    }

    static Check last(int idx) {
        Deque<Check> h = HISTORY.get(idx);
        synchronized (h) {
            return h.peekLast();
        }
    }

    static String statusJson() {
        StringBuilder sb = new StringBuilder("{\"now\":").append(q(Instant.now().toString())).append(",\"targets\":[");
        Instant fiveMinAgo = Instant.now().minus(Duration.ofMinutes(5));
        for (int i = 0; i < TARGETS.size(); i++) {
            Target t = TARGETS.get(i);
            List<Check> checks;
            Deque<Check> h = HISTORY.get(i);
            synchronized (h) {
                checks = new ArrayList<>(h);
            }
            long ok = checks.stream().filter(c -> c.time().isAfter(fiveMinAgo) && !c.state().equals("FAIL")).count();
            long failed = checks.stream().filter(c -> c.time().isAfter(fiveMinAgo) && c.state().equals("FAIL")).count();
            StringBuilder strip = new StringBuilder();
            for (Check c : checks.subList(Math.max(0, checks.size() - 60), checks.size())) strip.append(c.state().charAt(0));
            Check c = checks.isEmpty() ? null : checks.getLast();

            if (i > 0) sb.append(',');
            sb.append("{\"name\":").append(q(t.name()))
              .append(",\"endpoint\":").append(q(t.host() + ":" + t.port() + t.path()))
              .append(",\"env\":").append(q(t.env()))
              .append(",\"history\":").append(q(strip.toString()))
              .append(",\"ok5m\":").append(ok)
              .append(",\"failed5m\":").append(failed);
            if (c != null) {
                sb.append(",\"state\":").append(q(c.state()))
                  .append(",\"time\":").append(q(c.time().toString()))
                  .append(",\"status\":").append(c.status())
                  .append(",\"latencyMs\":").append(c.latencyMs())
                  .append(",\"error\":").append(q(c.error()))
                  .append(",\"plain\":").append(q(c.plain()))
                  .append(",\"warnings\":[").append(String.join(",", c.warnings().stream().map(TrafficProbe::q).toList())).append(']');
                CertInfo ci = c.cert();
                if (ci != null) {
                    sb.append(",\"cert\":{\"issuer\":").append(q(ci.issuerCn()))
                      .append(",\"key\":").append(q(ci.key() + " " + ci.keyBits()))
                      .append(",\"notAfter\":").append(q(ci.notAfter().toString()))
                      .append(",\"fp\":").append(q(ci.fp())).append('}');
                }
            }
            sb.append('}');
        }
        return sb.append("]}").toString();
    }

    static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : s.toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }
}
