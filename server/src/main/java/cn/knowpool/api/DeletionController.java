package cn.knowpool.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.Deletions;
import cn.knowpool.system.api.LocalSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public final class DeletionController {
    private final Deletions deletions;

    public DeletionController(Deletions deletions) {
        this.deletions = deletions;
    }

    @GetMapping("/api/v1/datasets/{datasetId}/delete-impact")
    public JsonNode datasetImpact(@PathVariable String datasetId, HttpServletRequest request) {
        noQuery(request);
        return deletions.impact("dataset", datasetId);
    }

    @GetMapping("/api/v1/files/{fileId}/delete-impact")
    public JsonNode fileImpact(@PathVariable String fileId, HttpServletRequest request) {
        noQuery(request);
        return deletions.impact("file", fileId);
    }

    @GetMapping("/api/v1/knowledge-bases/{knowledgeBaseId}/delete-impact")
    public JsonNode baseImpact(@PathVariable String knowledgeBaseId, HttpServletRequest request) {
        noQuery(request);
        return deletions.impact("knowledge_base", knowledgeBaseId);
    }

    @DeleteMapping(value = "/api/v1/datasets/{datasetId}", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode dataset(@PathVariable String datasetId, @RequestBody JsonNode body, HttpServletRequest request) {
        return delete("dataset", datasetId, body, request);
    }

    @DeleteMapping(value = "/api/v1/files/{fileId}", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode file(@PathVariable String fileId, @RequestBody JsonNode body, HttpServletRequest request) {
        return delete("file", fileId, body, request);
    }

    @DeleteMapping(value = "/api/v1/knowledge-bases/{knowledgeBaseId}", consumes = "application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode base(@PathVariable String knowledgeBaseId, @RequestBody JsonNode body, HttpServletRequest request) {
        return delete("knowledge_base", knowledgeBaseId, body, request);
    }

    private JsonNode delete(String kind, String id, JsonNode body, HttpServletRequest request) {
        noQuery(request);
        if (!body.isObject()
                || body.size() != 1
                || !body.path("confirmed").isBoolean()
                || !body.path("confirmed").asBoolean()) throw ApiFailure.invalid("/confirmed", "删除须明确确认且不得添加其他字段。");
        return deletions.delete(kind, id, session(request).sessionId(), key(request));
    }

    @PostMapping("/api/v1/tasks/{taskId}/retry-cleanup")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode retry(
            @PathVariable String taskId, @RequestBody(required = false) String body, HttpServletRequest request) {
        noQuery(request);
        if (body != null && !body.isEmpty()) throw ApiFailure.invalid(null, "重试清理不接受正文。");
        return deletions.retry(taskId, session(request).sessionId(), key(request));
    }

    @GetMapping("/api/v1/tasks")
    public Deletions.Page tasks(HttpServletRequest request) {
        for (var item : request.getParameterMap().entrySet())
            if (!Set.of("limit", "cursor", "kind", "state").contains(item.getKey()) || item.getValue().length != 1)
                throw ApiFailure.invalid(item.getKey(), "查询参数未知或重复。");
        int limit = 20;
        String value = request.getParameter("limit");
        try {
            if (value != null) {
                if (!value.matches("[0-9]+")) throw new NumberFormatException();
                limit = Integer.parseInt(value);
                if (limit < 1 || limit > 100) throw new NumberFormatException();
            }
        } catch (NumberFormatException failure) {
            throw ApiFailure.invalid("limit", "每页数量须为一至一百的整数。");
        }
        String cursor = request.getParameter("cursor"),
                kind = request.getParameter("kind"),
                state = request.getParameter("state");
        if (cursor != null && (cursor.isEmpty() || cursor.length() > 2048))
            throw ApiFailure.invalid("cursor", "分页位置长度无效。");
        if (kind != null
                && !Set.of(
                                "index_file",
                                "rebuild_knowledge_base",
                                "cleanup",
                                "knowledge_base_create",
                                "search",
                                "question",
                                "model_test",
                                "migration",
                                "backup",
                                "restore")
                        .contains(kind)) throw ApiFailure.invalid("kind", "任务类型无效。");
        if (state != null
                && !Set.of("queued", "running", "succeeded", "failed", "cancelled", "interrupted")
                        .contains(state)) throw ApiFailure.invalid("state", "任务状态无效。");
        return deletions.tasks(limit, cursor, kind, state);
    }

    private static void noQuery(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null, "此操作不接受查询参数。");
    }

    private static String key(HttpServletRequest request) {
        var values = java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (values.size() != 1) throw ApiFailure.invalid("Idempotency-Key", "须提供唯一幂等键。");
        return values.getFirst();
    }

    private static LocalSessionFilter.Session session(HttpServletRequest request) {
        return (LocalSessionFilter.Session) request.getAttribute("localSession");
    }
}
