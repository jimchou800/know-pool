package cn.knowpool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import cn.knowpool.api.QuestionController;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.PdfExtractionWorker;
import cn.knowpool.infrastructure.RuntimeSettings;
import cn.knowpool.infrastructure.UploadConfiguration;
import cn.knowpool.questions.Questions;
import cn.knowpool.search.SearchRequests;
import cn.knowpool.search.api.SearchController;
import cn.knowpool.system.api.LocalSessionFilter;
import cn.knowpool.system.api.TaskEventsController;
import jakarta.servlet.http.Cookie;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class BoundaryCoverageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "00000000-0000-4000-8000-000000000001";
    private static final String OTHER = "00000000-0000-4000-8000-000000000002";

    @Test
    @DisplayName("检索非法输入在调用业务层前拒绝")
    void rejectSearchInputsWithoutSideEffects() {
        SearchRequests service = mock(SearchRequests.class);
        SearchController controller = new SearchController(service);
        for (String body : List.of(
                "null",
                "{}",
                "{\"name\":1}",
                "{\"name\":\" \"}",
                JSON.createObjectNode().put("name", "字".repeat(121)).toString(),
                "{\"name\":\"名称\",\"extra\":1}")) {
            invalid(() -> controller.rename(ID, tree(body), request()));
        }
        for (String body : scopeBodies()) invalid(() -> controller.scope(ID, tree(body), request()));
        for (String body : List.of(
                "null",
                "{}",
                "{\"confirmed\":1}",
                "{\"confirmed\":false}",
                "{\"confirmed\":true,\"extra\":1}",
                "{\"confirmed\":true}")) {
            invalid(() -> controller.delete(ID, tree(body), request()));
        }
        for (String body : searchBodies()) {
            invalid(() -> controller.ordinary(ID, tree(body), request()));
            invalid(() -> controller.submit(ID, tree(body), request()));
        }
        invalid(() -> controller.submit(ID, query(JSON.createArrayNode().add(OTHER)), request()));
        invalid(() -> controller.submit("非法标识", query(JSON.createArrayNode().add(ID)), request()));
        MockHttpServletRequest duplicate = request();
        duplicate.addHeader("Idempotency-Key", "a");
        duplicate.addHeader("Idempotency-Key", "b");
        invalid(() -> controller.ordinary(ID, query(JSON.createArrayNode().add(ID)), duplicate));
        invalid(() -> controller.submit(ID, query(JSON.createArrayNode().add(ID)), duplicate));
        invalid(() -> controller.delete(ID, tree("{\"confirmed\":true}"), duplicate));
        invalid(() -> controller.create("{}", request()));
        invalid(() -> controller.cancel(ID, " ", request()));
        MockHttpServletRequest queried = request();
        queried.addParameter("extra", "1");
        invalid(() -> controller.instance(ID, queried));
        invalid(() -> controller.get(ID, queried));
        invalid(() -> controller.evidence(ID, ID, queried));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("问答非法范围名称与幂等键不产生会话或问题")
    void rejectQuestionInputsWithoutSideEffects() {
        Questions service = mock(Questions.class);
        TaskEventsController events = mock(TaskEventsController.class);
        QuestionController controller = new QuestionController(service, events);
        for (String body : List.of(
                "null",
                "{}",
                "{\"question\":\"问题\"}",
                "{\"knowledgeBaseIds\":[]}",
                "{\"question\":\"问题\",\"knowledgeBaseIds\":[],\"extra\":1}")) {
            invalid(() -> controller.first(tree(body), request()));
        }
        for (JsonNode text :
                List.of(tree("null"), tree("1"), JSON.valueToTree(" "), JSON.valueToTree("字".repeat(20001)))) {
            JsonNode body = JSON.createObjectNode().set("question", text);
            ((tools.jackson.databind.node.ObjectNode) body)
                    .set("knowledgeBaseIds", JSON.createArrayNode().add(ID));
            invalid(() -> controller.first(body, request()));
        }
        for (String body : scopeBodies()) {
            invalid(() -> controller.scope(ID, tree(body), request()));
        }
        for (JsonNode ids : List.of(
                tree("null"),
                tree("[]"),
                tree("[1]"),
                tree("[\"非法标识\"]"),
                JSON.createArrayNode().add(ID).add(ID),
                repeatedIds())) {
            JsonNode body = JSON.createObjectNode().put("question", "问题").set("knowledgeBaseIds", ids);
            invalid(() -> controller.first(body, request()));
        }
        for (JsonNode name :
                List.of(tree("null"), tree("1"), JSON.valueToTree(" "), JSON.valueToTree("字".repeat(121)))) {
            var body = JSON.createObjectNode().put("question", "问题");
            body.set("knowledgeBaseIds", JSON.createArrayNode().add(ID));
            body.set("manualName", name);
            invalid(() -> controller.first(body, request()));
            invalid(() -> controller.rename(ID, JSON.createObjectNode().set("name", name), request()));
        }
        invalid(() -> controller.rename(ID, tree("[]"), request()));
        invalid(() -> controller.rename(ID, tree("{}"), request()));
        invalid(() -> controller.rename(ID, tree("{\"wrong\":1}"), request()));
        var first = JSON.createObjectNode().put("question", "问题").put("manualName", "名称");
        first.set("knowledgeBaseIds", JSON.createArrayNode().add(ID));
        invalid(() -> controller.submit(ID, first, request()));
        first.remove("manualName");
        invalid(() -> controller.first(first, request()));
        MockHttpServletRequest duplicate = request();
        duplicate.addHeader("Idempotency-Key", "a");
        duplicate.addHeader("Idempotency-Key", "b");
        invalid(() -> controller.submit(ID, first, duplicate));
        for (String body : List.of("{}", "{\"confirmed\":1}", "{\"confirmed\":false}", "{\"confirmed\":true}")) {
            invalid(() -> controller.delete(ID, tree(body), request()));
        }
        invalid(() -> controller.cancel(ID, "{}", request()));
        MockHttpServletRequest queried = request();
        queried.addParameter("extra", "1");
        invalid(() -> controller.conversation(ID, queried));
        invalid(() -> controller.get(ID, queried));
        invalid(() -> controller.evidence(ID, ID, queried));
        verifyNoInteractions(service, events);
    }

    @Test
    @DisplayName("检索和会话分页拒绝重复未知越界参数并保留合法边界")
    void validatePaginationAndUnicodeBoundaries() {
        SearchRequests searches = mock(SearchRequests.class);
        Questions questions = mock(Questions.class);
        SearchController search = new SearchController(searches);
        QuestionController question = new QuestionController(questions, mock(TaskEventsController.class));
        for (String value : List.of("", "-1", "0", "101", "1.5", "2147483648", "a")) {
            MockHttpServletRequest request = request();
            request.addParameter("limit", value);
            invalid(() -> search.instances(request));
            invalid(() -> question.conversations(request));
            invalid(() -> question.turns(ID, request));
        }
        for (String cursor : List.of("", "字".repeat(2049))) {
            MockHttpServletRequest request = request();
            request.addParameter("cursor", cursor);
            invalid(() -> search.instances(request));
            invalid(() -> question.conversations(request));
        }
        for (String parameter : List.of("extra", "limit", "cursor")) {
            MockHttpServletRequest request = request();
            request.addParameter(parameter, "1", "2");
            invalid(() -> search.instances(request));
            invalid(() -> question.conversations(request));
        }
        verifyNoInteractions(searches, questions);
        search.instances(request());
        question.conversations(request());
        verify(searches).listInstances(20, null);
        verify(questions).conversations(20, null);
        MockHttpServletRequest maximum = request();
        maximum.addParameter("limit", "100");
        maximum.addParameter("cursor", "位置");
        search.instances(maximum);
        question.turns(ID, maximum);
        verify(searches).listInstances(100, "位置");
        verify(questions).turns(ID, 100, "位置");
        String name = "😀".repeat(120);
        search.rename(ID, JSON.createObjectNode().put("name", name), request());
        question.rename(ID, JSON.createObjectNode().put("name", name), request());
        verify(searches).renameInstance(ID, name);
        verify(questions).rename(ID, name);
        var empty = JSON.createArrayNode();
        search.scope(ID, JSON.createObjectNode().set("knowledgeBaseIds", empty), request());
        question.scope(ID, JSON.createObjectNode().set("knowledgeBaseIds", empty), request());
        verify(searches).updateScope(ID, empty);
        verify(questions).updateScope(ID, empty);
    }

    @Test
    @DisplayName("合法码点上限与唯一幂等键完整传入业务层")
    void preserveAcceptedPayloadAndSessionIdentity() {
        SearchRequests searches = mock(SearchRequests.class);
        Questions questions = mock(Questions.class);
        SearchController search = new SearchController(searches);
        QuestionController question = new QuestionController(questions, mock(TaskEventsController.class));
        MockHttpServletRequest request = request();
        request.addHeader("Idempotency-Key", "唯一请求");
        var ids = JSON.createArrayNode().add(ID);
        var searchInput = JSON.createObjectNode().put("query", "😀".repeat(20000));
        searchInput.set("knowledgeBaseIds", ids);
        search.ordinary(ID, searchInput, request);
        search.submit(ID, searchInput, request);
        search.delete(ID, tree("{\"confirmed\":true}"), request);
        verify(searches).submitInstance(ID, ID, "唯一请求", searchInput);
        verify(searches).submit(ID, ID, "唯一请求", searchInput);
        verify(searches).deleteInstance(ID, ID, "唯一请求");
        var questionInput = JSON.createObjectNode().put("question", "😀".repeat(20000));
        questionInput.set("knowledgeBaseIds", ids);
        var firstInput = questionInput.deepCopy().put("manualName", "😀".repeat(120));
        question.first(firstInput, request);
        question.submit(ID, questionInput, request);
        question.delete(ID, tree("{\"confirmed\":true}"), request);
        verify(questions).first(ID, "唯一请求", firstInput);
        verify(questions).submit(ID, ID, "唯一请求", questionInput);
        verify(questions).delete(ID, ID, "唯一请求");
        search.cancel(ID, "", request);
        question.cancel(ID, null, request);
        verify(searches).cancelRequest(ID);
        verify(questions).cancel(ID);
    }

    @Test
    @DisplayName("会话路由保护拒绝未声明方法并区分读取与写入保护")
    void enforceSessionRoutingAndCsrf() throws Exception {
        LocalSessionFilter filter = new LocalSessionFilter("http://localhost:9090", JSON);
        MockHttpServletRequest initialize = localRequest("GET", "/api/v1/session");
        MockHttpServletResponse initialized = new MockHttpServletResponse();
        filter.doFilter(initialize, initialized, (request, response) -> {});
        var session = (LocalSessionFilter.Session) initialize.getAttribute("localSession");
        assertThat(session).isNotNull();
        Cookie cookie = new Cookie("KNOW_POOL_SESSION", session.sessionId());
        for (String path : List.of(
                "/datasets",
                "/datasets/" + ID,
                "/datasets/" + ID + "/files:upload",
                "/files/" + ID,
                "/files/" + ID + "/latest/download",
                "/files/" + ID + "/name",
                "/files/" + ID + "/versions",
                "/files/" + ID + "/versions/" + ID + "/preview",
                "/settings/runtime",
                "/settings/models/generation",
                "/settings/vector-store",
                "/settings/models/embedding/versions",
                "/settings/models/embedding/versions/" + ID + "/credential",
                "/settings/models/generation/test",
                "/settings/vector-store/test",
                "/tasks/" + ID,
                "/tasks/" + ID + "/chunk-preflight",
                "/knowledge-bases/" + ID + "/processing-config",
                "/knowledge-bases",
                "/knowledge-bases/" + ID,
                "/knowledge-bases/" + ID + "/sources",
                "/knowledge-bases/" + ID + "/sources/" + ID,
                "/knowledge-bases/" + ID + "/exclusions/" + ID,
                "/files/" + ID + "/knowledge-bases",
                "/knowledge-bases/" + ID + "/files/" + ID + "/retry",
                "/knowledge-bases/" + ID + "/processing-config/versions/" + ID,
                "/knowledge-bases/" + ID + "/publish",
                "/knowledge-bases/" + ID + "/search-test",
                "/search-requests/" + ID,
                "/tasks/" + ID + "/cancel",
                "/conversations/first-question",
                "/question-tasks/" + ID,
                "/question-tasks/" + ID + "/cancel",
                "/conversations/" + ID + "/evidence/" + ID,
                "/conversations",
                "/conversations/" + ID,
                "/conversations/" + ID + "/name",
                "/conversations/" + ID + "/scope",
                "/conversations/" + ID + "/turns",
                "/conversations/" + ID + "/questions",
                "/search-instances",
                "/search-instances/" + ID,
                "/search-instances/" + ID + "/name",
                "/search-instances/" + ID + "/scope",
                "/search-instances/" + ID + "/requests",
                "/search-requests/" + ID + "/cancel",
                "/files/" + ID + "/delete-impact",
                "/tasks",
                "/tasks/" + ID + "/retry-cleanup",
                "/maintenance/backup")) {
            MockHttpServletRequest request = localRequest("TRACE", "/api/v1" + path);
            request.setCookies(cookie);
            request.addHeader("X-CSRF-Token", session.csrfToken());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("未声明方法不得进入业务链");
            });
            error(response, 404, "NOT_FOUND");
        }
        for (String token : List.of("", "失效保护令牌")) {
            MockHttpServletRequest request = localRequest("POST", "/api/v1/search-instances");
            request.setCookies(cookie);
            if (!token.isEmpty()) request.addHeader("X-CSRF-Token", token);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("缺失保护信息不得产生写入");
            });
            error(response, 403, "CSRF_REJECTED");
        }
        MockHttpServletRequest allowed = localRequest("GET", "/assets/main.js");
        allowed.addHeader("Origin", "http://localhost:9090");
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(allowed, new MockHttpServletResponse(), (req, res) -> called.set(true));
        assertThat(called).isTrue();
        for (String host : List.of("localhost,evil", "user@localhost", "localhost:80")) {
            MockHttpServletRequest request = localRequest("GET", "/api/v1/session");
            request.removeHeader("Host");
            request.addHeader("Host", host);
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("非法主机不得进入业务链");
            });
            error(response, 403, "ORIGIN_REJECTED");
        }
    }

    @Test
    @DisplayName("PDF工作进程保留页映射并对损坏内容与文字上限返回确定结果")
    void validatePdfWorkerExitAndPageMapping(@TempDir Path directory) throws Exception {
        Path input = directory.resolve("source.pdf");
        Path output = directory.resolve("pages.bin");
        assertThat(PdfExtractionWorker.run(new String[0])).isEqualTo(2);
        Files.writeString(input, "损坏内容");
        assertThat(PdfExtractionWorker.run(pdfArgs(input, output, "1000"))).isEqualTo(2);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700);
                content.showText("1234567890");
                content.endText();
            }
            document.save(input.toFile());
        }
        assertThat(PdfExtractionWorker.run(pdfArgs(input, output, "1000"))).isZero();
        try (var stream = new java.io.DataInputStream(Files.newInputStream(output))) {
            assertThat(stream.readInt()).isEqualTo(2);
            boolean foundText = false;
            while (stream.available() > 0) {
                int page = stream.readInt();
                String text = new String(stream.readNBytes(stream.readInt()), java.nio.charset.StandardCharsets.UTF_8);
                if (page == 1) assertThat(text).isBlank();
                else {
                    assertThat(page).isEqualTo(2);
                    assertThat(text).contains("1234567890");
                    foundText = true;
                }
            }
            assertThat(foundText).isTrue();
        }
        assertThat(PdfExtractionWorker.run(pdfArgs(input, output, "1"))).isEqualTo(3);
        assertThat(PdfExtractionWorker.run(pdfArgs(input, output, "10"))).isEqualTo(3);
        assertThat(PdfExtractionWorker.run(pdfArgs(input, output, "非法上限"))).isEqualTo(2);
    }

    @Test
    @DisplayName("运行参数拒绝非法限流结构且嵌套捕获保持既有快照")
    void validateRuntimeLimitsAndNestedSnapshots(@TempDir Path directory) throws Exception {
        try (ApplicationData data = new ApplicationData(directory.toString())) {
            RuntimeSettings settings = new RuntimeSettings(data, JSON, new MockEnvironment());
            try {
                var initial = settings.get();
                invalid(() -> settings.update(null, () -> {}));
                invalid(() -> settings.update(tree("[]"), () -> {}));
                var unknown = initial.deepCopy();
                unknown.remove("queueCapacity");
                unknown.put("unknown", 1);
                invalid(() -> settings.update(unknown, () -> {}));
                var many = JSON.createArrayNode();
                for (int i = 0; i < 101; i++) many.addObject();
                for (JsonNode rates : List.of(
                        tree("null"),
                        tree("{}"),
                        many,
                        tree("[null]"),
                        tree("[{}]"),
                        tree(
                                "[{\"endpoint\":1,\"model\":\"模型\",\"maxConcurrent\":1,\"requestsPerMinute\":1,\"tokensPerMinute\":1}]"),
                        tree(
                                "[{\"endpoint\":\"http://localhost\",\"model\":1,\"maxConcurrent\":1,\"requestsPerMinute\":1,\"tokensPerMinute\":1}]"),
                        tree(
                                "[{\"endpoint\":\"http://localhost\",\"model\":\" \",\"maxConcurrent\":1,\"requestsPerMinute\":1,\"tokensPerMinute\":1}]"))) {
                    var candidate = initial.deepCopy();
                    candidate.set("modelRateLimits", rates);
                    invalid(() -> settings.update(candidate, () -> {}));
                    assertThat(settings.get()).isEqualTo(initial);
                }
                for (JsonNode value :
                        List.<JsonNode>of(JSON.valueToTree("非数字"), JSON.valueToTree(Double.POSITIVE_INFINITY))) {
                    var candidate = initial.deepCopy();
                    candidate.set("rrfConstant", value);
                    invalid(() -> settings.update(candidate, () -> {}));
                }
                var oversized = initial.deepCopy();
                oversized.set(
                        "queueCapacity", JSON.valueToTree(new java.math.BigInteger("9999999999999999999999999999")));
                invalid(() -> settings.update(oversized, () -> {}));
                AtomicBoolean changed = new AtomicBoolean();
                try (var outer = settings.capture()) {
                    double original = settings.decimal("rrfConstant");
                    var next = initial.deepCopy().put("rrfConstant", original + 0.5);
                    settings.update(next, () -> changed.set(true));
                    assertThat(changed).isFalse();
                    assertThat(settings.decimal("rrfConstant")).isEqualTo(original);
                    try (var inner = settings.capture()) {
                        assertThat(settings.decimal("rrfConstant")).isEqualTo(original + 0.5);
                    }
                    assertThat(settings.decimal("rrfConstant")).isEqualTo(original);
                }
                assertThat(settings.decimal("rrfConstant"))
                        .isEqualTo(initial.path("rrfConstant").asDouble() + 0.5);
                var exported = settings.get();
                exported.put("queueCapacity", 999);
                assertThat(settings.number("queueCapacity"))
                        .isEqualTo(initial.path("queueCapacity").asLong());
            } finally {
                settings.close();
            }
        }
    }

    @Test
    @DisplayName("限流端点拒绝隐含凭据与歧义地址并统一标准端口")
    void normalizeRateLimitEndpoints() {
        for (String endpoint : List.of(
                "localhost/path",
                "ftp://localhost",
                "http:/missing",
                "http://u:p@localhost",
                "http://localhost?query=1",
                "http://localhost#fragment",
                "http://[")) {
            invalid(() -> RuntimeSettings.endpoint(endpoint));
        }
        assertThat(RuntimeSettings.endpoint("HTTP://LOCALHOST:80/a/../v1///")).isEqualTo("http://localhost/v1");
        assertThat(RuntimeSettings.endpoint("HTTPS://LOCALHOST:443/")).isEqualTo("https://localhost");
        assertThat(RuntimeSettings.endpoint("http://localhost:443/")).isEqualTo("http://localhost:443");
        assertThat(RuntimeSettings.endpoint("https://localhost:80/")).isEqualTo("https://localhost:80");
    }

    @Test
    @DisplayName("状态读取异常只在未提交时写统一错误且会话读取遵守方法保护")
    void preserveCommittedStatusErrorsAndSafeOrigins() throws Exception {
        LocalSessionFilter filter = new LocalSessionFilter("http://localhost:9090", JSON);
        MockHttpServletRequest initialize = localRequest("GET", "/api/v1/session");
        filter.doFilter(initialize, new MockHttpServletResponse(), (request, response) -> {});
        var session = (LocalSessionFilter.Session) initialize.getAttribute("localSession");
        Cookie cookie = new Cookie("KNOW_POOL_SESSION", session.sessionId());
        for (boolean committed : List.of(false, true)) {
            MockHttpServletRequest request = localRequest("GET", "/api/v1/system/status");
            request.setCookies(new Cookie("unrelated", "忽略"), cookie);
            MockHttpServletResponse response = new MockHttpServletResponse();
            jakarta.servlet.ServletException failure = new jakarta.servlet.ServletException("受控状态读取失败");
            jakarta.servlet.FilterChain chain = (req, res) -> {
                if (committed) res.flushBuffer();
                throw failure;
            };
            if (committed)
                assertThat(assertThrows(
                                jakarta.servlet.ServletException.class,
                                () -> filter.doFilter(request, response, chain)))
                        .isSameAs(failure);
            else {
                filter.doFilter(request, response, chain);
                assertThat(response.getStatus()).isEqualTo(500);
                JsonNode body = JSON.readTree(response.getContentAsByteArray());
                assertThat(body.path("code").asText()).isEqualTo("INTERNAL_ERROR");
                assertThat(body.path("retryable").asBoolean()).isTrue();
                assertThat(body.path("message").asText()).doesNotContain(failure.getMessage());
            }
        }
        for (String path : List.of("/api/v1/session", "/api/v1/system/status")) {
            MockHttpServletRequest request = localRequest("POST", path);
            request.setCookies(cookie);
            request.addHeader("X-CSRF-Token", session.csrfToken());
            MockHttpServletResponse response = new MockHttpServletResponse();
            filter.doFilter(request, response, (req, res) -> {
                throw new AssertionError("读取入口不能通过写方法调用");
            });
            error(response, 404, "NOT_FOUND");
        }
        for (String host : List.of("[::1]:8080", "localhost:9090", "localhost")) {
            MockHttpServletRequest request = localRequest("GET", "/assets/main.js");
            request.removeHeader("Host");
            request.addHeader("Host", host);
            if (host.equals("localhost")) request.setLocalPort(80);
            AtomicBoolean reached = new AtomicBoolean();
            filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> reached.set(true));
            assertThat(reached).isTrue();
        }
    }

    @Test
    @DisplayName("PDF隔离提取拒绝损坏空白加密与超限内容并清理临时文件")
    void rejectUnsafePdfExtraction(@TempDir Path directory) throws Exception {
        var limits = new UploadConfiguration.Limits(1000000, 1000000, 1, 10, 1000);
        Path extraction = directory.resolve("extraction");
        Path occupied = directory.resolve("occupied");
        Files.writeString(occupied, "普通文件不能作为解析目录");
        assertThrows(java.io.IOException.class, () -> PdfExtractionWorker.extract(new byte[] {1}, occupied, limits));
        assertThat(Files.readString(occupied)).isEqualTo("普通文件不能作为解析目录");
        ApiFailure corrupt = assertThrows(
                ApiFailure.class, () -> PdfExtractionWorker.extract(new byte[] {1, 2, 3}, extraction, limits));
        assertThat(corrupt.code()).isEqualTo("PARSING_FAILED");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(bytes);
        }
        ApiFailure blank = assertThrows(
                ApiFailure.class, () -> PdfExtractionWorker.extract(bytes.toByteArray(), extraction, limits));
        assertThat(blank.code()).isEqualTo("PARSING_FAILED");
        Path encrypted = directory.resolve("encrypted.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy(
                    "所有者密码", "", new org.apache.pdfbox.pdmodel.encryption.AccessPermission()));
            document.save(encrypted.toFile());
        }
        assertThat(PdfExtractionWorker.run(pdfArgs(encrypted, directory.resolve("encrypted.bin"), "1000")))
                .isEqualTo(2);
        try (var files = Files.list(extraction)) {
            assertThat(files).isEmpty();
        }
    }

    private static String[] pdfArgs(Path input, Path output, String maximum) {
        return new String[] {PdfExtractionWorker.INTERNAL_MODE, input.toString(), output.toString(), maximum};
    }

    private static List<String> scopeBodies() {
        return List.of(
                "null",
                "{}",
                "{\"knowledgeBaseIds\":null}",
                "{\"knowledgeBaseIds\":[1]}",
                "{\"knowledgeBaseIds\":[\"非法标识\"]}",
                "{\"knowledgeBaseIds\":[\"" + ID + "\",\"" + ID + "\"]}",
                JSON.createObjectNode().set("knowledgeBaseIds", repeatedIds()).toString(),
                "{\"knowledgeBaseIds\":[],\"extra\":1}");
    }

    private static List<String> searchBodies() {
        var bodies = new java.util.ArrayList<String>(List.of(
                "null",
                "{}",
                "{\"query\":\"问题\"}",
                "{\"wrong\":1,\"knowledgeBaseIds\":[]}",
                "{\"query\":\"问题\",\"wrong\":[]}"));
        for (JsonNode value : List.of(tree("1"), JSON.valueToTree(" "), JSON.valueToTree("字".repeat(20001)))) {
            var body = JSON.createObjectNode();
            body.set("query", value);
            body.set("knowledgeBaseIds", JSON.createArrayNode().add(ID));
            bodies.add(body.toString());
        }
        for (JsonNode ids : List.of(
                tree("null"),
                tree("[]"),
                tree("[1]"),
                tree("[\"非法标识\"]"),
                JSON.createArrayNode().add(ID).add(ID),
                repeatedIds(),
                JSON.createArrayNode().add(ID))) {
            bodies.add(query(ids).toString());
        }
        return bodies;
    }

    private static JsonNode repeatedIds() {
        var ids = JSON.createArrayNode();
        for (int i = 0; i < 101; i++) ids.add(ID);
        return ids;
    }

    private static JsonNode query(JsonNode ids) {
        return JSON.createObjectNode().put("query", "问题").set("knowledgeBaseIds", ids);
    }

    private static JsonNode tree(String body) {
        return JSON.readTree(body);
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("localSession", new LocalSessionFilter.Session(ID, "保护令牌", "2099-01-01T00:00:00Z"));
        return request;
    }

    private static MockHttpServletRequest localRequest(String method, String path) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setLocalPort(8080);
        request.addHeader("Host", "localhost:8080");
        return request;
    }

    private static void invalid(Executable operation) {
        ApiFailure failure = assertThrows(ApiFailure.class, operation);
        assertThat(failure.status()).isEqualTo(400);
        assertThat(failure.code()).isEqualTo("INVALID_REQUEST");
        assertThat(failure.getMessage()).isNotBlank();
    }

    private static void error(MockHttpServletResponse response, int status, String code) throws Exception {
        assertThat(response.getStatus()).isEqualTo(status);
        JsonNode body = JSON.readTree(response.getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(body.path("code").asText()).isEqualTo(code);
        assertThat(body.path("requestId").asText()).isEqualTo(response.getHeader("X-Request-Id"));
        assertThat(body.path("fieldErrors").isArray()).isTrue();
        assertThat(body.path("retryable").asBoolean()).isFalse();
    }
}
