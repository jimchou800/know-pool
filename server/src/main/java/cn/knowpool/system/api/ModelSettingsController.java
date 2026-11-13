package cn.knowpool.system.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.system.ConnectionTests;
import cn.knowpool.system.ModelSettingsService;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class ModelSettingsController {
    private final ModelSettingsService service;
    private final ConnectionTests tests;

    public ModelSettingsController(ModelSettingsService service, ConnectionTests tests) {
        this.service = service;
        this.tests = tests;
    }

    @PostMapping(
            value = {"/api/v1/settings/models/generation/test", "/api/v1/settings/models/embedding/test"},
            consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionTests.Accepted testModel(@RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        object(input, Set.of("allowChargedCall"), "");
        if (!input.get("allowChargedCall").isBoolean()
                || !input.get("allowChargedCall").asBoolean())
            throw ApiFailure.invalid("/allowChargedCall", "须明确同意连接测试可能产生费用。");
        return accept(
                request.getRequestURI().contains("/generation/") ? "generation" : "embedding",
                request,
                "{\"allowChargedCall\":true}");
    }

    @PostMapping("/api/v1/settings/vector-store/test")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ConnectionTests.Accepted testVector(HttpServletRequest request) {
        noQuery(request);
        try {
            if (request.getInputStream().read() != -1) throw ApiFailure.invalid(null, "向量连接测试不接受请求正文。");
        } catch (java.io.IOException failure) {
            throw ApiFailure.invalid(null, "请求正文无法读取。");
        }
        return accept("vector", request, "");
    }

    private ConnectionTests.Accepted accept(String kind, HttpServletRequest request, String fingerprint) {
        java.util.List<String> keys = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        LocalSessionFilter.Session session = (LocalSessionFilter.Session) request.getAttribute("localSession");
        return new ConnectionTests.Accepted(tests.accept(kind, session.sessionId(), keys.getFirst(), fingerprint));
    }

    @GetMapping("/api/v1/tasks/{taskId}")
    public ConnectionTests.Task task(@PathVariable String taskId, HttpServletRequest request) {
        noQuery(request);
        return tests.get(taskId);
    }

    @GetMapping("/api/v1/tasks/{taskId}/connection-test")
    public ConnectionTests.Result result(@PathVariable String taskId, HttpServletRequest request) {
        noQuery(request);
        return tests.result(taskId);
    }

    @GetMapping({"/api/v1/settings/models/generation", "/api/v1/settings/models/embedding"})
    public ModelSettingsService.ModelConfig model(HttpServletRequest request) {
        noQuery(request);
        return service.current(request.getRequestURI().endsWith("/generation") ? "generation" : "embedding");
    }

    @GetMapping("/api/v1/settings/vector-store")
    public ModelSettingsService.VectorConfig vector(HttpServletRequest request) {
        noQuery(request);
        return service.vector();
    }

    @PutMapping(
            value = {"/api/v1/settings/models/generation", "/api/v1/settings/models/embedding"},
            consumes = "application/json")
    public ModelSettingsService.ModelConfig save(@RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        String kind = request.getRequestURI().endsWith("/generation") ? "generation" : "embedding";
        object(input, Set.of("endpoint", "model", "credential", "contextWindowTokens", "maxOutputTokens"), "");
        String endpoint = endpoint(input, true);
        String model = text(input, "model", 1, 200, "");
        if (!model.codePoints().anyMatch(value -> !Character.isWhitespace(value) && !Character.isSpaceChar(value)))
            throw ApiFailure.invalid("/model", "模型标识不能仅为空白。");
        Long context = budget(input, "contextWindowTokens", kind.equals("generation"));
        Long output = budget(input, "maxOutputTokens", kind.equals("generation"));
        return service.save(kind, endpoint, model, context, output, credential(input.get("credential"), "/credential"));
    }

    @PutMapping(value = "/api/v1/settings/vector-store", consumes = "application/json")
    public ModelSettingsService.VectorConfig saveVector(@RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        object(input, Set.of("endpoint", "credential"), "");
        return service.saveVector(endpoint(input, false), credential(input.get("credential"), "/credential"));
    }

    @GetMapping("/api/v1/settings/models/embedding/versions/{modelConfigVersionId}")
    public ModelSettingsService.ModelConfig historical(
            @PathVariable String modelConfigVersionId, HttpServletRequest request) {
        noQuery(request);
        return service.historical(modelConfigVersionId);
    }

    @PutMapping(
            value = "/api/v1/settings/models/embedding/versions/{modelConfigVersionId}/credential",
            consumes = "application/json")
    public ModelSettingsService.ModelConfig credential(
            @PathVariable String modelConfigVersionId, @RequestBody JsonNode input, HttpServletRequest request) {
        noQuery(request);
        return service.credential(modelConfigVersionId, credential(input, ""));
    }

    @GetMapping("/api/v1/settings/models/embedding/versions")
    public ModelSettingsService.ModelPage versions(HttpServletRequest request) {
        request.getParameterMap().forEach((name, values) -> {
            if (!Set.of("limit", "cursor").contains(name) || values.length != 1)
                throw ApiFailure.invalid(name, "查询参数未声明或重复。");
        });
        int limit = 20;
        if (request.getParameter("limit") != null) {
            try {
                String value = request.getParameter("limit");
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                limit = Integer.parseInt(value);
                if (limit < 1 || limit > 100) throw new NumberFormatException();
            } catch (NumberFormatException failure) {
                throw ApiFailure.invalid("limit", "每页数量须为一至一百。");
            }
        }
        String cursor = request.getParameter("cursor");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw ApiFailure.invalid("cursor", "分页位置长度不正确。");
        return service.versions(limit, cursor);
    }

    private static Long budget(JsonNode input, String field, boolean generation) {
        JsonNode value = input.get(field);
        if (generation) {
            if (!value.isIntegralNumber()
                    || !value.canConvertToLong()
                    || value.longValue() < 1
                    || value.longValue() > 9007199254740991L) throw ApiFailure.invalid("/" + field, "生成预算须为安全范围内的正整数。");
            return value.longValue();
        }
        if (!value.isNull()) throw ApiFailure.invalid("/" + field, "嵌入配置的生成预算须为空。");
        return null;
    }

    private static String endpoint(JsonNode input, boolean model) {
        String value = text(input, "endpoint", 1, Integer.MAX_VALUE, "");
        try {
            URI uri = URI.create(value);
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            if (!Set.of("http", "https").contains(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getHost().isEmpty()
                    || uri.getUserInfo() != null
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getPort() > 65535
                    || uri.getPort() == 0
                    || model && (path.endsWith("/chat/completions") || path.endsWith("/embeddings")))
                throw new IllegalArgumentException();
            return value;
        } catch (IllegalArgumentException failure) {
            throw ApiFailure.invalid("/endpoint", "地址须为合法的连接基础地址，不含用户信息、查询或片段。");
        }
    }

    private static ModelSettingsService.CredentialChange credential(JsonNode input, String prefix) {
        if (input == null
                || !input.isObject()
                || !input.has("action")
                || !input.get("action").isString()) throw ApiFailure.invalid(prefix + "/action", "须明确选择保持、替换或清除凭据。");
        String action = input.get("action").asText();
        if (!Set.of("keep", "replace", "clear").contains(action))
            throw ApiFailure.invalid(prefix + "/action", "凭据动作不正确。");
        object(input, action.equals("replace") ? Set.of("action", "value") : Set.of("action"), prefix);
        String value = action.equals("replace") ? text(input, "value", 1, 8192, prefix) : null;
        if ("【密钥已设置】".equals(value)) throw ApiFailure.invalid(prefix + "/value", "显示掩码不能作为真实密钥保存。");
        return new ModelSettingsService.CredentialChange(action, value);
    }

    private static String text(JsonNode input, String field, int min, int max, String prefix) {
        JsonNode value = input.get(field);
        if (value == null || !value.isString()) throw ApiFailure.invalid(prefix + "/" + field, "字段须为字符串。");
        String result = value.asText();
        int length = result.codePointCount(0, result.length());
        if (length < min || length > max) throw ApiFailure.invalid(prefix + "/" + field, "字段长度不符合约定。");
        return result;
    }

    private static void object(JsonNode input, Set<String> fields, String prefix) {
        if (input == null || !input.isObject()) throw ApiFailure.invalid(prefix, "请求须为字段对象。");
        for (String name : input.propertyNames()) {
            if (!fields.contains(name))
                throw ApiFailure.invalid(prefix + "/" + name.replace("~", "~0").replace("/", "~1"), "包含未声明字段。");
        }
        for (String name : fields) if (!input.has(name)) throw ApiFailure.invalid(prefix + "/" + name, "缺少必填字段。");
    }

    private static void noQuery(HttpServletRequest request) {
        if (request.getQueryString() != null)
            throw ApiFailure.invalid(
                    request.getParameterNames().hasMoreElements()
                            ? request.getParameterNames().nextElement()
                            : "query",
                    "该操作不接受查询参数。");
    }
}
