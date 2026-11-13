package cn.knowpool.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.questions.Questions;
import cn.knowpool.system.ModelSettingsService;
import cn.knowpool.system.api.LocalSessionFilter;
import cn.knowpool.system.api.TaskEventsController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;

@RestController
public final class QuestionController {
    private final Questions questions;
    private final TaskEventsController events;
    public QuestionController(Questions questions,TaskEventsController events) { this.questions=questions;this.events=events; }

    @PostMapping(value="/api/v1/conversations/first-question",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode first(@RequestBody JsonNode input,HttpServletRequest request) {
        noQuery(request);
        if (!input.isObject() || !input.has("question") || !input.has("knowledgeBaseIds") || input.propertyNames().stream().anyMatch(name -> !java.util.Set.of("question","knowledgeBaseIds","manualName").contains(name))) throw ApiFailure.invalid(null,"首问仅接受问题、知识库范围及可选手动名称。");
        text(input.path("question"),20000,"/question");if (input.has("manualName")) text(input.path("manualName"),120,"/manualName");
        JsonNode ids=input.path("knowledgeBaseIds");
        if (!ids.isArray() || ids.isEmpty() || ids.size()>100) throw ApiFailure.invalid("/knowledgeBaseIds","须选择一至一百个知识库。");
        var unique=new java.util.HashSet<String>();
        for (JsonNode id:ids) if (!id.isTextual() || !unique.add(ModelSettingsService.identifier(id.asText(),"/knowledgeBaseIds"))) throw ApiFailure.invalid("/knowledgeBaseIds","知识库标识须合法且不能重复。");
        var keys=java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size()!=1) throw ApiFailure.invalid("Idempotency-Key","须提供唯一幂等键。");
        var session=(LocalSessionFilter.Session)request.getAttribute("localSession");return questions.first(session.sessionId(),keys.getFirst(),input);
    }
    @GetMapping("/api/v1/question-tasks/{taskId}")
    public JsonNode get(@PathVariable String taskId,HttpServletRequest request) { noQuery(request);return questions.get(taskId); }
    @PostMapping("/api/v1/question-tasks/{taskId}/cancel")
    public JsonNode cancel(@PathVariable String taskId,@RequestBody(required=false) String body,HttpServletRequest request) {
        noQuery(request);if (body!=null && !body.isEmpty()) throw ApiFailure.invalid(null,"取消问答不接受正文。");return questions.cancel(taskId);
    }
    @GetMapping("/api/v1/question-tasks/{taskId}/events")
    public SseEmitter events(@PathVariable String taskId,HttpServletRequest request) { questions.get(taskId);return events.watch(taskId,request); }
    @GetMapping("/api/v1/conversations/{conversationId}/evidence/{evidenceId}")
    public JsonNode evidence(@PathVariable String conversationId,@PathVariable String evidenceId,HttpServletRequest request) { noQuery(request);return questions.evidence(conversationId,evidenceId); }
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null,"此操作不接受查询参数。"); }
    private static void text(JsonNode value,int limit,String field) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().codePointCount(0,value.asText().length())>limit) throw ApiFailure.invalid(field,"文本须非空白且不超过允许长度。");
    }
}
