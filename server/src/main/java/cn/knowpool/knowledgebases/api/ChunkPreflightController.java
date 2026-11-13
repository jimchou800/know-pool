package cn.knowpool.knowledgebases.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.Indexes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;

@RestController
public final class ChunkPreflightController {
    private final Indexes indexes;
    public ChunkPreflightController(Indexes indexes) { this.indexes = indexes; }
    @GetMapping("/api/v1/tasks/{taskId}/chunk-preflight")
    public JsonNode get(@PathVariable String taskId, HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null, "预检读取不接受查询参数。");
        return indexes.preflight(taskId);
    }
}
