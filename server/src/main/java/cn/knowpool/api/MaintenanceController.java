package cn.knowpool.api;

import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.knowledgebases.Maintenance;
import cn.knowpool.system.api.LocalSessionFilter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController
public final class MaintenanceController {
    private final Maintenance maintenance;
    public MaintenanceController(Maintenance maintenance) { this.maintenance=maintenance; }
    @PostMapping("/api/v1/maintenance/migration/preflight")
    public JsonNode preflight(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.preflight(body,session(request)); }
    @PostMapping("/api/v1/maintenance/migration") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode submit(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.submit(body,session(request),key(request)); }
    @PostMapping("/api/v1/maintenance/backup/preflight")
    public JsonNode backupPreflight(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.backupPreflight(body,session(request)); }
    @PostMapping("/api/v1/maintenance/backup") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode backup(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.submitArchive("backup",body,session(request),key(request)); }
    @PostMapping("/api/v1/maintenance/restore/preflight")
    public JsonNode restorePreflight(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.restorePreflight(body,session(request)); }
    @PostMapping("/api/v1/maintenance/restore") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode restore(@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.submitArchive("restore",body,session(request),key(request)); }
    @GetMapping("/api/v1/maintenance/tasks/{taskId}")
    public JsonNode get(@PathVariable String taskId,HttpServletRequest request) { noQuery(request);return maintenance.get(taskId); }
    @PostMapping("/api/v1/maintenance/tasks/{taskId}/retry") @ResponseStatus(HttpStatus.ACCEPTED)
    public JsonNode retry(@PathVariable String taskId,@RequestBody JsonNode body,HttpServletRequest request) { noQuery(request);return maintenance.retry(taskId,body,session(request),key(request)); }
    private static void noQuery(HttpServletRequest request) { if (!request.getParameterMap().isEmpty()) throw ApiFailure.invalid(null,"此操作不接受查询参数。"); }
    private static String key(HttpServletRequest request) { var keys=java.util.Collections.list(request.getHeaders("Idempotency-Key"));if (keys.size()!=1) throw ApiFailure.invalid("Idempotency-Key","须提供唯一幂等键。");return keys.getFirst(); }
    private static String session(HttpServletRequest request) { return ((LocalSessionFilter.Session)request.getAttribute("localSession")).sessionId(); }
}
