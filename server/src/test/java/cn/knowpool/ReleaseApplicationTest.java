package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.knowpool.infrastructure.ApplicationData;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import tools.jackson.databind.json.JsonMapper;

class ReleaseApplicationTest {
    private static final HttpClient HTTP=HttpClient.newHttpClient();

    @Test
    @DisplayName("同源发行首页及实际构建资产可读取，未知接口不回退首页且请求保护保留")
    void packagedFrontendAndProtectedApiShareOrigin() throws Exception {
        Path directory=Files.createTempDirectory(Path.of("target"),"release-http-").toAbsolutePath();
        try (var application=SpringApplication.run(KnowledgePoolApplication.class,"--server.port=0","--know-pool.data-directory="+directory,"--logging.file.name=target/test-release.log")) {
            String origin="http://127.0.0.1:"+application.getEnvironment().getProperty("local.server.port");
            var index=get(origin+"/");
            assertThat(index.statusCode()).isEqualTo(200);
            assertThat(index.headers().firstValue("Content-Type").orElse("")).startsWith("text/html");
            assertThat(index.body()).contains("id=\"app\"");
            assertThat(index.headers().firstValue("Content-Security-Policy").orElse("")).contains("default-src 'self'", "frame-ancestors 'none'");
            var matcher=Pattern.compile("(?:src|href)=\"(/assets/[^\"]+)\"").matcher(index.body());
            var assets=new HashSet<String>();
            while (matcher.find()) assets.add(matcher.group(1));
            assertThat(assets).isNotEmpty();
            for (String asset:assets) {
                var resource=get(origin+asset);
                assertThat(resource.statusCode()).as(asset).isEqualTo(200);
                assertThat(resource.body()).isNotBlank().doesNotContain("<html");
            }
            // 井号之后的深层导航只在浏览器处理，服务器仍接收相同首页地址。
            assertThat(get(origin+"/#/settings").body()).isEqualTo(index.body());
            var session=get(origin+"/api/v1/session");
            String cookie=session.headers().firstValue("Set-Cookie").orElseThrow().split(";",2)[0];
            assertThat(session.headers().firstValue("Set-Cookie").orElse("")).contains("HttpOnly","SameSite=Strict","Path=/");
            var unknown=HTTP.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/unknown-release-operation")).header("Cookie",cookie).GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unknown.statusCode()).isEqualTo(404);
            assertThat(unknown.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
            assertThat(JsonMapper.builder().build().readTree(unknown.body()).path("code").asText()).isEqualTo("NOT_FOUND");
            var unsafe=HTTP.send(HttpRequest.newBuilder(URI.create(origin+"/api/v1/settings/runtime")).header("Cookie",cookie).header("Content-Type","application/json").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(),HttpResponse.BodyHandlers.ofString());
            assertThat(unsafe.statusCode()).isEqualTo(403);
            var foreign=HTTP.send(HttpRequest.newBuilder(URI.create(origin+"/")).header("Origin","https://unsafe.invalid").GET().build(),HttpResponse.BodyHandlers.ofString());
            assertThat(foreign.statusCode()).isEqualTo(403);
            assertThatThrownBy(() -> new ApplicationData(directory.toString())).isInstanceOf(IOException.class).hasMessageContaining("另一应用进程");
        }
        try (var reopened=new ApplicationData(directory.toString())) { assertThat(reopened.status().schemaVersion()).isEqualTo(17); }
    }

    @Test
    @DisplayName("跨代升级后续结构失败时完整回滚，升级前备份完整且进程锁释放")
    void laterUpgradeFailureRollsBackEarlierSchemaChanges() throws Exception {
        Path root=Files.createDirectories(Path.of("target/test-data"));
        Path directory=Files.createTempDirectory(root,"release-upgrade-").toAbsolutePath();
        try (var initial=new ApplicationData(directory.toString())) { assertThat(initial.status().schemaVersion()).isEqualTo(17); }
        String url="jdbc:sqlite:"+directory.resolve("application.sqlite");
        try (var connection=DriverManager.getConnection(url);var statement=connection.createStatement()) {
            statement.execute("DROP TABLE deleted_resources");statement.execute("DROP TABLE deletion_cleanup");statement.execute("DROP TABLE owned_vector_collections");
            statement.execute("PRAGMA user_version=15");
        }
        // 已有第十七版表使第二步失败，第一步新增的第十六版表也必须回滚。
        assertThatThrownBy(() -> new ApplicationData(directory.toString())).isInstanceOf(IOException.class).hasMessageContaining("停止业务写入");
        try (var connection=DriverManager.getConnection(url);var statement=connection.createStatement()) {
            try (var rows=statement.executeQuery("PRAGMA user_version")) { assertThat(rows.getInt(1)).isEqualTo(15); }
            try (var rows=statement.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('deleted_resources','deletion_cleanup','owned_vector_collections')")) { assertThat(rows.getInt(1)).isZero(); }
            statement.execute("DROP TABLE maintenance_tasks");
        }
        try (var backups=Files.list(directory.resolve("schema-backups"))) {
            var copies=backups.toList();assertThat(copies).hasSize(1);
            try (var connection=DriverManager.getConnection("jdbc:sqlite:"+copies.getFirst());var statement=connection.createStatement()) {
                try (var rows=statement.executeQuery("PRAGMA integrity_check")) { assertThat(rows.getString(1)).isEqualTo("ok"); }
                try (var rows=statement.executeQuery("PRAGMA user_version")) { assertThat(rows.getInt(1)).isEqualTo(15); }
            }
        }
        try (var retried=new ApplicationData(directory.toString())) { assertThat(retried.status().schemaVersion()).isEqualTo(17); }
    }
    private static HttpResponse<String> get(String url) throws Exception {
        return HTTP.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),HttpResponse.BodyHandlers.ofString());
    }
}
