package cn.knowpool.infrastructure;

import cn.knowpool.KnowledgePoolApplication;
import java.io.IOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.springframework.boot.system.ApplicationHome;

public final class FileInspectionWorker {
    public static final String INTERNAL_MODE = "--internal-inspect-pdf";
    private static final Semaphore CAPACITY = new Semaphore(1);

    private FileInspectionWorker() {}

    public enum Result {
        SUPPORTED,
        UNSUPPORTED,
        RESOURCE_LIMIT
    }

    public static Result inspectPdf(Path input, long timeoutMillis, long maxExtractedCodePoints) throws IOException {
        if (input == null
                || !input.isAbsolute()
                || !Files.isRegularFile(input)
                || timeoutMillis < 1
                || maxExtractedCodePoints < 1) {
            throw new IllegalArgumentException("PDF校验参数不合法。");
        }
        if (!CAPACITY.tryAcquire()) {
            return Result.RESOURCE_LIMIT;
        }
        try {
            return inspectInProcess(input, timeoutMillis, maxExtractedCodePoints);
        } finally {
            CAPACITY.release();
        }
    }

    private static Result inspectInProcess(Path input, long timeoutMillis, long maxExtractedCodePoints)
            throws IOException {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xms16m");
        command.add("-Xmx128m");
        command.add("-XX:-HeapDumpOnOutOfMemoryError");
        command.add("-XX:-CreateCoredumpOnCrash");
        var source = new ApplicationHome(KnowledgePoolApplication.class).getSource();
        if (source != null && source.isFile()) {
            command.add("-jar");
            command.add(source.getAbsolutePath());
        } else {
            command.add("-cp");
            command.add(System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")));
            command.add(KnowledgePoolApplication.class.getName());
        }
        command.add(INTERNAL_MODE);
        command.add(input.toString());
        command.add(Long.toString(maxExtractedCodePoints));
        Process process = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                return Result.RESOURCE_LIMIT;
            }
            return switch (process.exitValue()) {
                case 0 -> Result.SUPPORTED;
                case 2 -> Result.UNSUPPORTED;
                case 3 -> Result.RESOURCE_LIMIT;
                default -> throw new IOException("PDF校验进程异常退出。");
            };
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("PDF校验已中断。");
        } finally {
            process.destroyForcibly();
            boolean interrupted = false;
            while (true) {
                try {
                    process.waitFor();
                    break;
                } catch (InterruptedException failure) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public static int run(String[] args) {
        try {
            if (args.length != 3 || !INTERNAL_MODE.equals(args[0])) {
                return 2;
            }
            Path input = Path.of(args[1]);
            long maximum = Long.parseLong(args[2]);
            if (!input.isAbsolute() || !Files.isRegularFile(input) || maximum < 1) {
                return 2;
            }
            try (var document = Loader.loadPDF(input.toFile())) {
                if (document.isEncrypted()) {
                    return 2;
                }
                new TextInspection(maximum).writeText(document, Writer.nullWriter());
                return 2;
            } catch (TextFound found) {
                return 0;
            }
        } catch (ResourceLimit | OutOfMemoryError failure) {
            return 3;
        } catch (IOException | RuntimeException failure) {
            return 2;
        }
    }

    private static final class TextInspection extends PDFTextStripper {
        private final long maximum;
        private long inspected;

        private TextInspection(long maximum) throws IOException {
            this.maximum = maximum;
        }

        @Override
        protected void processTextPosition(TextPosition position) {
            String text = position.getUnicode();
            int count = text.codePointCount(0, text.length());
            if (count > maximum - inspected) {
                throw new ResourceLimit();
            }
            inspected += count;
            if (!text.isBlank()) {
                throw new TextFound();
            }
        }
    }

    private static final class TextFound extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private TextFound() {
            super(null, null, false, false);
        }
    }

    private static final class ResourceLimit extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private ResourceLimit() {
            super(null, null, false, false);
        }
    }
}
