package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import cn.knowpool.infrastructure.ApplicationData;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MigrationHttpTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final List<Map<String,Object>> OBSERVATIONS=new ArrayList<>();
    private static final List<Map<String,String>> ROUTES=new ArrayList<>();
    private static boolean passed;
    private boolean recording;
    private final HttpClient http=HttpClient.newHttpClient();
    @TempDir Path temporary;

    @Test @DisplayName("迁移暂停、失败恢复、清旧失败保新及固定入口多次切换")
    void migrationPathsSwitchRestartAndResidueStaySafe() throws Exception {
        recording=true;
        Path source=temporary.resolve("source").toAbsolutePath(),target=temporary.resolve("target").toAbsolutePath(),third=temporary.resolve("third").toAbsolutePath();
        Files.createDirectories(source.resolve("files"));Files.writeString(source.resolve("files/fixture.txt"),"固定迁移资料。");
        CountDownLatch checked=new CountDownLatch(1),release=new CountDownLatch(1);List<String> vectorPaths=new CopyOnWriteArrayList<>();
        HttpServer vector=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        vector.createContext("/",exchange -> {
            vectorPaths.add(exchange.getRequestURI().getPath());checked.countDown();
            try { if (!release.await(10,TimeUnit.SECONDS)) throw new java.io.IOException("固定向量服务等待超时。"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt();throw new java.io.IOException(failure); }
            byte[] bytes="{\"status\":\"ok\",\"result\":{\"collections\":[]}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });vector.start();
        ConfigurableApplicationContext app=start(source);
        try {
            Client client=client(app);ApplicationData data=app.getBean(ApplicationData.class);
            data.write(jdbc -> { jdbc.sql("UPDATE vector_config SET endpoint=? WHERE id=1").param("http://127.0.0.1:"+vector.getAddress().getPort()).update();return null; });
            assertThat(call(client,"POST","/maintenance/migration/preflight",paths(source,source.resolve("nested")),null).body().path("allowed").asBoolean()).isFalse();
            assertThat(call(client,"POST","/maintenance/migration/preflight",paths(third,target),null).status()).isEqualTo(409);
            String token=call(client,"POST","/maintenance/migration/preflight",paths(source,target),null).body().path("token").asText();
            String failed;
            try (var lease=data.gate().enter()) {
                assertThat(lease).isNotNull();
                failed=submit(client,token);
                assertThat(call(client,"GET","/system/status",null,null).body().path("writeState").asText()).isEqualTo("paused");
                assertThat(call(client,"POST","/datasets","{\"name\":\"维护期拒绝\",\"description\":\"\"}",null).status()).isEqualTo(503);
                assertThat(call(client,"GET","/tasks?kind=migration&limit=20",null,null).status()).isEqualTo(200);
                Files.createDirectories(target);Files.writeString(target.resolve("foreign.txt"),"未登记内容必须保留。");
            }
            JsonNode failure=await(client,failed);assertThat(failure.path("configurationSwitched").asBoolean()).isFalse();
            assertThat(failure.path("failureStage").asText()).isEqualTo("path_check");assertThat(Files.readString(source.resolve("files/fixture.txt"))).isEqualTo("固定迁移资料。");
            assertThat(Files.readString(target.resolve("foreign.txt"))).isEqualTo("未登记内容必须保留。");Files.delete(target.resolve("foreign.txt"));
            String resumed=call(client,"POST","/maintenance/tasks/"+failed+"/retry","{\"action\":\"resume\"}",null).body().at("/task/id").asText();
            assertThat(checked.await(10,TimeUnit.SECONDS)).isTrue();
            Files.writeString(source.resolve("unregistered.txt"),"旧目录新增内容不能删除。");release.countDown();
            JsonNode residue=await(client,resumed);assertThat(residue.path("configurationSwitched").asBoolean()).isTrue();assertThat(residue.path("oldDirectoryResidue").asBoolean()).isTrue();
            assertThat(residue.path("failureStage").asText()).isEqualTo("old_directory_cleanup");assertThat(residue.at("/task/retryOfTaskId").asText()).isEqualTo(failed);
            assertThat(Files.readString(target.resolve("files/fixture.txt"))).isEqualTo("固定迁移资料。");assertThat(Files.exists(source.resolve("unregistered.txt"))).isTrue();
            assertThat(call(client,"GET","/system/status",null,null).body().path("writeState").asText()).isEqualTo("ready");
            Files.delete(source.resolve("unregistered.txt"));String key=UUID.randomUUID().toString();
            String cleaned=call(client,"POST","/maintenance/tasks/"+resumed+"/retry","{\"action\":\"cleanup_residue\"}",key).body().at("/task/id").asText();
            assertThat(await(client,cleaned).at("/task/state").asText()).isEqualTo("succeeded");
            assertThat(call(client,"POST","/maintenance/tasks/"+resumed+"/retry","{\"action\":\"cleanup_residue\"}",key).body().at("/task/id").asText()).isEqualTo(cleaned);
            app.close();app=start(source);client=client(app);
            assertThat(app.getBean(ApplicationData.class).directory()).isEqualTo(target);
            token=call(client,"POST","/maintenance/migration/preflight",paths(target,third),null).body().path("token").asText();
            assertThat(await(client,submit(client,token)).at("/task/state").asText()).isEqualTo("succeeded");
            app.close();app=start(source);assertThat(app.getBean(ApplicationData.class).directory()).isEqualTo(third);
            assertThat(Files.readString(third.resolve("files/fixture.txt"))).isEqualTo("固定迁移资料。");
            assertThat(vectorPaths).isNotEmpty().allMatch(path -> path.equals("/collections"));passed=true;
        } finally { recording=false;release.countDown();app.close();vector.stop(0); }
    }

    @Test @DisplayName("支持目录链接时预检拒绝祖先链接")
    void rejectsDirectoryLinksWhenAvailable() throws Exception {
        Path source=temporary.resolve("link-source"),outside=temporary.resolve("outside"),link=temporary.resolve("link");Files.createDirectories(outside);
        try { Files.createSymbolicLink(link,outside); }
        catch (java.io.IOException | UnsupportedOperationException | SecurityException unsupported) { Assumptions.assumeTrue(false,"本机没有创建目录链接权限，统一验证需另行核验目录联接。"); }
        try (var app=start(source)) {
            Client client=client(app);assertThat(call(client,"POST","/maintenance/migration/preflight",paths(source,link.resolve("data")),null).body().path("allowed").asBoolean()).isFalse();
        }
    }
    private String submit(Client client,String token) throws Exception { Reply response=call(client,"POST","/maintenance/migration","{\"preflightToken\":\""+token+"\",\"confirmed\":true}",null);assertThat(response.status()).isEqualTo(202);return response.body().at("/task/id").asText(); }
    private JsonNode await(Client client,String id) throws Exception {
        for (int attempt=0;attempt<200;attempt++) { JsonNode result=call(client,"GET","/maintenance/tasks/"+id,null,null).body();if (Set.of("succeeded","failed","interrupted").contains(result.at("/task/state").asText())) return result;Thread.sleep(25); }
        throw new AssertionError("迁移任务未在固定时间内结束。");
    }
    private String paths(Path source,Path target) { return JSON.writeValueAsString(Map.of("sourcePath",source.toAbsolutePath().toString(),"targetPath",target.toAbsolutePath().toString())); }
    private ConfigurableApplicationContext start(Path source) {
        var app=new SpringApplication(KnowledgePoolApplication.class).run("--server.port=0","--know-pool.data-directory="+source.toAbsolutePath());
        if (ROUTES.isEmpty()) app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class).getHandlerMethods().forEach((mapping,handler) -> { for (String path:mapping.getPatternValues()) for (var method:mapping.getMethodsCondition().getMethods()) ROUTES.add(Map.of("method",method.name(),"path",path)); });return app;
    }
    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base="http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port")+"/api/v1";
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        return new Client(base,response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0],JSON.readTree(response.body()).path("csrfToken").asText());
    }
    private Reply call(Client client,String method,String path,String body,String rawKey) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(client.base()+path)).timeout(Duration.ofSeconds(15)).header("Cookie",client.cookie());
        Map<String,String> parameters=new LinkedHashMap<>();
        if (!method.equals("GET")) { String key=rawKey==null?UUID.randomUUID().toString():rawKey;request.header("X-CSRF-Token",client.token()).header("Idempotency-Key",key);parameters.put("Idempotency-Key",key); }
        if (body!=null) request.header("Content-Type","application/json");
        var response=http.send(request.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        JsonNode value=JSON.readTree(response.body());
        if (recording && path.startsWith("/maintenance/")) { Map<String,Object> item=new LinkedHashMap<>();item.put("path",path);item.put("method",method);item.put("status",response.statusCode());item.put("contentType",response.headers().firstValue("Content-Type").orElse(""));item.put("response",value);item.put("body",body);item.put("parameters",parameters);item.put("behavior","migrationPathsSwitchRestartAndResidueStaySafe");item.put("behaviorChecks",List.of("migrationSafety"));OBSERVATIONS.add(item); }
        return new Reply(response.statusCode(),value);
    }
    @AfterAll static void evidence() throws Exception {
        if (!passed) return;OBSERVATIONS.getFirst().put("routes",ROUTES);
        Path root=Path.of(System.getProperty("maven.multiModuleProjectDirectory","..")).toAbsolutePath().normalize();
        Process process=new ProcessBuilder("python","server/src/test/resources/contract/settings-evidence.py",root.toString(),"maintenance").directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var output=process.getOutputStream()) { output.write(JSON.writeValueAsBytes(OBSERVATIONS)); }
        String result=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);assertThat(process.waitFor()).as(result).isZero();
    }
    private record Client(String base,String cookie,String token) {}
    private record Reply(int status,JsonNode body) {}
}
