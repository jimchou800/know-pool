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
    public Limits uploadLimits(RuntimeSettings settings) { return new Limits(settings); }

    @Bean
    public MultipartConfigElement multipartConfigElement(ApplicationData data, Limits limits) throws IOException {
        var directory = Files.createTempDirectory("know-pool-multipart-");
        Files.createDirectories(directory);
        return new MultipartConfigElement(directory.toString(), -1, -1, 0);
    }

    public static final class Limits {
        private final RuntimeSettings runtime;
        private final long file,upload,timeout,extracted;
        private final int count;
        public Limits(RuntimeSettings runtime) { this.runtime=runtime;file=upload=timeout=extracted=0;count=0; }
        public Limits(long file,long upload,int count,long timeout,long extracted) { this.runtime=null;this.file=file;this.upload=upload;this.count=count;this.timeout=timeout;this.extracted=extracted; }
        public long maxFileBytes() { return runtime==null?file:runtime.number("maxFileBytes"); }
        public long maxUploadBytes() { return runtime==null?upload:runtime.number("maxUploadBytes"); }
        public int maxUploadFiles() { return runtime==null?count:(int)runtime.number("maxUploadFiles"); }
        public long parseTimeoutSeconds() { return runtime==null?timeout:runtime.number("parseTimeoutSeconds"); }
        public long maxExtractedCodePoints() { return runtime==null?extracted:runtime.number("maxExtractedCodePoints"); }
    }
}
