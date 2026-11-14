package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;

import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.RuntimeSettings;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class RuntimeSettingsHttpTest {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final List<Map<String,Object>> OBSERVATIONS=new ArrayList<>();
    private static final List<Map<String,String>> ROUTES=new ArrayList<>();
    private static boolean passed;
    private final HttpClient http=HttpClient.newHttpClient();
    private String base,cookie,token;

    @Test
    @DisplayName("运行参数全量严格校验、维护拒绝与重启持久化")
    void runtimeSettingsPersistAndRejectInvalidChanges() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("target"),"runtime-settings-").toAbsolutePath();
        ObjectNode saved;
        try (var application=start(directory)) {
            connect(application);
            ObjectNode initial=read();
            assertThat(initial.propertyNames()).containsExactlyInAnyOrderElementsOf(RuntimeSettings.FIELDS);
            assertThat(send("GET","/settings/runtime",null,null,null).statusCode()).isEqualTo(401);
            assertThat(send("PUT","/settings/runtime",initial.toString(),cookie,null).statusCode()).isEqualTo(403);
            assertThat(send("PUT","/settings/runtime",initial.toString(),null,token).statusCode()).isEqualTo(401);
            for (String method:List.of("GET","PUT")) {
                assertThat(send(method,"/settings/runtime?extra=1",method.equals("PUT")?initial.toString():null,cookie,token).statusCode()).isEqualTo(400);
                HttpRequest.Builder unsafe=HttpRequest.newBuilder(URI.create(base+"/settings/runtime")).header("Origin","https://unsafe.invalid").header("Cookie",cookie);
                if (method.equals("PUT")) unsafe.header("X-CSRF-Token",token).header("Content-Type","application/json");
                HttpResponse<String> response=http.send(unsafe.method(method,method.equals("PUT")?HttpRequest.BodyPublishers.ofString(initial.toString()):HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                record(response,method.equals("PUT")?initial.toString():null);
                assertThat(response.statusCode()).isEqualTo(403);
            }
            for (String field:RuntimeSettings.FIELDS) {
                if (field.equals("modelRateLimits")) continue;
                rejected(initial.deepCopy().put(field,"1"),initial);
                rejected(initial.deepCopy().put(field,0),initial);
            }
            ObjectNode missing=initial.deepCopy();missing.remove("queueCapacity");rejected(missing,initial);
            rejected(initial.deepCopy().put("unknown",1),initial);
            rejected(initial.deepCopy().put("indexConcurrency",33),initial);
            rejected(initial.deepCopy().put("maxUploadFiles",1001),initial);
            rejected(initial.deepCopy().put("queueCapacity",100001),initial);
            rejected(initial.deepCopy().put("maxUploadBytes",1),initial);
            rejected(initial.deepCopy().put("finalEvidenceLimit",initial.path("maxRequestCandidates").asLong()+1),initial);
            rejected(initial.deepCopy().put("chunkHardLimitCodePoints",initial.path("maxExtractedCodePoints").asLong()+1),initial);
            ObjectNode duplicate=initial.deepCopy();
            var duplicates=duplicate.putArray("modelRateLimits");
            duplicates.addObject().put("endpoint","HTTP://LOCALHOST:80/v1/").put("model","固定模型").put("maxConcurrent",1).put("requestsPerMinute",5).put("tokensPerMinute",1000);
            duplicates.addObject().put("endpoint","http://localhost/v1").put("model","固定模型").put("maxConcurrent",1).put("requestsPerMinute",5).put("tokensPerMinute",1000);
            rejected(duplicate,initial);
            saved=initial.deepCopy().put("searchConcurrency",2).put("questionConcurrency",2).put("queueCapacity",20).put("rrfConstant",42.5).put("sqliteBusyTimeoutMilliseconds",1234);
            saved.putArray("modelRateLimits").addObject().put("endpoint","http://localhost:9911/v1").put("model","固定模型").put("maxConcurrent",2).put("requestsPerMinute",10).put("tokensPerMinute",2000);
            HttpResponse<String> response=send("PUT","/settings/runtime",saved.toString(),cookie,token);
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            assertThat(JSON.readTree(response.body())).isEqualTo(saved);
            assertThat(read()).isEqualTo(saved);
            var gate=application.getBean(ApplicationData.class).gate();
            assertThat(gate.pause()).isTrue();
            try {
                assertThat(send("GET","/settings/runtime",null,cookie,null).statusCode()).isEqualTo(503);
                assertThat(send("PUT","/settings/runtime",saved.toString(),cookie,token).statusCode()).isEqualTo(503);
            } finally { gate.resume(); }
            assertThat(read()).isEqualTo(saved);
        }
        try (var application=start(directory)) {
            connect(application);
            assertThat(read()).isEqualTo(saved);
        }
        passed=true;
    }

    private void rejected(ObjectNode input,ObjectNode previous) throws Exception {
        HttpResponse<String> response=send("PUT","/settings/runtime",input.toString(),cookie,token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
        assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("INVALID_REQUEST");
        assertThat(read()).isEqualTo(previous);
    }
    private ObjectNode read() throws Exception {
        var response=send("GET","/settings/runtime",null,cookie,null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return (ObjectNode)JSON.readTree(response.body());
    }
    private void connect(ConfigurableApplicationContext application) throws Exception {
        base="http://127.0.0.1:"+application.getEnvironment().getProperty("local.server.port")+"/api/v1";
        var response=http.send(HttpRequest.newBuilder(URI.create(base+"/session")).GET().build(),HttpResponse.BodyHandlers.ofString());
        cookie=response.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
        token=JSON.readTree(response.body()).path("csrfToken").asText();
    }
    private HttpResponse<String> send(String method,String path,String body,String cookie,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base+path));
        if (cookie!=null) builder.header("Cookie",cookie);
        if (token!=null) builder.header("X-CSRF-Token",token);
        if (body!=null) builder.header("Content-Type","application/json");
        var response=http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        record(response,body);return response;
    }
    private static void record(HttpResponse<String> response,String body) {
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(response.headers().firstValue("X-Request-Id")).isPresent();
        Map<String,Object> item=new LinkedHashMap<>();
        item.put("path",response.request().uri().getRawPath()+(response.request().uri().getRawQuery()==null?"":"?"+response.request().uri().getRawQuery()));
        item.put("method",response.request().method());item.put("status",response.statusCode());
        item.put("contentType",response.headers().firstValue("Content-Type").orElse(""));
        item.put("response",JSON.readTree(response.body()));item.put("body",body);
        item.put("behavior","runtimeSettingsPersistAndRejectInvalidChanges");
        // 请求保护值只用于内存中的标准参数校验，不写入报告。
        Map<String,String> parameters=new LinkedHashMap<>();
        response.request().headers().map().forEach((key,values) -> parameters.put(key,values.getFirst()));
        item.put("parameters",parameters);OBSERVATIONS.add(item);
    }
    private static ConfigurableApplicationContext start(Path directory) {
        var application=SpringApplication.run(KnowledgePoolApplication.class,"--server.port=0","--know-pool.data-directory="+directory,"--logging.file.name=target/test-runtime-settings.log");
        if (ROUTES.isEmpty()) application.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class).getHandlerMethods().keySet().forEach(mapping -> {
            for (String path:mapping.getPatternValues()) if (path.startsWith("/api/v1/")) mapping.getMethodsCondition().getMethods().forEach(method -> ROUTES.add(Map.of("method",method.name(),"path",path)));
        });
        return application;
    }
    @AfterAll
    static void evidence() throws Exception {
        if (!passed || OBSERVATIONS.isEmpty()) return;
        OBSERVATIONS.getFirst().put("routes",ROUTES);
        Path root=Path.of(System.getProperty("maven.multiModuleProjectDirectory","..")).toAbsolutePath().normalize();
        var process=new ProcessBuilder("python","server/src/test/resources/contract/settings-evidence.py",root.toString(),"runtime").directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT).start();
        try (var input=process.getOutputStream()) { input.write(JSON.writeValueAsBytes(OBSERVATIONS)); }
        String output=new String(process.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
    }
}
