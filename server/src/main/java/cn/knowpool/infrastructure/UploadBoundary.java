package cn.knowpool.infrastructure;

import jakarta.servlet.MultipartConfigElement;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** 在容器解析之前固定本次上传限额，分块请求同样受解析器的流式限额约束。 */
@Component
public final class UploadBoundary {
    private final RuntimeSettings settings;
    private final String temporary;
    private org.apache.catalina.Wrapper servlet;
    private org.apache.catalina.connector.Connector connector;

    public UploadBoundary(RuntimeSettings settings) throws IOException {
        this.settings = settings;
        temporary = Files.createTempDirectory("know-pool-upload-").toString();
    }

    @EventListener
    public void started(WebServerInitializedEvent event) {
        if (event.getWebServer() instanceof org.springframework.boot.tomcat.TomcatWebServer server) {
            connector = server.getTomcat().getConnector();
            for (var child : server.getTomcat().getHost().findChildren())
                if (child instanceof org.apache.catalina.Context context
                        && context.findChild("dispatcherServlet") instanceof org.apache.catalina.Wrapper wrapper)
                    servlet = wrapper;
        }
    }

    public synchronized void parse(HttpServletRequest request) throws IOException, jakarta.servlet.ServletException {
        if (servlet == null || connector == null)
            throw new ApiFailure(503, "DEPENDENCY_UNAVAILABLE", "上传容器尚未就绪，请稍后重试。", null);
        var values = settings.get();
        long limit = values.path("maxUploadBytes").asLong();
        servlet.setMultipartConfigElement(new MultipartConfigElement(temporary, limit, limit, 0));
        connector.setMaxPartCount(values.path("maxUploadFiles").asInt());
        try {
            request.getParts();
        } catch (IOException | jakarta.servlet.ServletException | IllegalStateException failure) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof org.apache.tomcat.util.http.fileupload.impl.InvalidContentTypeException)
                    throw new ApiFailure(415, "UNSUPPORTED_FILE", "上传请求须使用多部分文件媒体类型。", null);
                if (cause instanceof org.apache.tomcat.util.http.fileupload.impl.FileCountLimitExceededException)
                    throw new ApiFailure(400, "INVALID_REQUEST", "上传文件数量超出允许范围。", null);
                if (cause instanceof org.apache.tomcat.util.http.fileupload.impl.SizeLimitExceededException
                        || cause instanceof org.apache.tomcat.util.http.fileupload.impl.FileSizeLimitExceededException)
                    throw new ApiFailure(413, "RESOURCE_LIMIT", "上传请求超过总大小上限。", null);
            }
            if (failure instanceof IOException io) throw io;
            throw new ApiFailure(400, "INVALID_REQUEST", "上传多部分请求格式不正确。", null);
        }
    }
}
