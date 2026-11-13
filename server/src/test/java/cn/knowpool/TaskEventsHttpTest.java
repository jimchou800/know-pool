package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class TaskEventsHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final List<java.util.Map<String, Object>> OBSERVATIONS = new ArrayList<>();
    private static final List<java.util.Map<String, String>> ROUTES = new ArrayList<>();
    private static final java.util.Set<String> PASSED = new java.util.HashSet<>();

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher completed = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            PASSED.add(context.getRequiredTestMethod().getName());
        }
    };

    @Test
    @DisplayName("真实任务终态事件可恢复且订阅不改变序号")
    void terminalSnapshotCanBeResumed() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/", exchange -> {
            calls.incrementAndGet();
            byte[] body = "{\"status\":\"ok\",\"result\":{}}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        stub.start();
        Path directory =
                Files.createTempDirectory(Path.of("target"), "task-events-").toAbsolutePath();
        try (var app = SpringApplication.run(
                KnowledgePoolApplication.class,
                "--server.port=0",
                "--know-pool.data-directory=" + directory,
                "--logging.file.name=target/test-task-events.log")) {
            captureRoutes(app);
            String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
            var session = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/session")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            String cookie =
                    session.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String token = JSON.readTree(session.body()).path("csrfToken").asText();
            String configuration = "{\"endpoint\":\"http://127.0.0.1:"
                    + stub.getAddress().getPort() + "\",\"credential\":{\"action\":\"clear\"}}";
            assertThat(send(base, "/settings/vector-store", "PUT", configuration, cookie, token)
                            .statusCode())
                    .isEqualTo(200);
            var accepted = send(base, "/settings/vector-store/test", "POST", null, cookie, token);
            assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
            String id = JSON.readTree(accepted.body()).at("/task/id").asText();
            for (int attempt = 0; attempt < 100; attempt++) {
                JsonNode task = JSON.readTree(
                        send(base, "/tasks/" + id, "GET", null, cookie, null).body());
                if (task.path("state").asText().equals("succeeded")) break;
                Thread.sleep(20);
            }
            int settledCalls = calls.get();
            var event = events(base, id, cookie, null);
            assertThat(event.statusCode()).as(event.body()).isEqualTo(200);
            assertThat(event.headers().firstValue("Content-Type").orElseThrow()).startsWith("text/event-stream");
            assertThat(event.body())
                    .contains("event:terminal", "id:" + id + ":", "data:")
                    .doesNotContain("endpoint", "credential");
            String eventId = event.body()
                    .lines()
                    .filter(line -> line.startsWith("id:"))
                    .findFirst()
                    .orElseThrow()
                    .substring(3)
                    .trim();
            JsonNode beforeReconnect = JSON.readTree(send(base, "/tasks/" + id, "GET", null, cookie, null).body());
            assertThat(beforeReconnect.path("state").asText()).isEqualTo("succeeded");
            assertThat(events(base, id, cookie, eventId).body()).isEqualTo(event.body());
            assertThat(events(base, id, cookie, id + ":9007199254740991").body())
                    .isEqualTo(event.body());
            assertThat(calls.get()).as("???????????").isEqualTo(settledCalls);
            assertThat(JSON.readTree(send(base, "/tasks/" + id, "GET", null, cookie, null).body())).isEqualTo(beforeReconnect);
            assertThat(events(base, id, cookie + "invalid", null).statusCode()).isEqualTo(401);
            for (String cursor : new String[] {
                id + ":0", id + ":01", id + ":-1", id + ":9007199254740992", UUID.randomUUID() + ":1", id + ":1,2"
            }) {
                var rejected = events(base, id, cookie, cursor);
                assertThat(rejected.statusCode()).as(rejected.body()).isEqualTo(400);
            }
            assertThat(events(base, UUID.randomUUID().toString(), cookie, null).statusCode())
                    .isEqualTo(404);
            var anonymous = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/tasks/" + id + "/events"))
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            observed(anonymous, null);
            assertThat(anonymous.statusCode()).isEqualTo(401);
            var foreign = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/tasks/" + id + "/events"))
                            .header("Cookie", cookie)
                            .header("Origin", "http://untrusted.invalid")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            observed(foreign, null);
            assertThat(foreign.statusCode()).isEqualTo(403);
            var query = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/tasks/" + id + "/events?token=invalid"))
                            .header("Cookie", cookie)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            observed(query, null);
            assertThat(query.statusCode()).isEqualTo(400);
            var duplicate = http.send(HttpRequest.newBuilder(URI.create(base + "/tasks/" + id + "/events"))
                    .header("Cookie", cookie).header("Last-Event-ID", eventId).header("Last-Event-ID", eventId)
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            observed(duplicate, null);
            assertThat(duplicate.statusCode()).as(duplicate.body()).isEqualTo(400);
            assertThat(events(base, "invalid-id", cookie, null).statusCode()).isEqualTo(400);
        } finally {
            stub.stop(0);
        }
    }

    @Test
    @DisplayName("订阅容量有界，心跳不推进序号，断开释放后可继续观察真实终态")
    void boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var stubExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        stub.setExecutor(stubExecutor);
        stub.createContext("/", exchange -> {
            try {
                release.await(20, TimeUnit.SECONDS);
                byte[] body = "{\"status\":\"ok\",\"result\":{}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        stub.start();
        List<InputStream> bodies = new ArrayList<>();
        Path directory = Files.createTempDirectory(Path.of("target"), "task-events-capacity-").toAbsolutePath();
        try (var app = SpringApplication.run(KnowledgePoolApplication.class, "--server.port=0",
                "--know-pool.data-directory=" + directory, "--logging.file.name=target/test-task-events-capacity.log")) {
            captureRoutes(app);
            String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
            var session = http.send(HttpRequest.newBuilder(URI.create(base + "/session")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String cookie = session.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            String token = JSON.readTree(session.body()).path("csrfToken").asText();
            assertThat(send(base, "/settings/vector-store", "PUT", "{\"endpoint\":\"http://127.0.0.1:"
                    + stub.getAddress().getPort() + "\",\"credential\":{\"action\":\"clear\"}}", cookie, token).statusCode()).isEqualTo(200);
            String id = null;
            for (int index = 0; index < 8; index++) {
                var accepted = send(base, "/settings/vector-store/test", "POST", null, cookie, token);
                assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
                id = JSON.readTree(accepted.body()).at("/task/id").asText();
            }
            String path = base + "/tasks/" + id + "/events";
            BufferedReader reader = null;
            JsonNode initial = null;
            String initialFrame = null;
            HttpResponse<InputStream> initialResponse = null;
            for (int index = 0; index < 64; index++) {
                var response = open(path, cookie);
                assertThat(response.statusCode()).isEqualTo(200);
                bodies.add(response.body());
                var current = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                String frame = frame(current);
                assertThat(frame).contains("event:phase");
                if (index == 0) {
                    reader = current;
                    initial = eventData(frame);
                    initialFrame = frame;
                    initialResponse = response;
                    assertThat(initial.path("state").asText()).isEqualTo("queued");
                }
            }
            var full = events(base, id, cookie, null);
            assertThat(full.statusCode()).as(full.body()).isEqualTo(429);
            assertThat(JSON.readTree(full.body()).path("code").asText()).isEqualTo("QUEUE_FULL");
            BufferedReader observedReader = reader;
            String heartbeat = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try { return frame(observedReader); } catch (Exception failure) { throw new RuntimeException(failure); }
            }).get(8, TimeUnit.SECONDS);
            assertThat(heartbeat).startsWith(":").doesNotContain("data:", "id:", "event:");
            bodies.get(63).close();
            boolean reopened = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (System.nanoTime() < deadline) {
                var response = open(path, cookie);
                if (response.statusCode() == 200) {
                    bodies.add(response.body());
                    var resumed = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8));
                    assertThat(eventData(frame(resumed))).isEqualTo(initial);
                    reopened = true;
                    break;
                }
                response.body().close();
                Thread.sleep(100);
            }
            assertThat(reopened).as("断开的订阅应释放容量").isTrue();
            release.countDown();
            String terminal = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                try {
                    for (int index = 0; index < 20; index++) {
                        String next = frame(observedReader);
                        if (next.contains("event:terminal")) return next;
                    }
                    throw new AssertionError("未收到终态事件");
                } catch (Exception failure) { throw new RuntimeException(failure); }
            }).get(10, TimeUnit.SECONDS);
            observeStream(initialResponse, initialFrame, heartbeat, terminal);
            JsonNode finished = eventData(terminal);
            assertThat(finished.path("state").asText()).isEqualTo("succeeded");
            assertThat(finished.path("sequence").asLong()).isGreaterThan(initial.path("sequence").asLong());
            assertThat(reader.readLine()).as("终态发送后应关闭流").isNull();
            assertThat(JSON.readTree(send(base, "/tasks/" + id, "GET", null, cookie, null).body()).path("state").asText()).isEqualTo("succeeded");
        } finally {
            release.countDown();
            for (InputStream body : bodies) body.close();
            stub.stop(0);
            stubExecutor.shutdownNow();
        }
    }

    private HttpResponse<InputStream> open(String path, String cookie) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(path)).timeout(Duration.ofSeconds(5))
                .header("Cookie", cookie).header("Accept", "text/event-stream").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private static String frame(BufferedReader reader) throws Exception {
        StringBuilder result = new StringBuilder();
        for (String line; (line = reader.readLine()) != null;) {
            if (line.isEmpty() && !result.isEmpty()) return result.toString();
            if (!line.isEmpty()) result.append(line).append('\n');
        }
        throw new AssertionError("事件流提前关闭");
    }

    private static JsonNode eventData(String frame) {
        return JSON.readTree(frame.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5));
    }

    private static void captureRoutes(org.springframework.context.ConfigurableApplicationContext app) {
        if (!ROUTES.isEmpty()) return;
        app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                .getHandlerMethods().keySet().forEach(mapping -> {
                    for (String path : mapping.getPatternValues())
                        for (var method : mapping.getMethodsCondition().getMethods())
                            ROUTES.add(java.util.Map.of("method", method.name(), "path", path));
                });
    }

    private static String behavior() {
        return java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .filter(frame -> frame.getClassName().equals(TaskEventsHttpTest.class.getName()))
                .map(StackTraceElement::getMethodName)
                .filter(name -> name.equals("terminalSnapshotCanBeResumed") || name.equals("boundedSubscriptionsReleaseOnDisconnectAndEmitHeartbeat"))
                .findFirst().orElse("");
    }

    private static void observed(HttpResponse<String> response, String body) {
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("path", response.request().uri().getRawPath() + (response.request().uri().getRawQuery() == null ? "" : "?" + response.request().uri().getRawQuery()));
        item.put("method", response.request().method());
        item.put("status", response.statusCode());
        String media = response.headers().firstValue("Content-Type").orElse("");
        item.put("contentType", media);
        item.put("response", media.startsWith("text/event-stream") ? response.body() : JSON.readTree(response.body()));
        item.put("body", body);
        item.put("parameters", response.request().headers().firstValue("Last-Event-ID").map(value -> java.util.Map.of("Last-Event-ID", value)).orElse(java.util.Map.of()));
        item.put("behavior", behavior());
        if (behavior().equals("terminalSnapshotCanBeResumed")) item.put("behaviorChecks", List.of("eventReconnect", "eventCursor", "eventReconnectProtocol", "eventCookieSecurity"));
        OBSERVATIONS.add(item);
    }

    private static void observeStream(HttpResponse<InputStream> response, String initialFrame, String heartbeat, String terminal) {
        var item = new java.util.LinkedHashMap<String, Object>();
        item.put("path", response.request().uri().getRawPath());
        item.put("method", "GET");
        item.put("status", response.statusCode());
        item.put("contentType", response.headers().firstValue("Content-Type").orElse(""));
        item.put("response", initialFrame + "\n" + heartbeat + "\n" + terminal + "\n");
        item.put("body", null);
        item.put("parameters", java.util.Map.of());
        item.put("behavior", behavior());
        item.put("behaviorChecks", List.of("eventReconnectProtocol", "eventCapacity"));
        OBSERVATIONS.add(item);
    }

    @org.junit.jupiter.api.AfterAll
    static void evidence() throws Exception {
        if (OBSERVATIONS.isEmpty()) return;
        OBSERVATIONS.getFirst().put("routes", ROUTES);
        for (var item : OBSERVATIONS) if (!PASSED.contains((String) item.get("behavior"))) {
            item.put("behavior", "");
            item.remove("behaviorChecks");
        }
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", "..")).toAbsolutePath().normalize();
        Process process = new ProcessBuilder("python", "server/src/test/resources/contract/settings-evidence.py", root.toString(), "indexing-events")
                .directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var output = process.getOutputStream()) { output.write(JSON.writeValueAsBytes(OBSERVATIONS)); }
        String result = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(result).isZero();
    }

    private HttpResponse<String> events(String base, String id, String cookie, String cursor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + "/tasks/" + id + "/events"))
                .timeout(Duration.ofSeconds(5))
                .header("Cookie", cookie)
                .header("Accept", "text/event-stream");
        if (cursor != null) request.header("Last-Event-ID", cursor);
        var response = http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
        observed(response, null);
        return response;
    }

    private HttpResponse<String> send(String base, String path, String method, String body, String cookie, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .header("Cookie", cookie);
        if (token != null) request.header("X-CSRF-Token", token);
        if (method.equals("POST"))
            request.header("Idempotency-Key", UUID.randomUUID().toString());
        if (body != null) request.header("Content-Type", "application/json");
        var response = http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        observed(response, body);
        return response;
    }
}
