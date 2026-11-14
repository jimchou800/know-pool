package cn.knowpool.infrastructure;

import cn.knowpool.KnowledgePoolApplication;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.boot.system.ApplicationHome;

public final class PdfExtractionWorker {
    public static final String INTERNAL_MODE = "--internal-extract-pdf";
    private static final Semaphore CAPACITY = new Semaphore(1);

    private PdfExtractionWorker() {}

    public static List<String> extract(byte[] bytes, Path directory, UploadConfiguration.Limits limits)
            throws IOException {
        if (!CAPACITY.tryAcquire()) throw new ApiFailure(422, "RESOURCE_LIMIT", "PDF全文提取容量已满。", null);
        Path input = null, output = null;
        try {
            Files.createDirectories(directory);
            if (!directory.toRealPath().equals(directory.toAbsolutePath().normalize()))
                throw new IOException("解析临时目录不能包含链接。");
            input = Files.createTempFile(directory, "source-", ".pdf");
            output = Files.createTempFile(directory, "pages-", ".bin");
            Files.write(input, bytes);
            var command = new ArrayList<String>();
            command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
            command.addAll(List.of("-Xms16m", "-Xmx128m", "-XX:-HeapDumpOnOutOfMemoryError", "-XX:-CreateCoredumpOnCrash"));
            var source = new ApplicationHome(KnowledgePoolApplication.class).getSource();
            if (source != null && source.isFile()) {
                command.addAll(List.of("-jar", source.getAbsolutePath()));
            } else {
                command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), KnowledgePoolApplication.class.getName()));
            }
            command.addAll(List.of(INTERNAL_MODE, input.toString(), output.toString(), Long.toString(limits.maxExtractedCodePoints())));
            Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try {
                if (!process.waitFor(limits.parseTimeoutSeconds(), TimeUnit.SECONDS) || process.exitValue() == 3)
                    throw new ApiFailure(422, "RESOURCE_LIMIT", "PDF全文提取超过时间、内存或文字量限制。", null);
                if (process.exitValue() != 0)
                    throw new ApiFailure(422, "PARSING_FAILED", "PDF损坏或无法可靠提取文字。", null);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException("PDF全文提取已中断。", failure);
            } finally {
                process.destroyForcibly();
                boolean interrupted = false;
                while (process.isAlive()) {
                    try { process.waitFor(); }
                    catch (InterruptedException failure) { interrupted = true; }
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
            var pages = new ArrayList<String>();
            long count = 0;
            try (var stream = new DataInputStream(Files.newInputStream(output))) {
                int totalPages = stream.readInt();
                if (totalPages < 1 || totalPages > 100000) throw new IOException("PDF页面总量超过可控范围。");
                while (stream.available() > 0) {
                    int pageNumber = stream.readInt();
                    if (pageNumber < 1 || pageNumber > 100000 || pageNumber <= pages.size()) throw new IOException("PDF页码映射不合法。");
                    while (pages.size() < pageNumber - 1) pages.add("");
                    int length = stream.readInt();
                    if (length < 0 || length > stream.available() || length > 4L * Math.min(Integer.MAX_VALUE / 4, limits.maxExtractedCodePoints()))
                        throw new IOException("PDF全文提取输出不完整。");
                    byte[] page = stream.readNBytes(length);
                    String text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(page)).toString();
                    count += text.codePointCount(0, text.length());
                    if (count > limits.maxExtractedCodePoints())
                        throw new ApiFailure(422, "RESOURCE_LIMIT", "PDF全文提取文字超过限制。", null);
                    pages.add(text);
                }
                while (pages.size() < totalPages) pages.add("");
                if (pages.size() != totalPages) throw new IOException("PDF页码映射不完整。");
            }
            if (pages.isEmpty() || pages.stream().allMatch(String::isBlank))
                throw new ApiFailure(422, "PARSING_FAILED", "PDF没有可提取文字，不支持扫描识别。", null);
            return List.copyOf(pages);
        } finally {
            try { if (input != null) Files.deleteIfExists(input); }
            finally {
                try { if (output != null) Files.deleteIfExists(output); }
                finally { CAPACITY.release(); }
            }
        }
    }

    public static int run(String[] args) {
        if (args.length != 4) return 2;
        try (var document = Loader.loadPDF(Path.of(args[1]).toFile());
                var output = new DataOutputStream(Files.newOutputStream(Path.of(args[2])))) {
            if (document.isEncrypted()) return 2;
            if (document.getNumberOfPages() > 100000) return 3;
            output.writeInt(document.getNumberOfPages());
            Extractor extractor = new Extractor(Long.parseLong(args[3]), output);
            extractor.writeText(document, extractor.writer());
            return 0;
        } catch (LimitReached | OutOfMemoryError failure) { return 3; }
        catch (IOException | RuntimeException failure) { return 2; }
    }

    private static final class Extractor extends PDFTextStripper {
        private final long maximum;
        private final DataOutputStream output;
        private final StringBuilder page = new StringBuilder();
        private long count;
        private long positions;

        private Extractor(long maximum, DataOutputStream output) throws IOException {
            this.maximum = maximum;
            this.output = output;
            setSortByPosition(true);
            setSuppressDuplicateOverlappingText(false);
            setLineSeparator("\n");
            setParagraphStart("");
            setParagraphEnd("\n");
            setPageStart("");
            setPageEnd("");
        }

        @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition position) {
            String text = position.getUnicode();
            positions += text.codePointCount(0, text.length());
            if (positions > maximum) throw new LimitReached();
            if (text.indexOf('\ufffd') >= 0 || !Float.isFinite(position.getXDirAdj()) || !Float.isFinite(position.getYDirAdj())
                    || position.getDir() != 0) throw new IllegalArgumentException("PDF文字顺序或编码无法可靠恢复。");
            super.processTextPosition(position);
        }

        @Override protected void startPage(PDPage value) throws IOException {
            super.startPage(value);
            page.setLength(0);
        }

        private Writer writer() {
            return new Writer() {
                @Override public void write(char[] buffer, int offset, int length) {
                    String text = new String(buffer, offset, length);
                    count += text.codePointCount(0, text.length());
                    if (count > maximum) throw new LimitReached();
                    page.append(text);
                }
                @Override public void flush() {}
                @Override public void close() {}
            };
        }

        @Override protected void endPage(PDPage value) throws IOException {
            super.endPage(value);
            byte[] bytes = page.toString().getBytes(StandardCharsets.UTF_8);
            output.writeInt(getCurrentPageNo());
            output.writeInt(bytes.length);
            output.write(bytes);
        }
    }

    private static final class LimitReached extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private LimitReached() { super(null, null, false, false); }
    }
}
