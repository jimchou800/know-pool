package cn.knowpool.system.api;

import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception failure, HttpServletResponse response) {
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) {
            requestId = UUID.randomUUID().toString();
        }
        return ResponseEntity.internalServerError()
                .body(Map.of(
                        "requestId",
                        requestId,
                        "code",
                        "INTERNAL_ERROR",
                        "message",
                        "操作失败，请稍后重试。",
                        "fieldErrors",
                        List.of(),
                        "retryable",
                        true));
    }
}
