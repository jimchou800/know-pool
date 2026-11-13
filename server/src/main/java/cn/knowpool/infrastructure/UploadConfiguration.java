package cn.knowpool.infrastructure;

import jakarta.servlet.MultipartConfigElement;
import java.io.IOException;
import java.nio.file.Files;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class UploadConfiguration {
    @Bean
    public Limits uploadLimits(
            @Value("${know-pool.upload.max-file-bytes}") long maxFileBytes,
            @Value("${know-pool.upload.max-upload-bytes}") long maxUploadBytes,
            @Value("${know-pool.upload.max-upload-files}") int maxUploadFiles,
            @Value("${know-pool.processing.parse-timeout-seconds}") long parseTimeoutSeconds,
            @Value("${know-pool.processing.max-extracted-code-points}") long maxExtractedCodePoints) {
        if (maxFileBytes < 1
                || maxUploadBytes < maxFileBytes
                || maxUploadBytes > 9007199254740991L
                || maxUploadFiles < 1
                || maxUploadFiles > 1000
                || parseTimeoutSeconds < 1
                || parseTimeoutSeconds > 9007199254740991L
                || maxExtractedCodePoints < 1
                || maxExtractedCodePoints > 9007199254740991L) throw new IllegalArgumentException("上传资源参数不合法。");
        return new Limits(maxFileBytes, maxUploadBytes, maxUploadFiles, parseTimeoutSeconds, maxExtractedCodePoints);
    }

    @Bean
    public MultipartConfigElement multipartConfigElement(ApplicationData data, Limits limits) throws IOException {
        var directory = data.directory().resolve("multipart");
        Files.createDirectories(directory);
        return new MultipartConfigElement(directory.toString(), limits.maxUploadBytes(), limits.maxUploadBytes(), 0);
    }

    public record Limits(
            long maxFileBytes,
            long maxUploadBytes,
            int maxUploadFiles,
            long parseTimeoutSeconds,
            long maxExtractedCodePoints) {}
}
