package cn.knowpool.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileInspectionWorkerTest {
    @TempDir(factory = WorkspaceTemporaryDirectory.class)
    Path directory;

    static final class WorkspaceTemporaryDirectory implements org.junit.jupiter.api.io.TempDirFactory {
        @Override
        public Path createTempDirectory(
                org.junit.jupiter.api.extension.AnnotatedElementContext context,
                org.junit.jupiter.api.extension.ExtensionContext extension)
                throws IOException {
            Path root = Files.createDirectories(Path.of("target", "test-data").toAbsolutePath());
            return Files.createTempDirectory(root, "pdf-inspection-");
        }
    }

    @Test
    @DisplayName("文字PDF在独立进程内完成识别")
    void acceptTextPdf() throws Exception {
        Path input = textPdf("sample.pdf", "Hello");
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 1000))
                .isEqualTo(FileInspectionWorker.Result.SUPPORTED);
    }

    @Test
    @DisplayName("损坏、加密和无文字PDF均不受支持")
    void rejectUnsupportedPdf() throws Exception {
        Path damaged = Files.writeString(directory.resolve("damaged.pdf"), "不是PDF");
        Path blank = directory.resolve("blank.pdf");
        Path encrypted = directory.resolve("encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(blank.toFile());
            document.protect(new StandardProtectionPolicy("owner", "reader", new AccessPermission()));
            document.save(encrypted.toFile());
        }
        for (Path input : new Path[] {damaged, blank, encrypted}) {
            assertThat(FileInspectionWorker.inspectPdf(input, 10000, 1000))
                    .isEqualTo(FileInspectionWorker.Result.UNSUPPORTED);
        }
    }

    @Test
    @DisplayName("识别首个文字前的码点预算不可静默截断")
    void enforceInspectedCodePointBoundary() throws Exception {
        Path input = textPdf("boundary.pdf", "   A");
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 3))
                .isEqualTo(FileInspectionWorker.Result.RESOURCE_LIMIT);
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 4)).isEqualTo(FileInspectionWorker.Result.SUPPORTED);
    }

    @Test
    @DisplayName("补充平面字符按一个Unicode码点计入预算")
    void countSupplementaryCharacterAsOneCodePoint() throws Exception {
        Path source = textPdf("unicode-source.pdf", "A");
        Path input = directory.resolve("unicode.pdf");
        try (var document = org.apache.pdfbox.Loader.loadPDF(source.toFile())) {
            var mapping = new PDStream(document);
            try (var output = mapping.createOutputStream()) {
                output.write(("/CIDInit /ProcSet findresource begin 12 dict begin begincmap "
                                + "/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def "
                                + "/CMapName /CustomUnicode def /CMapType 2 def "
                                + "1 begincodespacerange <00> <FF> endcodespacerange "
                                + "1 beginbfchar <41> <D83DDE00> endbfchar "
                                + "endcmap CMapName currentdict /CMap defineresource pop end end")
                        .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            var resources = document.getPage(0).getResources();
            resources
                    .getFont(resources.getFontNames().iterator().next())
                    .getCOSObject()
                    .setItem(COSName.TO_UNICODE, mapping);
            document.save(input.toFile());
        }
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 1)).isEqualTo(FileInspectionWorker.Result.SUPPORTED);
    }

    @Test
    @DisplayName("内部命令入口校验参数并返回固定协议状态码")
    void validateInternalCommandProtocol() throws Exception {
        String mode = FileInspectionWorker.INTERNAL_MODE;
        Path input = textPdf("command.pdf", "  A");
        assertThat(FileInspectionWorker.run(new String[] {mode, input.toString(), "3"}))
                .isZero();
        assertThat(FileInspectionWorker.run(new String[] {mode, input.toString(), "2"}))
                .isEqualTo(3);
        assertThat(FileInspectionWorker.run(new String[] {})).isEqualTo(2);
        assertThat(FileInspectionWorker.run(new String[] {"invalid", input.toString(), "3"}))
                .isEqualTo(2);
        assertThat(FileInspectionWorker.run(new String[] {mode, "relative.pdf", "3"}))
                .isEqualTo(2);
        assertThat(FileInspectionWorker.run(
                        new String[] {mode, directory.resolve("missing.pdf").toString(), "3"}))
                .isEqualTo(2);
        assertThat(FileInspectionWorker.run(new String[] {mode, input.toString(), "0"}))
                .isEqualTo(2);
        assertThat(FileInspectionWorker.run(new String[] {mode, input.toString(), "invalid"}))
                .isEqualTo(2);
        Path damaged = Files.writeString(directory.resolve("command-damaged.pdf"), "不是PDF");
        assertThat(FileInspectionWorker.run(new String[] {mode, damaged.toString(), "3"}))
                .isEqualTo(2);
        Path blank = directory.resolve("command-blank.pdf");
        Path encrypted = directory.resolve("command-encrypted.pdf");
        try (var document = new PDDocument()) {
            document.addPage(new PDPage());
            document.save(blank.toFile());
            document.protect(new StandardProtectionPolicy("owner", "", new AccessPermission()));
            document.save(encrypted.toFile());
        }
        assertThat(FileInspectionWorker.run(new String[] {mode, blank.toString(), "3"}))
                .isEqualTo(2);
        assertThat(FileInspectionWorker.run(new String[] {mode, encrypted.toString(), "3"}))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("超时硬终止并回收子进程，随后可正常校验")
    void terminateAndReapTimedOutWorker() throws Exception {
        Path input = textPdf("timeout.pdf", "Hello");
        Set<Long> before = childIds();
        long started = System.nanoTime();
        for (int index = 0; index < 3; index++) {
            assertThat(FileInspectionWorker.inspectPdf(input, 1, 1000))
                    .isEqualTo(FileInspectionWorker.Result.RESOURCE_LIMIT);
        }
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(childIds()).isSubsetOf(before);
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 1000))
                .isEqualTo(FileInspectionWorker.Result.SUPPORTED);
    }

    @Test
    @DisplayName("中断保持线程标志且回收子进程")
    void reapInterruptedWorker() throws Exception {
        Path input = textPdf("interrupt.pdf", "Hello");
        Set<Long> before = childIds();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(input, 10000, 1000))
                    .isInstanceOf(IOException.class)
                    .hasMessage("PDF校验已中断。");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(childIds()).isSubsetOf(before);
    }

    @Test
    @DisplayName("拒绝非法进程参数且不创建子进程")
    void rejectInvalidArguments() throws Exception {
        Path input = textPdf("arguments.pdf", "Hello");
        Set<Long> before = childIds();
        assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(null, 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(Path.of("relative.pdf"), 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(directory.resolve("missing.pdf"), 1000, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(input, 0, 1000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FileInspectionWorker.inspectPdf(input, 1000, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(childIds()).isSubsetOf(before);
    }

    @Test
    @DisplayName("压缩流膨胀超过独立堆上限不会耗尽主进程")
    void boundDecompressedPdfMemory() throws Exception {
        Path input = directory.resolve("expanded.pdf");
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            var contents = new PDStream(document);
            page.setContents(contents);
            try (var output = contents.createOutputStream(COSName.FLATE_DECODE)) {
                output.write("BT (".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                byte[] spaces = new byte[8192];
                Arrays.fill(spaces, (byte) ' ');
                for (int index = 0; index < 32768; index++) {
                    output.write(spaces);
                }
                output.write(") Tj ET".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            document.save(input.toFile());
        }
        Set<Long> before = childIds();
        var running = CompletableFuture.supplyAsync(() -> {
            try {
                return FileInspectionWorker.inspectPdf(input, 10000, 1000000);
            } catch (IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        });
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (childIds().equals(before) && !running.isDone() && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertThat(childIds()).isNotEqualTo(before);
        assertThat(FileInspectionWorker.inspectPdf(input, 10000, 1000000))
                .isEqualTo(FileInspectionWorker.Result.RESOURCE_LIMIT);
        assertThat(running.get(15, java.util.concurrent.TimeUnit.SECONDS))
                .isEqualTo(FileInspectionWorker.Result.RESOURCE_LIMIT);
        assertThat(childIds()).isSubsetOf(before);
        assertThat(FileInspectionWorker.inspectPdf(textPdf("recovery.pdf", "Hello"), 10000, 1000))
                .isEqualTo(FileInspectionWorker.Result.SUPPORTED);
    }

    private static Set<Long> childIds() {
        try (var children = ProcessHandle.current().children()) {
            return children.map(ProcessHandle::pid).collect(Collectors.toSet());
        }
    }

    private Path textPdf(String name, String text) throws Exception {
        Path input = directory.resolve(name);
        try (var document = new PDDocument()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(20, 700);
                stream.showText(text);
                stream.endText();
            }
            document.save(input.toFile());
        }
        return input;
    }
}
