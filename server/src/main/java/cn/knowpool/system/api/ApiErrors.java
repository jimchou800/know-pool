package cn.knowpool.system.api;

import cn.knowpool.infrastructure.ApiFailure;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public final class ApiErrors {
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> uploadSize(Exception failure, HttpServletResponse response) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException)
                return body(400, "INVALID_REQUEST", "上传文件数量超出允许范围。", List.of(), response);
        }
        return body(413, "RESOURCE_LIMIT", "上传请求超过总大小上限。", List.of(), response);
    }

    @ExceptionHandler(ApiFailure.class)
    public ResponseEntity<Map<String, Object>> expected(ApiFailure failure, HttpServletResponse response) {
        return body(failure.status(), failure.code(), failure.getMessage(), failure.fieldErrors(), response);
    }

    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        org.springframework.web.multipart.MultipartException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class,
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> malformed(Exception failure, HttpServletResponse response) {
        return body(400, "INVALID_REQUEST", "请求正文格式或内容类型不正确。", List.of(), response);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> media(
            Exception failure, HttpServletResponse response, jakarta.servlet.http.HttpServletRequest request) {
        if (request.getRequestURI().endsWith("/files:upload"))
            return body(415, "UNSUPPORTED_FILE", "上传请求须使用多部分文件媒体类型。", List.of(), response);
        return body(400, "INVALID_REQUEST", "请求内容类型不正确。", List.of(), response);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception failure, HttpServletResponse response) {
        return body(500, "INTERNAL_ERROR", "操作失败，请稍后重试。", List.of(), response);
    }

    private static ResponseEntity<Map<String, Object>> body(
            int status, String code, String message, List<ApiFailure.FieldError> fields, HttpServletResponse response) {
        String requestId = response.getHeader("X-Request-Id");
        if (requestId == null) requestId = UUID.randomUUID().toString();
        return ResponseEntity.status(status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "requestId",
                        requestId,
                        "code",
                        code,
                        "message",
                        message,
                        "fieldErrors",
                        fields,
                        "retryable",
                        status >= 500));
    }
}
