package cn.knowpool.knowledgebases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.knowpool.datasets.FileService;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.UploadConfiguration;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DocumentSourcesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "00000000-0000-4000-8000-000000000001";

    @Test
    @DisplayName("多页重复页眉页脚仅按显式配置移除且保留原始偏移")
    void removeRepeatedMarginsWithOriginalOffsets(@TempDir Path directory) throws Exception {
        try (ApplicationData data = new ApplicationData(directory.toString())) {
            DocumentSources parser = parser(data, 10000);
            byte[] bytes = pdf(List.of(
                    List.of("HEADER", "Body one", "FOOTER"),
                    List.of("HEADER", "Body two", "FOOTER"),
                    List.of("Other header", "Body three", "Other footer"),
                    List.of("Short page")));
            FileService.VersionContent source = source("pdf", bytes);
            var kept = parser.parse(source, JSON.createObjectNode().put("removePageHeadersFooters", false));
            assertThat(kept.removals()).isEmpty();
            assertThat(kept.sources()).hasSize(1);
            assertThat(kept.sources().getFirst().text()).contains("HEADER", "FOOTER", "Short page");
            var cleaned = parser.parse(source, JSON.createObjectNode().put("removePageHeadersFooters", true));
            assertThat(cleaned.removals()).hasSize(4);
            var unit = cleaned.sources().getFirst();
            assertThat(unit.text()).doesNotContain("HEADER", "FOOTER");
            assertThat(unit.text()).contains("Body one", "Body two", "Other header", "Other footer", "Short page");
            assertThat(unit.pageEnds()).hasSize(4);
            assertThat(unit.pageEnds()[3]).isEqualTo(unit.text().length());
            assertThat(unit.location().path("pageStart").asInt()).isEqualTo(1);
            assertThat(unit.location().path("pageEnd").asInt()).isEqualTo(4);
            for (JsonNode removal : cleaned.removals()) {
                assertThat(removal.path("page").asInt()).isBetween(1, 2);
                assertThat(removal.path("utf16EndExclusive").asInt())
                        .isGreaterThan(removal.path("utf16Start").asInt());
                assertThat(removal.path("text").asText()).containsAnyOf("HEADER", "FOOTER");
            }
            assertThat(cleaned.warnings()).hasSize(2);
            JsonNode provenance = parser.provenance(source, cleaned);
            assertThat(provenance.path("removedPageMargins").size()).isEqualTo(4);
            assertThat(provenance.at("/sources/0/pageEnds").size()).isEqualTo(4);
            assertThat(provenance.path("contentVersionId").asText()).isEqualTo(ID);
            var single = parser.parse(
                    source("pdf", pdf(List.of(List.of("Single page")))),
                    JSON.createObjectNode().put("removePageHeadersFooters", true));
            assertThat(single.removals()).isEmpty();
            assertThat(single.sources().getFirst().text()).contains("Single page");
        }
    }

    @Test
    @DisplayName("文本规范化与提取上限按码点执行且空白不生成来源")
    void normalizeTextAndRejectExtractionOverflow(@TempDir Path directory) throws Exception {
        try (ApplicationData data = new ApplicationData(directory.toString())) {
            DocumentSources parser = parser(data, 10);
            var parsed = parser.parse(text("text", "\ufeff甲\r\n😀\r乙\n"), JSON.createObjectNode());
            assertThat(parsed.sources()).hasSize(1);
            assertThat(parsed.sources().getFirst().text()).isEqualTo("甲\n😀\n乙\n");
            assertThat(parsed.sources().getFirst().lineStart()).isEqualTo(1);
            assertThat(parsed.sources().getFirst().location().path("format").asText())
                    .isEqualTo("text");
            assertThat(parser.provenance(text("text", "正文"), parsed)
                            .at("/sources/0/pageEnds")
                            .isMissingNode())
                    .isTrue();
            var empty = parser.parse(text("text", " \t\r\n"), JSON.createObjectNode());
            assertThat(empty.sources()).isEmpty();
            assertThat(empty.warnings()).hasSize(1);
            ApiFailure overflow = assertThrows(
                    ApiFailure.class, () -> parser.parse(text("text", "😀".repeat(11)), JSON.createObjectNode()));
            assertThat(overflow.code()).isEqualTo("RESOURCE_LIMIT");
            assertThat(overflow.status()).isEqualTo(422);
            assertThrows(
                    java.nio.charset.CharacterCodingException.class,
                    () -> parser.parse(source("text", new byte[] {(byte) 0xc3, 0x28}), JSON.createObjectNode()));
            var fixed = parser.parse(
                    text("markdown", "# 标题\n正文"), JSON.createObjectNode().put("strategy", "fixed"));
            assertThat(fixed.sources()).hasSize(1);
            assertThat(fixed.sources().getFirst().text()).isEqualTo("# 标题\n正文");
        }
    }

    @Test
    @DisplayName("结构解析保留同级标题路径及无语言代码来源")
    void preserveSiblingHeadingsAndUnlabelledCode(@TempDir Path directory) throws Exception {
        try (ApplicationData data = new ApplicationData(directory.toString())) {
            DocumentSources parser = parser(data, 10000);
            String markdown = "# 第一章\n\n## 子节\n\n正文\n\n# 第二章\n\n```\n代码内容\n```\n\n    缩进代码\n\n- 第一项\n- 第二项\n";
            var parsed = parser.parse(
                    text("markdown", markdown), JSON.createObjectNode().put("strategy", "structure"));
            assertThat(parsed.sources()).anySatisfy(unit -> {
                assertThat(unit.text()).isEqualTo("# 第二章");
                assertThat(unit.location().path("titlePath").size()).isEqualTo(1);
                assertThat(unit.location().at("/titlePath/0").asText()).isEqualTo("第二章");
            });
            assertThat(parsed.sources()).anySatisfy(unit -> {
                assertThat(unit.text()).contains("```", "代码内容");
                assertThat(unit.location().path("codeLanguage").isNull()).isTrue();
                assertThat(unit.location().path("codeBlockId").asText()).isNotBlank();
            });
            assertThat(parsed.sources()).anySatisfy(unit -> {
                assertThat(unit.text()).contains("缩进代码");
                assertThat(unit.location().path("codeBlockId").asText()).isNotBlank();
            });
            assertThat(parsed.sources()).anySatisfy(unit -> {
                assertThat(unit.text()).contains("第一项");
                assertThat(unit.location().path("paragraphId").asText()).isNotBlank();
            });
            var referenceOnly =
                    parser.parse(text("markdown", "[引用]: https://example.invalid\n"), JSON.createObjectNode());
            assertThat(referenceOnly.sources()).hasSize(1);
            assertThat(referenceOnly.sources().getFirst().text()).contains("[引用]");
        }
    }

    private static DocumentSources parser(ApplicationData data, long maximum) {
        return new DocumentSources(new UploadConfiguration.Limits(1000000, 1000000, 10, 10, maximum), data, JSON);
    }

    private static FileService.VersionContent text(String format, String text) {
        return source(format, text.getBytes(StandardCharsets.UTF_8));
    }

    private static FileService.VersionContent source(String format, byte[] bytes) {
        var version = new FileService.Version(
                ID,
                ID,
                "固定资料",
                format,
                "application/octet-stream",
                bytes.length,
                "SHA-256",
                "固定摘要",
                null,
                "2026-11-14T00:00:00Z",
                true);
        var file = new FileService.FileView(
                ID, ID, "固定资料", null, "2026-11-14T00:00:00Z", "2026-11-14T00:00:00Z", "2026-11-14T00:00:00Z", version);
        return new FileService.VersionContent(file, version, bytes);
    }

    private static byte[] pdf(List<List<String>> pages) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (List<String> lines : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.setLeading(16);
                    content.newLineAtOffset(30, 700);
                    for (String line : lines) {
                        content.showText(line);
                        content.newLine();
                    }
                    content.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
