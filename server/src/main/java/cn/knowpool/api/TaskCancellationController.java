package cn.knowpool.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.Indexes;
import cn.knowpool.search.SearchRequests;
import cn.knowpool.system.ConnectionTests;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public final class TaskCancellationController {
    private final ConnectionTests tasks;
    private final Indexes indexes;
    private final SearchRequests searches;
    private final cn.knowpool.questions.Questions questions;

    public TaskCancellationController(ConnectionTests tasks, Indexes indexes, SearchRequests searches,cn.knowpool.questions.Questions questions) {
        this.tasks = tasks; this.indexes = indexes; this.searches = searches;this.questions=questions;
    }

    @PostMapping("/api/v1/tasks/{taskId}/cancel")
    public Object cancel(@PathVariable String taskId, HttpServletRequest request) throws java.io.IOException {
        if (!request.getParameterMap().isEmpty() || request.getInputStream().read() != -1)
            throw ApiFailure.invalid(null,"取消操作不接受查询参数或请求正文。");
        var task = tasks.get(taskId);
        return switch (task.kind()) {
            case "index_file", "rebuild_knowledge_base" -> indexes.cancel(taskId);
            case "search" -> searches.cancel(taskId);
            case "question" -> questions.cancel(taskId).path("task");
            default -> throw new ApiFailure(409,"CONFLICT","此任务类型不允许取消。",null);
        };
    }
}
