package cn.knowpool;

import cn.knowpool.infrastructure.FileInspectionWorker;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class KnowledgePoolApplication {
    public static void main(String[] args) {
        if (args.length > 0 && FileInspectionWorker.INTERNAL_MODE.equals(args[0])) {
            System.exit(FileInspectionWorker.run(args));
            return;
        }
        if (args.length > 0 && cn.knowpool.infrastructure.PdfExtractionWorker.INTERNAL_MODE.equals(args[0])) {
            System.exit(cn.knowpool.infrastructure.PdfExtractionWorker.run(args));
            return;
        }
        SpringApplication.run(KnowledgePoolApplication.class, args);
    }
}
