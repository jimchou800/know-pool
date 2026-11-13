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
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class IndexChunkingHttpTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private static final java.util.List<java.util.Map<String, Object>> GAP_OBSERVATIONS = new java.util.ArrayList<>();
    private static final java.util.List<java.util.Map<String, String>> GAP_ROUTES = new java.util.ArrayList<>();
    private static boolean gapsPassed;
    private static boolean documentPassed;
    private static boolean pdfPassed;
    private static boolean previewPassed;
    private static boolean associationsPassed;
    private static boolean versionsPassed;

    @org.junit.jupiter.api.extension.RegisterExtension
    final org.junit.jupiter.api.extension.TestWatcher completed = new org.junit.jupiter.api.extension.TestWatcher() {
        @Override
        public void testSuccessful(org.junit.jupiter.api.extension.ExtensionContext context) {
            if (context.getRequiredTestMethod().getName().equals("publicContractBranchesRemainReachable"))
                gapsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("markdownLocationsRemainBoundToOriginalSource")) documentPassed = true;
            if (context.getRequiredTestMethod().getName().equals("pdfLocationsKeepCrossPageSourceAndBlankPages")) pdfPassed = true;
            if (context.getRequiredTestMethod().getName().equals("preflightAndRebuildKeepVersionsAtomic")) previewPassed = true;
            if (context.getRequiredTestMethod().getName().equals("associationUnionExclusionsAndLateTasksStayConsistent")) associationsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("contentSwitchHistoryAndCancellationRemainBoundToVersions")) versionsPassed = true;
            if (context.getRequiredTestMethod().getName().equals("runningSearchAndRebuildCancellationRejectLateResults")) versionsPassed = true;
        }
    };

    @Test
    @DisplayName("旧副本不可用标记提交失败不删字节，物理删除失败保留可重试意图")
    void obsoleteOriginalCleanupKeepsCommittedAvailabilityTruth() throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"),"original-cleanup-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            app.getBean(cn.knowpool.knowledgebases.Indexes.class).close();
            Client client = client(app);
            String dataset = readCreatedDataset(client,"副本清理故障资料");
            JsonNode first = uploadText(client,dataset,"清理前必须保全的原文。").at("/items/0/file");
            String file = first.path("id").asText(), old = first.at("/latestVersion/id").asText();
            uploadText(client,dataset,"当前最新正文。");
            var files = app.getBean(cn.knowpool.datasets.FileService.class);
            try (var database = java.sql.DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("application.sqlite")); var statement = database.createStatement()) {
                statement.execute("CREATE TRIGGER reject_original_cleanup BEFORE UPDATE OF available ON file_versions BEGIN SELECT RAISE(ABORT,'固定清理事务故障'); END");
                try {
                    org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,() -> files.cleanObsoleteVersions(java.util.Set.of()));
                    assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).body()).isEqualTo("清理前必须保全的原文。");
                } finally { statement.execute("DROP TRIGGER reject_original_cleanup"); }
                Path target = directory.resolve("files/versions").resolve(old);
                Files.delete(target); Files.createDirectory(target); Files.writeString(target.resolve("blocked"),"固定不可删除子项");
                files.cleanObsoleteVersions(java.util.Set.of());
                assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).statusCode()).isEqualTo(410);
                try (var rows = statement.executeQuery("SELECT attempts FROM file_version_cleanup WHERE version_id='" + old + "'")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1);
                }
                Files.delete(target.resolve("blocked")); Files.delete(target);
                files.cleanObsoleteVersions(java.util.Set.of());
                try (var rows = statement.executeQuery("SELECT COUNT(*) FROM file_version_cleanup")) {
                    assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isZero();
                }
                assertThat(send(client,"/files/" + file + "/versions/" + old + "/download","GET",null,null).statusCode()).isEqualTo(410);
            }
        }
    }

    @Test
    @DisplayName("运行中检索及整库重建取消后旧响应不能发布结果或改变生效指针")
    void runningSearchAndRebuildCancellationRejectLateResults() throws Exception {
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        var release = new java.util.concurrent.atomic.AtomicReference<>(new java.util.concurrent.CountDownLatch(1));
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (block.get()) {
                entered.get().countDown();
                try { if (!release.get().await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控取消等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"running-cancel-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String kb = createIndexedBase(client,processing,"运行取消库","不可被取消结果替换的旧正文。");
            JsonNode base = read(client,"/knowledge-bases/" + kb);
            block.set(true);
            JsonNode request = search(client,kb);
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String searchTask = request.at("/task/id").asText();
            assertThat(JSON.readTree(send(client,"/tasks/" + searchTask + "/cancel","POST",null,null).body()).path("state").asText()).isEqualTo("cancelled");
            block.set(false); release.get().countDown();
            JsonNode next = search(client,kb);
            assertThat(await(client,next.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode cancelled = read(client,"/search-requests/" + request.path("id").asText());
            assertThat(cancelled.at("/task/state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("result").isNull()).isTrue();
            entered.set(new java.util.concurrent.CountDownLatch(1)); release.set(new java.util.concurrent.CountDownLatch(1)); block.set(true);
            var input = (tools.jackson.databind.node.ObjectNode) JSON.readTree(processing);
            ((tools.jackson.databind.node.ObjectNode)input.path("chunking")).put("strategy","fixed");
            JsonNode rebuild = JSON.readTree(send(client,"/knowledge-bases/" + kb + "/processing-config","PUT",input.toString(),UUID.randomUUID().toString()).body());
            assertThat(entered.get().await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String parent = rebuild.at("/task/id").asText();
            assertThat(JSON.readTree(send(client,"/tasks/" + parent + "/cancel","POST",null,null).body()).path("state").asText()).isEqualTo("cancelled");
            block.set(false); release.get().countDown();
            assertThat(read(client,"/knowledge-bases/" + kb).path("effectiveConfigVersionId")).isEqualTo(base.path("effectiveConfigVersionId"));
            for (JsonNode task : read(client,"/knowledge-bases/" + kb + "/tasks").path("items"))
                if (task.path("targetConfigVersionId").equals(rebuild.at("/task/targetConfigVersionId")))
                    assertThat(task.path("state").asText()).isEqualTo("cancelled");
            assertThat(read(client,"/knowledge-bases/" + kb + "/index-switches").path("items").size()).isEqualTo(1);
            String route = "/tasks/" + parent + "/cancel";
            assertThat(send(new Client(client.base(),null,null),route,"POST",null,null).statusCode()).isEqualTo(401);
            assertThat(send(new Client(client.base(),client.cookie(),null),route,"POST",null,null).statusCode()).isEqualTo(403);
        } finally { release.get().countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("各库内容独立切换、取消幂等拒绝迟到发布且历史关系及指定下载绑定真实版本")
    void contentSwitchHistoryAndCancellationRemainBoundToVersions() throws Exception {
        var blocked = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (blocked.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控版本更新等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"content-switch-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
            String first = createIndexedBase(client,processing,"版本切换第一库","最初内容😀。");
            JsonNode source = read(client,"/knowledge-bases/" + first + "/sources").path("items").get(0);
            String dataset = source.path("resourceId").asText();
            JsonNode creation = JSON.readTree(send(client,"/knowledge-bases","POST","{\"name\":\"版本切换第二库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",UUID.randomUUID().toString()).body());
            String second = await(client,creation.at("/task/id").asText()).path("resultResourceId").asText();
            assertThat(await(client,read(client,"/knowledge-bases/" + second + "/files").at("/items/0/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode old = read(client,"/knowledge-bases/" + first + "/files").path("items").get(0);
            String file = old.path("fileId").asText(), oldVersion = old.path("latestContentVersionId").asText();
            JsonNode initialSwitch = read(client,"/knowledge-bases/" + first + "/index-switches").path("items").get(0);
            assertThat(initialSwitch.path("fromContentVersionId").isNull()).isTrue();
            assertThat(initialSwitch.path("fromIndexVersionId").isNull()).isTrue();
            assertThat(initialSwitch.path("fromConfigVersionId").isNull()).isTrue();
            blocked.set(true);
            JsonNode replacement = uploadText(client,dataset,"第二次成功内容。");
            String version = replacement.at("/items/0/file/latestVersion/id").asText();
            assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            String running = null, cancelledKb = null;
            for (JsonNode id : replacement.at("/items/0/indexTaskIds")) {
                JsonNode task = read(client,"/tasks/" + id.asText());
                if (task.path("state").asText().equals("queued")) {
                    cancelledKb = task.path("knowledgeBaseId").asText();
                    JsonNode cancelled = JSON.readTree(send(client,"/tasks/" + id.asText() + "/cancel","POST",null,null).body());
                    assertThat(cancelled.path("state").asText()).isEqualTo("cancelled");
                    assertThat(JSON.readTree(send(client,"/tasks/" + id.asText() + "/cancel","POST",null,null).body())).isEqualTo(cancelled);
                } else running = id.asText();
            }
            assertThat(running).isNotNull(); assertThat(cancelledKb).isNotNull();
            JsonNode invalid = uploadText(client,dataset,"不支持文件不能替代进行中内容。","source.exe");
            assertThat(invalid.at("/items/0/outcome").asText()).isEqualTo("failed");
            assertThat(read(client,"/tasks/" + running).path("state").asText()).isEqualTo("running");
            HttpResponse<String> oldDownload = send(client,"/files/" + file + "/versions/" + oldVersion + "/download","GET",null,null);
            assertThat(oldDownload.statusCode()).isEqualTo(200); assertThat(oldDownload.body()).isEqualTo("最初内容😀。");
            assertThat(oldDownload.headers().firstValue("Content-Disposition").orElseThrow()).startsWith("attachment;");
            assertThat(oldDownload.headers().firstValue("Cache-Control").orElseThrow()).isEqualTo("no-store");
            assertThat(oldDownload.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
            blocked.set(false); release.countDown();
            assertThat(await(client,running).path("state").asText()).isEqualTo("succeeded");
            String successfulKb = cancelledKb.equals(first) ? second : first;
            JsonNode successful = read(client,"/knowledge-bases/" + successfulKb + "/files").path("items").get(0);
            assertThat(successful.path("effectiveContentVersionId").asText()).isEqualTo(version);
            assertThat(read(client,"/knowledge-bases/" + cancelledKb + "/files").at("/items/0/effectiveContentVersionId").asText()).isEqualTo(oldVersion);
            JsonNode switches = read(client,"/knowledge-bases/" + successfulKb + "/index-switches");
            assertThat(switches.path("items")).anySatisfy(item -> {
                assertThat(item.path("fromContentVersionId").asText()).isEqualTo(oldVersion);
                assertThat(item.path("toContentVersionId").asText()).isEqualTo(version);
                assertThat(item.path("toIndexVersionId")).isEqualTo(successful.path("effectiveIndexVersionId"));
                assertThat(item.path("fromConfigVersionId")).isEqualTo(item.path("toConfigVersionId"));
            });
            assertThat(read(client,"/knowledge-bases/" + cancelledKb + "/index-switches").path("items").size()).isEqualTo(1);
            JsonNode finalUpload = uploadText(client,dataset,"第三次全部生效正文。");
            for (JsonNode id : finalUpload.at("/items/0/indexTaskIds"))
                assertThat(await(client,id.asText()).path("state").asText()).isEqualTo("succeeded");
            int cleanedStatus = 200;
            for (int attempt=0; attempt<160 && cleanedStatus==200; attempt++) {
                cleanedStatus = send(client,"/files/" + file + "/versions/" + oldVersion + "/download","GET",null,null).statusCode();
                if (cleanedStatus==200) Thread.sleep(50);
            }
            assertThat(cleanedStatus).isEqualTo(410);
            assertThat(read(client,"/files/" + file + "/versions").path("items")).anySatisfy(item -> {
                assertThat(item.path("id").asText()).isEqualTo(oldVersion);
                assertThat(item.path("originalAvailable").asBoolean()).isFalse();
            });
            assertThat(send(client,"/tasks/" + creation.at("/task/id").asText() + "/cancel","POST",null,null).statusCode()).isEqualTo(409);
            assertThat(send(client,"/tasks/" + UUID.randomUUID() + "/cancel","POST",null,null).statusCode()).isEqualTo(404);
            assertThat(send(client,"/tasks/" + running + "/cancel?unknown=1","POST",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/knowledge-bases/" + first + "/index-switches?limit=0","GET",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/files/" + file + "/versions/" + UUID.randomUUID() + "/download","GET",null,null).statusCode()).isEqualTo(404);
        } finally { release.countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("来源并集不重复处理、排除优先且撤最后来源立即下线，迟到索引不能复活")
    void associationUnionExclusionsAndLateTasksStayConsistent() throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var block = new java.util.concurrent.atomic.AtomicBoolean();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer vector = indexedVector(inputs, () -> {
            if (block.get()) {
                entered.countDown();
                try { if (!release.await(15,java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("受控嵌入等待超时。"); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
            }
        });
        Path directory = Files.createTempDirectory(Path.of("target"),"associations-http-").toAbsolutePath();
        String kb, file;
        try {
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                String processing = processing(client,vector.getAddress().getPort(),0,"{}","[]");
                kb = createIndexedBase(client,processing,"来源并集库","最初完整原文。");
                JsonNode member = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                file = member.path("fileId").asText();
                String originalTask = member.at("/task/id").asText();
                JsonNode datasetSource = read(client,"/knowledge-bases/" + kb + "/sources").path("items").get(0);
                String dataset = datasetSource.path("resourceId").asText();
                String sourcePath = "/knowledge-bases/" + kb + "/sources";
                String exclusionPath = "/knowledge-bases/" + kb + "/exclusions/" + file;
                HttpResponse<String> added = send(client,sourcePath,"POST","{\"type\":\"file\",\"resourceId\":\"" + file + "\"}",null);
                assertThat(added.statusCode()).isEqualTo(201);
                String fileSource = JSON.readTree(added.body()).path("id").asText();
                JsonNode both = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                assertThat(both.path("sources").size()).isEqualTo(2);
                assertThat(both.at("/task/id").asText()).isEqualTo(originalTask);
                assertThat(send(client,sourcePath + "/" + datasetSource.path("id").asText(),"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/knowledge-bases/" + kb + "/files").at("/items/0/effectiveIndexVersionId")).isEqualTo(member.path("effectiveIndexVersionId"));
                JsonNode exclusion = JSON.readTree(send(client,exclusionPath,"PUT",null,null).body());
                assertThat(JSON.readTree(send(client,exclusionPath,"PUT",null,null).body())).isEqualTo(exclusion);
                JsonNode excluded = read(client,"/files/" + file + "/knowledge-bases").path("items").get(0);
                assertThat(excluded.path("included").asBoolean()).isFalse();
                assertThat(excluded.path("excluded").asBoolean()).isTrue();
                assertThat(excluded.path("task").isNull()).isTrue();
                assertThat(excluded.path("effectiveIndexVersionId").isNull()).isTrue();
                assertThat(read(client,"/knowledge-bases/" + kb).path("searchableFileCount").asInt()).isZero();
                assertThat(send(client,sourcePath + "/" + fileSource,"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/knowledge-bases/" + kb + "/files").at("/items/0/sources").isEmpty()).isTrue();
                assertThat(read(client,"/knowledge-bases/" + kb + "/exclusions").path("items").size()).isEqualTo(1);
                int calls = inputs.size();
                assertThat(send(client,exclusionPath,"DELETE",null,null).statusCode()).isEqualTo(204);
                assertThat(read(client,"/files/" + file + "/knowledge-bases").path("items").isEmpty()).isTrue();
                assertThat(inputs.size()).isEqualTo(calls);
                assertThat(send(client,exclusionPath,"DELETE",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,sourcePath,"POST","{\"type\":\"dataset\",\"resourceId\":\"" + dataset + "\"}",null).statusCode()).isEqualTo(201);
                JsonNode restored = read(client,"/knowledge-bases/" + kb + "/files").path("items").get(0);
                assertThat(await(client,restored.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                uploadText(client,dataset,"自动同步新增原文。","second.txt");
                for (JsonNode current : read(client,"/knowledge-bases/" + kb + "/files").path("items"))
                    assertThat(await(client,current.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
                assertThat(read(client,"/knowledge-bases/" + kb).path("includedFileCount").asInt()).isEqualTo(2);
                block.set(true);
                uploadText(client,dataset,"覆盖后不可迟到复活的正文。");
                assertThat(entered.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                String lateTask = read(client,"/knowledge-bases/" + kb + "/files").path("items").valueStream().filter(row -> row.path("fileId").asText().equals(file)).findFirst().orElseThrow().at("/task/id").asText();
                assertThat(send(client,exclusionPath,"PUT",null,null).statusCode()).isEqualTo(200);
                block.set(false); release.countDown();
                assertThat(await(client,lateTask).path("state").asText()).isEqualTo("cancelled");
                assertThat(read(client,"/files/" + file + "/knowledge-bases").at("/items/0/included").asBoolean()).isFalse();
                assertThat(send(client,sourcePath + "/" + UUID.randomUUID(),"DELETE",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,exclusionPath + "?unknown=1","PUT",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/knowledge-bases/" + kb + "/exclusions/" + UUID.randomUUID(),"PUT",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/files/" + UUID.randomUUID() + "/knowledge-bases","GET",null,null).statusCode()).isEqualTo(404);
                for (String[] route : java.util.List.of(
                        new String[]{sourcePath + "/" + UUID.randomUUID(),"DELETE"},
                        new String[]{"/knowledge-bases/" + kb + "/exclusions","GET"},
                        new String[]{exclusionPath,"PUT"},new String[]{exclusionPath,"DELETE"},
                        new String[]{"/files/" + file + "/knowledge-bases","GET"})) {
                    assertThat(send(new Client(client.base(),null,null),route[0],route[1],null,null).statusCode()).isEqualTo(401);
                    if (!route[1].equals("GET")) assertThat(send(new Client(client.base(),client.cookie(),null),route[0],route[1],null,null).statusCode()).isEqualTo(403);
                }
                assertThat(send(client,"/knowledge-bases/" + UUID.randomUUID() + "/exclusions","GET",null,null).statusCode()).isEqualTo(404);
                assertThat(send(client,"/files/" + file + "/knowledge-bases?limit=0","GET",null,null).statusCode()).isEqualTo(400);
                assertThat(send(client,"/knowledge-bases/" + kb + "/exclusions?cursor=invalid","GET",null,null).statusCode()).isEqualTo(400);
                for (JsonNode task : read(client,"/knowledge-bases/" + kb + "/tasks").path("items"))
                    if (task.path("kind").asText().equals("cleanup")) assertThat(await(client,task.path("id").asText()).path("state").asText()).isEqualTo("succeeded");
            }
            try (ConfigurableApplicationContext app = start(directory)) {
                Client client = client(app);
                assertThat(read(client,"/knowledge-bases/" + kb + "/exclusions").at("/items/0/fileId").asText()).isEqualTo(file);
                assertThat(read(client,"/files/" + file + "/knowledge-bases").at("/items/0/excluded").asBoolean()).isTrue();
            }
        } finally { release.countDown(); vector.stop(0); }
    }

    @Test
    @DisplayName("切片预检记录真实降级与码点，配置幂等重建原子生效且失败保留旧版")
    void preflightAndRebuildKeepVersionsAtomic() throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "chunk-preflight-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String source = "甲😀".repeat(1400) + "，" + "乙".repeat(2500) + "。末句。";
            String kb = createIndexedBase(client, processing, "切片预检库", source);
            JsonNode original = read(client, "/knowledge-bases/" + kb);
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files").path("items").get(0);
            String taskId = file.at("/task/id").asText();
            int calls = inputs.size();
            JsonNode preview = read(client, "/tasks/" + taskId + "/chunk-preflight");
            assertThat(preview.path("integrityPassed").asBoolean()).isTrue();
            assertThat(preview.path("emptyText").asBoolean()).isFalse();
            assertThat(preview.path("maximumLength").asInt()).isLessThanOrEqualTo(2000);
            assertThat(preview.path("fallbackCounts").path("hard").asInt()).isPositive();
            StringBuilder reconstructed = new StringBuilder();
            for (JsonNode sample : preview.path("samples")) {
                String text = sample.path("text").asText();
                assertThat(sample.path("codePointCount").asInt()).isEqualTo(text.codePointCount(0,text.length()));
                reconstructed.append(text);
            }
            assertThat(reconstructed.toString()).isEqualTo(source);
            read(client, "/tasks/" + taskId + "/chunk-preflight");
            assertThat(inputs.size()).isEqualTo(calls);
            String endpoint = "/knowledge-bases/" + kb + "/processing-config";
            var update = (tools.jackson.databind.node.ObjectNode) JSON.readTree(processing);
            ((tools.jackson.databind.node.ObjectNode) update.path("chunking")).put("strategy", "fixed");
            String key = UUID.randomUUID().toString();
            HttpResponse<String> accepted = send(client, endpoint, "PUT", update.toString(), key);
            assertThat(accepted.statusCode()).isEqualTo(202);
            String parent = JSON.readTree(accepted.body()).at("/task/id").asText();
            assertThat(JSON.readTree(send(client, endpoint, "PUT", update.toString(), key).body()).at("/task/id").asText()).isEqualTo(parent);
            assertThat(await(client,parent).path("state").asText()).isEqualTo("succeeded");
            JsonNode effective = read(client, "/knowledge-bases/" + kb);
            assertThat(effective.path("effectiveConfigVersionId")).isEqualTo(effective.path("targetConfigVersionId"));
            assertThat(effective.path("effectiveConfigVersionId")).isNotEqualTo(original.path("effectiveConfigVersionId"));
            ((tools.jackson.databind.node.ObjectNode) update.path("chunking")).put("strategy", "page");
            assertThat(send(client, endpoint, "PUT", update.toString(), key).statusCode()).isEqualTo(409);
            JsonNode failed = JSON.readTree(send(client, endpoint, "PUT", update.toString(), UUID.randomUUID().toString()).body());
            assertThat(await(client,failed.at("/task/id").asText()).path("state").asText()).isEqualTo("failed");
            assertThat(read(client, "/knowledge-bases/" + kb).path("effectiveConfigVersionId")).isEqualTo(effective.path("effectiveConfigVersionId"));
            JsonNode search = search(client,kb);
            assertThat(await(client,search.at("/task/id").asText()).path("state").asText()).isEqualTo("succeeded");
            JsonNode result = read(client,"/search-requests/" + search.path("id").asText());
            for (JsonNode evidence : result.at("/result/evidence")) assertThat(evidence.path("configVersionId")).isEqualTo(effective.path("effectiveConfigVersionId"));
            assertThat(send(client,"/tasks/" + parent + "/chunk-preflight","GET",null,null).statusCode()).isEqualTo(404);
            assertThat(send(client,"/tasks/" + taskId + "/chunk-preflight?unknown=1","GET",null,null).statusCode()).isEqualTo(400);
            assertThat(send(client,"/tasks/" + UUID.randomUUID() + "/chunk-preflight","GET",null,null).statusCode()).isEqualTo(404);
            read(client,"/knowledge-bases/" + kb + "/tasks");
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("Markdown索引保留真实标题段落表格代码与源行")
    void markdownLocationsRemainBoundToOriginalSource() throws Exception {
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "markdown-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = readCreatedDataset(client, "文档定位资料");
            String source = Files.readString(Path.of("src/test/resources/documents/source-location.md"));
            uploadText(client, dataset, source, "source.md");
            JsonNode accepted = JSON.readTree(send(client, "/knowledge-bases", "POST",
                    "{\"name\":\"文档定位库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                    UUID.randomUUID().toString()).body());
            String kb = await(client, accepted.at("/task/id").asText()).path("resultResourceId").asText();
            for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items")) {
                JsonNode task = await(client, file.at("/task/id").asText());
                assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
            }
            JsonNode request = search(client, kb);
            await(client, request.at("/task/id").asText());
            JsonNode result = read(client, "/search-requests/" + request.path("id").asText());
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.path("text").asText()).contains("第一段😀。");
                assertThat(evidence.at("/location/format").asText()).isEqualTo("markdown");
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(3);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(4);
                assertThat(evidence.at("/location/titlePath/0").asText()).isEqualTo("使用指南");
                assertThat(evidence.at("/location/paragraphId").isTextual()).isTrue();
            });
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.at("/location/tableRowStart").asInt(-1)).isEqualTo(0);
                assertThat(evidence.at("/location/tableRowEnd").asInt(-1)).isEqualTo(1);
                assertThat(evidence.at("/location/tableColumnEnd").asInt(-1)).isEqualTo(1);
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(11);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(13);
            });
            assertThat(result.at("/result/evidence")).anySatisfy(evidence -> {
                assertThat(evidence.at("/location/codeLanguage").asText()).isEqualTo("java");
                assertThat(evidence.at("/location/codeBlockId").isTextual()).isTrue();
                assertThat(evidence.at("/location/lineStart").asInt()).isEqualTo(15);
                assertThat(evidence.at("/location/lineEnd").asInt()).isEqualTo(17);
            });
            for (JsonNode evidence : result.at("/result/evidence")) {
                JsonNode complete = read(client, "/search-requests/" + request.path("id").asText() + "/evidence/" + evidence.path("id").asText());
                assertThat(complete.path("location")).isEqualTo(evidence.path("location"));
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("中文PDF全文证据保留跨页句子与中间空白页实际范围")
    void pdfLocationsKeepCrossPageSourceAndBlankPages() throws Exception {
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {});
        Path directory = Files.createTempDirectory(Path.of("target"), "pdf-location-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            for (String fixture : java.util.List.of("chinese-cross-page.pdf", "chinese-blank-middle-page.pdf")) {
                String dataset = readCreatedDataset(client, fixture);
                byte[] bytes = Files.readAllBytes(Path.of("src/test/resources/documents", fixture));
                String boundary = "pdf-location-boundary";
                var body = new java.io.ByteArrayOutputStream();
                body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + fixture + "\"\r\nContent-Type: application/pdf\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                body.write(bytes);
                body.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                HttpResponse<String> uploaded = http.send(HttpRequest.newBuilder(URI.create(client.base() + "/datasets/" + dataset + "/files:upload"))
                        .header("Cookie", client.cookie()).header("X-CSRF-Token", client.token())
                        .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(uploaded.statusCode()).isEqualTo(200);
                assertThat(JSON.readTree(uploaded.body()).at("/items/0/outcome").asText()).isEqualTo("created");
                JsonNode accepted = JSON.readTree(send(client, "/knowledge-bases", "POST",
                        "{\"name\":\"" + fixture + "\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}", UUID.randomUUID().toString()).body());
                String kb = await(client, accepted.at("/task/id").asText()).path("resultResourceId").asText();
                for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items")) {
                    JsonNode task = await(client, file.at("/task/id").asText());
                    assertThat(task.path("state").asText()).as(task.toString()).isEqualTo("succeeded");
                    assertThat(task.path("warnings")).isNotEmpty();
                }
                JsonNode request = search(client, kb);
                await(client, request.at("/task/id").asText());
                JsonNode result = read(client, "/search-requests/" + request.path("id").asText());
                JsonNode evidence = result.at("/result/evidence/0");
                assertThat(evidence.at("/location/format").asText()).isEqualTo("pdf");
                assertThat(evidence.at("/location/pageStart").asInt()).isEqualTo(1);
                assertThat(evidence.at("/location/pageEnd").asInt()).isEqualTo(fixture.contains("blank") ? 3 : 2);
                assertThat(evidence.at("/location/lineStart").isNull()).isTrue();
                assertThat(evidence.at("/location/tableRowStart").isNull()).isTrue();
                if (!fixture.contains("blank")) assertThat(evidence.path("text").asText()).contains("这是一句跨页的", "连续中文句子。");
                read(client, "/search-requests/" + request.path("id").asText() + "/evidence/" + evidence.path("id").asText());
            }
        } finally { vector.stop(0); }
    }

    @Test
    @DisplayName("公开排队快照、知识库依赖状态、未知资源及存储故障均保留真实契约证据")
    void publicContractBranchesRemainReachable() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var blocked = new java.util.concurrent.atomic.AtomicBoolean(false);
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            if (blocked.get()) {
                entered.countDown();
                try {
                    if (!release.await(15, java.util.concurrent.TimeUnit.SECONDS))
                        throw new AssertionError("受控模型等待超时。");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控模型等待被中断。", failure);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "contract-gaps-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String kb = createIndexedBase(client, processing, "契约可达分支", "完整原文依据。");
            JsonNode current = read(client, "/knowledge-bases/" + kb);
            assertThat(current.path("effectiveConfigVersionId").isTextual()).isTrue();
            assertThat(current.path("effectiveIndexVersionId").isTextual()).isTrue();
            read(client, "/knowledge-bases");
            assertThat(send(client, "/knowledge-bases/" + kb, "PATCH", "{\"description\":\"已生效\"}", null)
                            .statusCode())
                    .isEqualTo(200);
            blocked.set(true);
            JsonNode first = search(client, kb);
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode queued = search(client, kb);
            JsonNode snapshot =
                    read(client, "/search-requests/" + queued.path("id").asText());
            assertThat(snapshot.at("/task/state").asText()).isEqualTo("queued");
            for (String pointer : java.util.List.of("/scope", "/task/startedAt", "/task/finishedAt"))
                assertThat(snapshot.at(pointer).isNull()).as(pointer).isTrue();
            String dataset = readCreatedDataset(client, "排队索引资料");
            uploadText(client, dataset, "第二份排队资料。");
            uploadText(client, dataset, "第三份排队资料。", "third.txt");
            JsonNode creation = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"排队索引库\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String queuedKb = await(client, creation.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode tasks = read(client, "/knowledge-bases/" + queuedKb + "/tasks");
            assertThat(tasks.path("items")).anySatisfy(task -> {
                assertThat(task.path("state").asText()).isEqualTo("queued");
                assertThat(task.path("startedAt").isNull()).isTrue();
                assertThat(task.path("finishedAt").isNull()).isTrue();
                assertThat(task.path("progressTotal").isNull()).isTrue();
            });
            blocked.set(false);
            release.countDown();
            await(client, first.at("/task/id").asText());
            await(client, queued.at("/task/id").asText());
            for (JsonNode task : tasks.path("items"))
                await(client, task.path("id").asText());
            String absent = UUID.randomUUID().toString();
            assertThat(send(client, "/knowledge-bases/" + absent + "/tasks", "GET", null, null)
                            .statusCode())
                    .isEqualTo(404);
            assertThat(send(
                                    client,
                                    "/knowledge-bases/" + absent + "/search-test",
                                    "POST",
                                    "{\"query\":\"未知库\",\"knowledgeBaseIds\":[\"" + absent + "\"]}",
                                    UUID.randomUUID().toString())
                            .statusCode())
                    .isEqualTo(404);
            var invalid = send(
                    client, "/search-requests/" + first.path("id").asText() + "/evidence/invalid", "GET", null, null);
            assertThat(invalid.statusCode()).isEqualTo(400);
            assertThat(JSON.readTree(invalid.body()).path("fieldErrors")).isNotEmpty();
            vector.stop(0);
            JsonNode failed = search(client, kb);
            assertThat(await(client, failed.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("failed");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("availabilityReason")
                            .isTextual())
                    .isTrue();
            read(client, "/knowledge-bases");
            var renamed = send(client, "/knowledge-bases/" + kb, "PATCH", "{\"description\":\"依赖故障仍保留生效版本\"}", null);
            assertThat(renamed.statusCode()).isEqualTo(200);
            assertThat(JSON.readTree(renamed.body()).path("availabilityReason").isTextual())
                    .isTrue();
            try (var database = java.sql.DriverManager.getConnection(
                            "jdbc:sqlite:" + directory.resolve("application.sqlite"));
                    var statement = database.createStatement()) {
                statement.execute("ALTER TABLE search_identities RENAME TO blocked_search_identities");
                statement.execute("ALTER TABLE task_events RENAME TO blocked_task_events");
                try {
                    for (String path : java.util.List.of(
                            "/search-requests/" + absent,
                            "/search-requests/" + absent + "/evidence/" + UUID.randomUUID(),
                            "/tasks/" + first.at("/task/id").asText() + "/events"))
                        assertThat(send(client, path, "GET", null, null).statusCode())
                                .as(path)
                                .isEqualTo(500);
                } finally {
                    statement.execute("ALTER TABLE blocked_search_identities RENAME TO search_identities");
                    statement.execute("ALTER TABLE blocked_task_events RENAME TO task_events");
                }
            }
            assertThat(send(client, "/search-requests/" + absent, "GET", null, null)
                            .statusCode())
                    .isEqualTo(404);
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("真实上传切片保留缩写小数引用和换行，不切断完整句")
    void sentencesRemainIntact() throws Exception {
        String first = "首".repeat(1800) + "。";
        String second = "Dr. Smith measured 3.14, e.g. a reference “引用\n续行” " + "b".repeat(240) + ".";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("重叠只复用完整句，并在下一句需要空间时取消")
    void overlapKeepsCompleteSentences() throws Exception {
        String first = "前".repeat(1500) + "。";
        String second = "中".repeat(100) + "。";
        String third = "后".repeat(500) + "。";
        assertThat(index(first + second + third, 200, "{}", "[]")).containsExactly(first + second, second + third);
        String large = "尾".repeat(1950) + "。";
        assertThat(index(first + second + large, 200, "{}", "[]")).containsExactly(first + second, large);
    }

    @Test
    @DisplayName("超长句按分隔符降级，补充平面字符按码点硬切且正文无丢失")
    void longSentencesKeepEveryCodePoint() throws Exception {
        String first = "甲".repeat(1900) + "，";
        String second = "乙".repeat(300);
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
        String text = "😀甲".repeat(1300);
        java.util.List<String> chunks = index(text, 0, "{}", "[]");
        assertThat(chunks).hasSize(2);
        assertThat(chunks.getFirst()).isEqualTo("😀甲".repeat(1000));
        assertThat(chunks.getLast()).isEqualTo("😀甲".repeat(300));
        assertThat(String.join("", chunks)).isEqualTo(text);
        assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.codePointCount(0, chunk.length()))
                .isLessThanOrEqualTo(2000));
    }

    @Test
    @DisplayName("句末引用保留在原句，列表编号和首字母缩写不冒充句末")
    void quotationAndListBoundariesRemainTraceable() throws Exception {
        String first = "前".repeat(1948) + "。”";
        String second = "\n1. Dr. A. Smith in the U.S. measured 3.14, e.g. “引用\n续行” " + "后".repeat(200) + "！";
        assertThat(index(first + second, 0, "{}", "[]")).containsExactly(first, second);
    }

    @Test
    @DisplayName("嵌入输入仅加入选定自定义文本字段，未选中字段不外发")
    void onlySelectedMetadataEntersEmbedding() throws Exception {
        assertThat(index("固定正文", 0, "{\"user_label\":\"选中内容\",\"user_private\":\"未选内容\"}", "[\"user_label\"]"))
                .containsExactly("固定正文\nuser_label: 选中内容");
    }

    @Test
    @DisplayName("正文与选中元信息共同超过模型预算时拒绝外发和生效")
    void metadataBudgetPreventsDispatch() throws Exception {
        assertThat(index(
                        "正".repeat(1000),
                        0,
                        "{\"user_label\":\"" + "元".repeat(2000) + "\"}",
                        "[\"user_label\"]",
                        "failed"))
                .isEmpty();
    }

    @Test
    @DisplayName("首次嵌入外发期间覆盖上传，旧任务取消且迟到响应不能发布")
    void replacedVersionCannotPublishLateEmbedding() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {
            if (first.compareAndSet(true, false)) {
                entered.countDown();
                try {
                    assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                            .isTrue();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("受控嵌入等待被中断。", interrupted);
                }
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "late-embedding-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"首次版本保护\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "旧正文迟到返回");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"首次索引保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode initial = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            String oldTask = initial.at("/task/id").asText();
            assertThat(initial.path("effectiveIndexVersionId").isNull()).isTrue();
            JsonNode replacement = uploadText(client, dataset, "新正文唯一生效");
            JsonNode cancelled = await(client, oldTask);
            assertThat(cancelled.path("state").asText()).isEqualTo("cancelled");
            assertThat(cancelled.path("resultResourceId").isNull()).isTrue();
            release.countDown();
            JsonNode completed =
                    await(client, replacement.at("/items/0/indexTaskIds/0").asText());
            assertThat(completed.path("state").asText())
                    .as(completed.toString())
                    .isEqualTo("succeeded");
            JsonNode current = read(client, "/knowledge-bases/" + kb + "/files").at("/items/0");
            assertThat(current.path("effectiveIndexVersionId").asText())
                    .isEqualTo(completed.path("resultResourceId").asText());
            assertThat(current.path("effectiveContentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
            assertThat(read(client, "/tasks/" + oldTask).path("state").asText()).isEqualTo("cancelled");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"新正文\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("新正文唯一生效");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(completed.path("targetContentVersionId").asText());
        } finally {
            release.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("检索外发期间切换生效版本，当前请求仍返回捕获版本的完整证据")
    void searchRetainsCapturedVersionDuringSwitch() throws Exception {
        checkSearchSnapshot(false);
    }

    @Test
    @DisplayName("覆盖更新尚在运行时旧索引证据使用契约updating状态且知识库聚合保持运行")
    void updatingEvidenceUsesContractState() throws Exception {
        checkSearchSnapshot(true);
    }

    private void checkSearchSnapshot(boolean updating) throws Exception {
        var updatingEntered = new java.util.concurrent.CountDownLatch(1);
        var updatingRelease = new java.util.concurrent.CountDownLatch(1);
        var embeddingCalls = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        HttpServer vector = indexedVector(
                new java.util.concurrent.CopyOnWriteArrayList<>(),
                () -> {
                    if (updating && embeddingCalls.incrementAndGet() == 3) {
                        updatingEntered.countDown();
                        try {
                            assertThat(updatingRelease.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("受控更新等待被中断。", interrupted);
                        }
                    }
                },
                () -> {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown();
                        try {
                            assertThat(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                    .isTrue();
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError("受控检索等待被中断。", interrupted);
                        }
                    }
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "search-snapshot-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"检索版本快照\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, "原生效正文");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"读取版本保护\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            JsonNode oldTask = await(
                    client,
                    read(client, "/knowledge-bases/" + kb + "/files")
                            .at("/items/0/task/id")
                            .asText());
            assertThat(oldTask.path("state").asText()).isEqualTo("succeeded");
            JsonNode search = JSON.readTree(send(
                            client,
                            "/knowledge-bases/" + kb + "/search-test",
                            "POST",
                            "{\"query\":\"版本快照\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            assertThat(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            JsonNode replacement = uploadText(client, dataset, "后续生效正文");
            String replacementTask = replacement.at("/items/0/indexTaskIds/0").asText();
            if (updating)
                assertThat(updatingEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            JsonNode newTask = updating ? read(client, "/tasks/" + replacementTask) : await(client, replacementTask);
            assertThat(newTask.path("state").asText())
                    .as(newTask.toString())
                    .isEqualTo(updating ? "running" : "succeeded");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo(updating ? "running" : "idle");
            assertThat(read(client, "/knowledge-bases")
                            .at("/items/0/processingState")
                            .asText())
                    .isEqualTo(updating ? "running" : "idle");
            assertThat(newTask.path("targetContentVersionId").asText())
                    .isNotEqualTo(oldTask.path("targetContentVersionId").asText());
            release.countDown();
            assertThat(await(client, search.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result =
                    read(client, "/search-requests/" + search.path("id").asText());
            assertThat(result.at("/result/outcome").asText())
                    .as(result.toString())
                    .isEqualTo("matches");
            assertThat(result.at("/result/evidence").size()).isEqualTo(1);
            assertThat(result.at("/result/evidence/0/text").asText()).isEqualTo("原生效正文");
            assertThat(result.at("/result/evidence/0/contentVersionId").asText())
                    .isEqualTo(oldTask.path("targetContentVersionId").asText());
            assertThat(result.at("/result/evidence/0/indexVersionId").asText())
                    .isEqualTo(oldTask.path("resultResourceId").asText());
            assertThat(result.at("/result/evidence/0/updateState").asText()).isEqualTo(updating ? "updating" : "ready");
            assertThat(result.at("/result/evidence/0/usingPreviousVersion").asBoolean())
                    .isTrue();
            updatingRelease.countDown();
            assertThat(await(client, replacementTask).path("state").asText()).isEqualTo("succeeded");
        } finally {
            release.countDown();
            updatingRelease.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("供应商原始用量小数和整数溢出均不得产生生效索引")
    void malformedUsageCannotPublish() throws Exception {
        for (String usage :
                java.util.List.of("4.5", "4294967300", "-1", "null", "\"4\"", "9007199254740992", "100", "missing")) {
            assertThat(index("固定正文", 0, "{}", "[]", "failed", usage, "INTEGRITY_FAILED"))
                    .hasSize(1);
        }
    }

    @Test
    @DisplayName("索引成功持久保存独立实际用量字节上界和计量器版本")
    void successfulIndexRecordsMeasuredUsage() throws Exception {
        assertThat(index("😀甲e\u0301\t正文", 0, "{}", "[]")).containsExactly("😀甲e\u0301\t正文");
    }

    @Test
    @DisplayName("完整输入恰好字节预算可运行，超一字节拒绝外发，零实际用量合法")
    void exactInputBudgetAndZeroUsage() throws Exception {
        String text = "正".repeat(2000);
        String metadata = "元".repeat(726) + "a";
        assertThat(index(text, 0, "{\"user_label\":\"" + metadata + "\"}", "[\"user_label\"]"))
                .containsExactly(text + "\nuser_label: " + metadata);
        assertThat(index(text, 0, "{\"user_label\":\"" + metadata + "b\"}", "[\"user_label\"]", "failed"))
                .isEmpty();
        assertThat(index("固定正文", 0, "{}", "[]", "succeeded", "0", "")).containsExactly("固定正文");
    }

    @Test
    @DisplayName("多文件一项成功但另一项仍运行时知识库列表与详情保持运行状态")
    void multipleFilesKeepAggregateRunning() throws Exception {
        var firstEntered = new java.util.concurrent.CountDownLatch(1);
        var firstRelease = new java.util.concurrent.CountDownLatch(1);
        var secondEntered = new java.util.concurrent.CountDownLatch(1);
        var secondRelease = new java.util.concurrent.CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        HttpServer vector = indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {
            int call = calls.incrementAndGet();
            (call == 1 ? firstEntered : secondEntered).countDown();
            try {
                assertThat((call == 1 ? firstRelease : secondRelease).await(5, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("受控多文件等待被中断。", interrupted);
            }
        });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "aggregate-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String dataset = readCreatedDataset(client, "多文件聚合");
            uploadText(client, dataset, "甲资料");
            uploadText(client, dataset, "乙资料", "second.txt");
            JsonNode accepted = JSON.readTree(send(
                            client,
                            "/knowledge-bases",
                            "POST",
                            "{\"name\":\"多文件聚合\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                    + "\"]}",
                            UUID.randomUUID().toString())
                    .body());
            String kb = await(client, accepted.at("/task/id").asText())
                    .path("resultResourceId")
                    .asText();
            assertThat(firstEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("running");
            firstRelease.countDown();
            assertThat(secondEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    .isTrue();
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("searchableFileCount")
                            .asInt())
                    .isEqualTo(1);
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("running");
            assertThat(read(client, "/knowledge-bases")
                            .at("/items/0/processingState")
                            .asText())
                    .isEqualTo("running");
            secondRelease.countDown();
            for (JsonNode file :
                    read(client, "/knowledge-bases/" + kb + "/files").path("items"))
                assertThat(await(client, file.at("/task/id").asText())
                                .path("state")
                                .asText())
                        .isEqualTo("succeeded");
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo("idle");
        } finally {
            firstRelease.countDown();
            secondRelease.countDown();
            vector.stop(0);
        }
    }

    @Test
    @DisplayName("批量命中按召回顺序去重并排除越库切片，超候选上限失败")
    void retrievedCandidatesAreBoundedOrderedAndScoped() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var foreign = new java.util.concurrent.atomic.AtomicReference<JsonNode>();
        var orderedIds = new java.util.ArrayList<String>();
        HttpServer vector =
                indexedVector(new java.util.concurrent.CopyOnWriteArrayList<>(), () -> {}, () -> {}, "4", hits -> {
                    int call = calls.incrementAndGet();
                    if (call == 1) foreign.set(hits.get(0).deepCopy());
                    else if (call == 2) {
                        var reversed = new java.util.ArrayList<JsonNode>();
                        hits.forEach(reversed::add);
                        java.util.Collections.reverse(reversed);
                        hits.removeAll();
                        for (JsonNode hit : reversed) {
                            hits.add(hit);
                            orderedIds.add(hit.path("id").asText());
                        }
                        hits.add(reversed.getFirst());
                        hits.add(foreign.get());
                        hits.add(JSON.createObjectNode()
                                .put("id", UUID.randomUUID().toString())
                                .put("score", 0.9));
                    } else {
                        JsonNode hit = hits.get(0).deepCopy();
                        hits.removeAll();
                        for (int i = 0; i < 11; i++) hits.add(hit);
                    }
                });
        Path directory =
                Files.createTempDirectory(Path.of("target"), "retrieval-batch-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), 0, "{}", "[]");
            String other = createIndexedBase(client, processing, "另一知识库", "越库资料");
            JsonNode foreignRequest = search(client, other);
            assertThat(await(client, foreignRequest.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            String kb = createIndexedBase(client, processing, "当前知识库", "甲".repeat(2000) + "乙".repeat(2000));
            JsonNode request = search(client, kb);
            assertThat(await(client, request.at("/task/id").asText())
                            .path("state")
                            .asText())
                    .isEqualTo("succeeded");
            JsonNode result = read(
                            client, "/search-requests/" + request.path("id").asText())
                    .at("/result/evidence");
            var actual = new java.util.ArrayList<String>();
            for (JsonNode value : result) {
                actual.add(value.path("chunkId").asText());
                assertThat(value.path("knowledgeBaseId").asText()).isEqualTo(kb);
            }
            assertThat(actual).containsExactlyElementsOf(orderedIds).hasSize(2);
            JsonNode overflow = search(client, kb);
            JsonNode failed = await(client, overflow.at("/task/id").asText());
            assertThat(failed.path("state").asText()).isEqualTo("failed");
            assertThat(failed.at("/failure/code").asText()).isEqualTo("INTEGRITY_FAILED");
        } finally {
            vector.stop(0);
        }
    }

    private String readCreatedDataset(Client client, String name) throws Exception {
        return JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"" + name + "\"}", null)
                        .body())
                .path("id")
                .asText();
    }

    private String createIndexedBase(Client client, String processing, String name, String text) throws Exception {
        String dataset = readCreatedDataset(client, name);
        uploadText(client, dataset, text);
        JsonNode accepted = JSON.readTree(send(
                        client,
                        "/knowledge-bases",
                        "POST",
                        "{\"name\":\"" + name + "\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset
                                + "\"]}",
                        UUID.randomUUID().toString())
                .body());
        String kb = await(client, accepted.at("/task/id").asText())
                .path("resultResourceId")
                .asText();
        for (JsonNode file : read(client, "/knowledge-bases/" + kb + "/files").path("items"))
            assertThat(await(client, file.at("/task/id").asText()).path("state").asText())
                    .isEqualTo("succeeded");
        return kb;
    }

    private JsonNode search(Client client, String kb) throws Exception {
        return JSON.readTree(send(
                        client,
                        "/knowledge-bases/" + kb + "/search-test",
                        "POST",
                        "{\"query\":\"固定查询\",\"knowledgeBaseIds\":[\"" + kb + "\"]}",
                        UUID.randomUUID().toString())
                .body());
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields) throws Exception {
        return index(text, overlap, metadata, fields, "succeeded");
    }

    private java.util.List<String> index(String text, int overlap, String metadata, String fields, String expectedState)
            throws Exception {
        return index(text, overlap, metadata, fields, expectedState, "4", "MODEL_CONFIG_INVALID");
    }

    private java.util.List<String> index(
            String text,
            int overlap,
            String metadata,
            String fields,
            String expectedState,
            String usage,
            String failureCode)
            throws Exception {
        var inputs = new java.util.concurrent.CopyOnWriteArrayList<String>();
        HttpServer vector = indexedVector(inputs, () -> {}, () -> {}, usage);
        Path directory =
                Files.createTempDirectory(Path.of("target"), "chunking-http-").toAbsolutePath();
        try (ConfigurableApplicationContext app = start(directory)) {
            Client client = client(app);
            String processing = processing(client, vector.getAddress().getPort(), overlap, metadata, fields);
            String dataset = JSON.readTree(send(client, "/datasets", "POST", "{\"name\":\"切片固定样例\"}", null)
                            .body())
                    .path("id")
                    .asText();
            uploadText(client, dataset, text);
            HttpResponse<String> response = send(
                    client,
                    "/knowledge-bases",
                    "POST",
                    "{\"name\":\"切片校验\",\"processing\":" + processing + ",\"datasetIds\":[\"" + dataset + "\"]}",
                    UUID.randomUUID().toString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
            JsonNode created =
                    await(client, JSON.readTree(response.body()).at("/task/id").asText());
            assertThat(created.path("state").asText()).as(created.toString()).isEqualTo("succeeded");
            String kb = created.path("resultResourceId").asText();
            JsonNode file = read(client, "/knowledge-bases/" + kb + "/files")
                    .path("items")
                    .get(0);
            JsonNode indexed = await(client, file.at("/task/id").asText());
            assertThat(indexed.path("state").asText()).as(indexed.toString()).isEqualTo(expectedState);
            if (expectedState.equals("failed")) {
                assertThat(indexed.at("/failure/code").asText()).isEqualTo(failureCode);
                assertThat(read(client, "/knowledge-bases/" + kb)
                                .path("searchableFileCount")
                                .asInt())
                        .isZero();
            }
            assertThat(read(client, "/knowledge-bases/" + kb)
                            .path("processingState")
                            .asText())
                    .isEqualTo(expectedState.equals("failed") ? "failed" : "idle");
            if (expectedState.equals("succeeded")) {
                var measurements = app.getBean(cn.knowpool.infrastructure.ApplicationData.class)
                        .read(jdbc -> jdbc.sql(
                                        "SELECT input_upper_bound,prompt_tokens,meter_version FROM index_chunks ORDER BY ordinal")
                                .query((row, n) -> java.util.List.of(row.getInt(1), row.getInt(2), row.getString(3)))
                                .list());
                assertThat(measurements).hasSize(inputs.size());
                for (int i = 0; i < inputs.size(); i++) {
                    assertThat(measurements.get(i))
                            .containsExactly(
                                    inputs.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                                    Integer.parseInt(usage),
                                    "qwen-byte-upper-bound-1");
                }
            }
            return java.util.List.copyOf(inputs);
        } finally {
            vector.stop(0);
        }
    }

    private String processing(Client client, int port, int overlap, String metadata, String fields) throws Exception {
        configure(client, port);
        send(
                client,
                "/settings/models/embedding",
                "PUT",
                "{\"endpoint\":\"http://127.0.0.1:" + port
                        + "/v1\",\"model\":\"text-embedding-v4\",\"contextWindowTokens\":null,\"maxOutputTokens\":null,\"credential\":{\"action\":\"replace\",\"value\":\"fixed-test-key\"}}",
                null);
        JsonNode saved = read(client, "/settings/models/embedding");
        return "{\"chunking\":{\"strategy\":\"structure\",\"overlapCodePoints\":" + overlap
                + ",\"removePageHeadersFooters\":false,\"customMetadata\":" + metadata
                + ",\"embeddedMetadataFields\":" + fields
                + "},\"embedding\":{\"modelConfigVersionId\":\""
                + saved.path("versionId").asText()
                + "\",\"dimensions\":3,\"distance\":\"cosine\",\"similarityThreshold\":0.5,\"candidatesPerQuery\":10,\"modelSnapshot\":{\"endpoint\":\"http://127.0.0.1:"
                + port + "/v1\",\"model\":\"text-embedding-v4\"}}}";
    }

    private void configure(Client client, int port) throws Exception {
        String endpoint = "http://127.0.0.1:" + port;
        assertThat(send(
                                client,
                                "/settings/vector-store",
                                "PUT",
                                "{\"endpoint\":\"" + endpoint + "\",\"credential\":{\"action\":\"clear\"}}",
                                null)
                        .statusCode())
                .isEqualTo(200);
    }

    private JsonNode uploadText(Client client, String dataset, String text) throws Exception {
        return uploadText(client, dataset, text, "source.txt");
    }

    private JsonNode uploadText(Client client, String dataset, String text, String filename) throws Exception {
        String boundary = "knowledge-boundary";
        String body = "--" + boundary
                + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + filename
                + "\"\r\nContent-Type: " + (filename.endsWith(".md") ? "text/markdown" : "text/plain") + "\r\n\r\n"
                + text + "\r\n--"
                + boundary + "--\r\n";
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(client.base() + "/datasets/" + dataset + "/files:upload"))
                .header("Cookie", client.cookie())
                .header("X-CSRF-Token", client.token())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private JsonNode await(Client client, String id) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode task = read(client, "/tasks/" + id);
            if (!java.util.Set.of("queued", "running")
                    .contains(task.path("state").asText())) return task;
            Thread.sleep(25);
        }
        throw new AssertionError("创建任务没有按期结束。");
    }

    private JsonNode read(Client client, String path) throws Exception {
        HttpResponse<String> response = send(client, path, "GET", null, null);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JSON.readTree(response.body());
    }

    private HttpResponse<String> send(Client client, String path, String method, String body, String key)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(client.base() + path));
        if (client.cookie() != null) request.header("Cookie", client.cookie());
        if (client.token() != null) request.header("X-CSRF-Token", client.token());
        if (key != null) request.header("Idempotency-Key", key);
        if (body != null) request.header("Content-Type", "application/json");
        HttpResponse<String> response = http.send(
                request.method(
                                method,
                                body == null
                                        ? HttpRequest.BodyPublishers.noBody()
                                        : HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                .anyMatch(frame -> java.util.Set.of("runningSearchAndRebuildCancellationRejectLateResults", "contentSwitchHistoryAndCancellationRemainBoundToVersions", "associationUnionExclusionsAndLateTasksStayConsistent", "preflightAndRebuildKeepVersionsAtomic", "publicContractBranchesRemainReachable", "markdownLocationsRemainBoundToOriginalSource", "pdfLocationsKeepCrossPageSourceAndBlankPages").contains(frame.getMethodName()))) {
            java.util.Map<String, Object> item = new java.util.LinkedHashMap<>();
            item.put("path", response.request().uri().getRawPath() + (response.request().uri().getRawQuery() == null ? "" : "?" + response.request().uri().getRawQuery()));
            item.put("method", method);
            item.put("status", response.statusCode());
            item.put(
                    "contentType", response.headers().firstValue("Content-Type").orElse(""));
            if (response.statusCode() == 204) assertThat(response.body()).isEmpty();
            boolean binary = response.statusCode() == 200 && (path.endsWith("/download") || path.endsWith("/preview"));
            item.put("response", response.statusCode() == 204 ? null : binary ? response.body() : JSON.readTree(response.body()));
            java.util.Map<String,String> responseHeaders = new java.util.LinkedHashMap<>();
            response.headers().map().forEach((name,values) -> responseHeaders.put(name,values.getFirst()));
            item.put("responseHeaders",responseHeaders);
            item.put("body", body);
            java.util.Map<String, String> parameters = new java.util.LinkedHashMap<>();
            response.request().headers().map().forEach((name, values) -> parameters.put(name, values.getFirst()));
            item.put("parameters", parameters);
            String behavior = java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(StackTraceElement::getMethodName).filter(name -> java.util.Set.of("runningSearchAndRebuildCancellationRejectLateResults", "contentSwitchHistoryAndCancellationRemainBoundToVersions", "associationUnionExclusionsAndLateTasksStayConsistent", "preflightAndRebuildKeepVersionsAtomic", "markdownLocationsRemainBoundToOriginalSource", "pdfLocationsKeepCrossPageSourceAndBlankPages").contains(name)).findFirst().orElse("publicContractBranchesRemainReachable");
            item.put("behavior", behavior);
            if (behavior.equals("preflightAndRebuildKeepVersionsAtomic")) item.put("behaviorChecks", java.util.List.of("processingConfigurationSwitch"));
            if (behavior.equals("contentSwitchHistoryAndCancellationRemainBoundToVersions") || behavior.equals("runningSearchAndRebuildCancellationRejectLateResults")) item.put("behaviorChecks", java.util.List.of("contentVersionSwitch"));
            GAP_OBSERVATIONS.add(item);
        }
        return response;
    }

    private Client client(ConfigurableApplicationContext app) throws Exception {
        String base = "http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port") + "/api/v1";
        HttpResponse<String> response = send(new Client(base, null, null), "/session", "GET", null, null);
        return new Client(
                base,
                response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0],
                JSON.readTree(response.body()).get("csrfToken").asText());
    }

    private HttpServer indexedVector(java.util.List<String> inputs) throws Exception {
        return indexedVector(inputs, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding) throws Exception {
        return indexedVector(inputs, beforeEmbedding, () -> {});
    }

    private HttpServer indexedVector(java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, "4");
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs, Runnable beforeEmbedding, Runnable beforeQuery, String usage)
            throws Exception {
        return indexedVector(inputs, beforeEmbedding, beforeQuery, usage, hits -> {});
    }

    private HttpServer indexedVector(
            java.util.List<String> inputs,
            Runnable beforeEmbedding,
            Runnable beforeQuery,
            String usage,
            java.util.function.Consumer<tools.jackson.databind.node.ArrayNode> adjustHits)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(command -> Thread.ofVirtual().start(command));
        java.util.Set<String> collections = java.util.concurrent.ConcurrentHashMap.newKeySet();
        java.util.Map<String, JsonNode> points = new java.util.concurrent.ConcurrentHashMap<>();
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            byte[] request = exchange.getRequestBody().readAllBytes();
            JsonNode body = request.length == 0 ? JSON.createObjectNode() : JSON.readTree(request);
            String response;
            int status = 200;
            if (path.equals("/v1/embeddings")) {
                inputs.add(body.at("/input/0").asText());
                beforeEmbedding.run();
                String usageBody =
                        usage.equals("missing") ? "{}" : "{\"prompt_tokens\":" + usage + ",\"total_tokens\":4}";
                response =
                        "{\"object\":\"list\",\"data\":[{\"object\":\"embedding\",\"index\":0,\"embedding\":[1,0,0]}],\"model\":\"text-embedding-v4\",\"usage\":"
                                + usageBody + "}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("PUT")) {
                for (JsonNode point : body.path("points"))
                    points.put(point.path("id").asText(), point);
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (path.endsWith("/points") && exchange.getRequestMethod().equals("POST")) {
                var result = JSON.createArrayNode();
                for (JsonNode id : body.path("ids"))
                    if (points.containsKey(id.asText())) {
                        var point = (tools.jackson.databind.node.ObjectNode)
                                points.get(id.asText()).deepCopy();
                        var normalized = JSON.createArrayNode();
                        for (JsonNode component : point.path("vector")) {
                            if (component.doubleValue() == Math.rint(component.doubleValue()))
                                normalized.add(component.intValue());
                            else normalized.add(component.doubleValue());
                        }
                        point.set("vector", normalized);
                        result.add(point);
                    }
                response = JSON.writeValueAsString(java.util.Map.of("status", "ok", "result", result));
            } else if (path.endsWith("/points/query")) {
                beforeQuery.run();
                var result = JSON.createArrayNode();
                for (JsonNode point : points.values())
                    for (JsonNode index : body.at("/filter/must/0/match/any"))
                        if (point.at("/payload/index_id").asText().equals(index.asText()))
                            result.add(((tools.jackson.databind.node.ObjectNode) point.deepCopy()).put("score", 0.9));
                adjustHits.accept(result);
                response = JSON.writeValueAsString(
                        java.util.Map.of("status", "ok", "result", java.util.Map.of("points", result)));
            } else if (path.endsWith("/points/delete")) {
                String index = body.at("/filter/must/0/match/value").asText();
                points.entrySet().removeIf(entry -> entry.getValue().at("/payload/index_id").asText().equals(index));
                response = "{\"status\":\"ok\",\"result\":{\"status\":\"completed\"}}";
            } else if (exchange.getRequestMethod().equals("PUT")) {
                collections.add(path);
                response = "{\"status\":\"ok\",\"result\":true}";
            } else if (collections.contains(path)) {
                response =
                        "{\"status\":\"ok\",\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}";
            } else {
                status = 404;
                response = "{}";
            }
            byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return server;
    }

    private ConfigurableApplicationContext start(Path directory) {
        ConfigurableApplicationContext app = new SpringApplication(KnowledgePoolApplication.class)
                .run("--server.port=0", "--know-pool.data-directory=" + directory);
        if (GAP_ROUTES.isEmpty())
            app.getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping.class)
                    .getHandlerMethods()
                    .forEach((mapping, handler) -> {
                        for (String path : mapping.getPatternValues())
                            for (var method : mapping.getMethodsCondition().getMethods())
                                GAP_ROUTES.add(java.util.Map.of("method", method.name(), "path", path));
                    });
        return app;
    }

    @org.junit.jupiter.api.AfterAll
    static void gapEvidence() throws Exception {
        if (!gapsPassed && !documentPassed && !pdfPassed && !previewPassed && !associationsPassed && !versionsPassed) return;
        GAP_OBSERVATIONS.getFirst().put("routes", GAP_ROUTES);
        Path root = Path.of(System.getProperty("maven.multiModuleProjectDirectory", ".."))
                .toAbsolutePath()
                .normalize();
        Process process = new ProcessBuilder(
                        "python",
                        "server/src/test/resources/contract/settings-evidence.py",
                        root.toString(),
                        "indexing-gaps")
                .directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        try (var output = process.getOutputStream()) {
            output.write(JSON.writeValueAsBytes(GAP_OBSERVATIONS));
        }
        String result = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(result).isZero();
    }

    private record Client(String base, String cookie, String token) {}
}
