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
        questionInput(input,true);
        var session=session(request);return questions.first(session.sessionId(),key(request),input);
    }
    @GetMapping("/api/v1/conversations")
    public Questions.Page conversations(HttpServletRequest request) { return questions.conversations(limit(request),request.getParameter("cursor")); }
    @GetMapping("/api/v1/conversations/{conversationId}")
    public JsonNode conversation(@PathVariable String conversationId,HttpServletRequest request) { noQuery(request);return questions.conversation(conversationId); }
    @PatchMapping(value="/api/v1/conversations/{conversationId}/name",consumes="application/json")
    public JsonNode rename(@PathVariable String conversationId,@RequestBody JsonNode input,HttpServletRequest request) {
        noQuery(request);only(input,"name");text(input.path("name"),120,"/name");return questions.rename(conversationId,input.path("name").asText());
    }
    @PutMapping(value="/api/v1/conversations/{conversationId}/scope",consumes="application/json")
    public JsonNode scope(@PathVariable String conversationId,@RequestBody JsonNode input,HttpServletRequest request) {
        noQuery(request);only(input,"knowledgeBaseIds");scopeInput(input.path("knowledgeBaseIds"),true);return questions.updateScope(conversationId,input.path("knowledgeBaseIds"));
    }
    @GetMapping("/api/v1/conversations/{conversationId}/turns")
    public Questions.Page turns(@PathVariable String conversationId,HttpServletRequest request) { return questions.turns(conversationId,limit(request),request.getParameter("cursor")); }
    @PostMapping(value="/api/v1/conversations/{conversationId}/questions",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode submit(@PathVariable String conversationId,@RequestBody JsonNode input,HttpServletRequest request) {
        noQuery(request);questionInput(input,false);return questions.submit(conversationId,session(request).sessionId(),key(request),input);
    }
    @DeleteMapping(value="/api/v1/conversations/{conversationId}",consumes="application/json")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode delete(@PathVariable String conversationId,@RequestBody JsonNode input,HttpServletRequest request) {
        noQuery(request);only(input,"confirmed");if (!input.path("confirmed").isBoolean() || !input.path("confirmed").asBoolean()) throw ApiFailure.invalid("/confirmed","删除会话须明确确认。");
        return questions.delete(conversationId,session(request).sessionId(),key(request));
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
    private static LocalSessionFilter.Session session(HttpServletRequest request) { return (LocalSessionFilter.Session)request.getAttribute("localSession"); }
    private static String key(HttpServletRequest request) {
        var keys=java.util.Collections.list(request.getHeaders("Idempotency-Key"));
        if (keys.size()!=1) throw ApiFailure.invalid("Idempotency-Key","须提供唯一幂等键。");return keys.getFirst();
    }
    private static void questionInput(JsonNode input,boolean first) {
        if (!input.isObject() || !input.has("question") || !input.has("knowledgeBaseIds") || input.propertyNames().stream().anyMatch(name -> !(first?java.util.Set.of("question","knowledgeBaseIds","manualName"):java.util.Set.of("question","knowledgeBaseIds")).contains(name))) throw ApiFailure.invalid(null,"仅接受问题、知识库范围及首问可选手动名称。");
        text(input.path("question"),20000,"/question");if (input.has("manualName")) text(input.path("manualName"),120,"/manualName");scopeInput(input.path("knowledgeBaseIds"),false);
    }
    private static void scopeInput(JsonNode ids,boolean emptyAllowed) {
        if (!ids.isArray() || (!emptyAllowed && ids.isEmpty()) || ids.size()>100) throw ApiFailure.invalid("/knowledgeBaseIds","知识库范围数量超出允许限制。");
        var unique=new java.util.HashSet<String>();
        for (JsonNode id:ids) if (!id.isTextual() || !unique.add(ModelSettingsService.identifier(id.asText(),"/knowledgeBaseIds"))) throw ApiFailure.invalid("/knowledgeBaseIds","知识库标识须合法且不能重复。");
    }
    private static void only(JsonNode input,String field) { if (!input.isObject() || input.size()!=1 || !input.has(field)) throw ApiFailure.invalid(null,"请求字段不符合要求。"); }
    private static int limit(HttpServletRequest request) {
        for (var entry:request.getParameterMap().entrySet()) if (!java.util.Set.of("limit","cursor").contains(entry.getKey()) || entry.getValue().length!=1) throw ApiFailure.invalid(entry.getKey(),"查询参数未知或重复。");
        String cursor=request.getParameter("cursor");if (cursor!=null && (cursor.isEmpty() || cursor.length()>2048)) throw ApiFailure.invalid("cursor","分页位置长度无效。");
        String value=request.getParameter("limit");if (value==null) return 20;
        try { if (!value.matches("[0-9]+")) throw new NumberFormatException();int limit=Integer.parseInt(value);if (limit<1 || limit>100) throw new NumberFormatException();return limit; }
        catch (NumberFormatException failure) { throw ApiFailure.invalid("limit","每页数量须为一至一百的整数。"); }
    }
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null,"此操作不接受查询参数。"); }
    private static void text(JsonNode value,int limit,String field) {
        if (!value.isTextual() || value.asText().isBlank() || value.asText().codePointCount(0,value.asText().length())>limit) throw ApiFailure.invalid(field,"文本须非空白且不超过允许长度。");
    }
}
