package cn.knowpool;

import java.net.URI;
import java.net.http.*;
import java.util.Set;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** 固定模型场景显式提供本机限流，不给生产环境添加隐式供应商额度。 */
final class RuntimeTestFixtures {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    static void allowSavedModel(
            HttpClient http,
            String base,
            String path,
            String method,
            String body,
            String cookie,
            String token,
            int status)
            throws Exception {
        if (status != 200
                || !method.equals("PUT")
                || !Set.of("/settings/models/generation", "/settings/models/embedding")
                        .contains(path)) return;
        var model = JSON.readTree(body);
        if (!model.path("endpoint").isTextual() || !model.path("model").isTextual()) return;
        if (model.path("contextWindowTokens").isIntegralNumber()
                && model.path("contextWindowTokens").asLong()
                                - model.path("maxOutputTokens").asLong()
                                - 4096
                        < 2) return;
        var response = http.send(
                HttpRequest.newBuilder(URI.create(base + "/settings/runtime"))
                        .header("Cookie", cookie)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new AssertionError("固定模型限流初值读取失败。");
        ObjectNode settings = (ObjectNode) JSON.readTree(response.body());
        var rates = (tools.jackson.databind.node.ArrayNode) settings.path("modelRateLimits");
        String endpoint = model.path("endpoint").asText(),
                name = model.path("model").asText();
        if (rates.valueStream()
                .noneMatch(item -> cn.knowpool.infrastructure.RuntimeSettings.endpoint(
                                        item.path("endpoint").asText())
                                .equals(cn.knowpool.infrastructure.RuntimeSettings.endpoint(endpoint))
                        && item.path("model").asText().equals(name)))
            rates.addObject()
                    .put("endpoint", endpoint)
                    .put("model", name)
                    .put("maxConcurrent", 32)
                    .put("requestsPerMinute", 100000)
                    .put("tokensPerMinute", 9007199254740991L);
        if (model.path("contextWindowTokens").isIntegralNumber()) {
            long available = Math.max(
                    2,
                    model.path("contextWindowTokens").asLong()
                            - model.path("maxOutputTokens").asLong()
                            - 4096);
            if (settings.path("contextEvidenceTokenBudget").asLong()
                            + settings.path("historyContextTokenBudget").asLong()
                    > available)
                settings.put("contextEvidenceTokenBudget", available / 2)
                        .put("historyContextTokenBudget", available - available / 2);
        }
        var saved = http.send(
                HttpRequest.newBuilder(URI.create(base + "/settings/runtime"))
                        .header("Cookie", cookie)
                        .header("X-CSRF-Token", token)
                        .header("Content-Type", "application/json")
                        .PUT(HttpRequest.BodyPublishers.ofString(settings.toString()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        if (saved.statusCode() != 200) throw new AssertionError("固定模型限流配置失败。");
    }
}
