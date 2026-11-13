package cn.knowpool.knowledgebases;

import cn.knowpool.datasets.FileService;
import cn.knowpool.infrastructure.ApiFailure;
import cn.knowpool.infrastructure.ApplicationData;
import cn.knowpool.infrastructure.PdfExtractionWorker;
import cn.knowpool.infrastructure.UploadConfiguration;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Code;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Heading;
import org.commonmark.node.IndentedCodeBlock;
import org.commonmark.node.ListBlock;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.SourceSpan;
import org.commonmark.node.Text;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class DocumentSources {
    static final String RULE_VERSION = "source-location-1/commonmark-0.32.0/pdfbox-3.0.6";
    private final UploadConfiguration.Limits limits;
    private final ApplicationData data;
    private final JsonMapper json;

    DocumentSources(UploadConfiguration.Limits limits, ApplicationData data, JsonMapper json) {
        this.limits = limits;
        this.data = data;
        this.json = json;
    }

    record Source(String text, ObjectNode location, int[] pageEnds, int lineStart) {}
    record Parsed(List<Source> sources, List<String> warnings, List<JsonNode> removals) {
        Parsed(List<Source> sources, List<String> warnings) { this(sources, warnings, List.of()); }
    }

    Parsed parse(FileService.VersionContent source, JsonNode config) throws IOException {
        String format = source.version().fileType();
        if (format.equals("pdf")) {
            List<String> pages = PdfExtractionWorker.extract(source.bytes(), data.directory().resolve("extraction"), limits);
            var removals = new ArrayList<JsonNode>();
            if (config.path("removePageHeadersFooters").asBoolean() && pages.size() > 1) {
                var headers = new java.util.HashMap<String, Integer>();
                var footers = new java.util.HashMap<String, Integer>();
                for (String page : pages) {
                    List<String> lines = page.lines().filter(line -> !line.isBlank()).toList();
                    if (lines.size() > 2) {
                        headers.merge(lines.getFirst(), 1, Integer::sum);
                        footers.merge(lines.getLast(), 1, Integer::sum);
                    }
                }
                var cleaned = new ArrayList<String>();
                for (int page = 0; page < pages.size(); page++) {
                    String original = pages.get(page);
                    List<String> lines = original.lines().filter(line -> !line.isBlank()).toList();
                    int first = 0, last = original.length();
                    if (lines.size() > 2 && headers.getOrDefault(lines.getFirst(), 0) >= Math.max(2, (pages.size()+1)/2)) {
                        first = original.indexOf(lines.getFirst()) + lines.getFirst().length();
                        if (first < original.length() && original.charAt(first) == '\n') first++;
                    }
                    if (lines.size() > 2 && footers.getOrDefault(lines.getLast(), 0) >= Math.max(2, (pages.size()+1)/2)) last = original.lastIndexOf(lines.getLast());
                    if (first > 0) removals.add(json.createObjectNode().put("page", page+1).put("utf16Start", 0).put("utf16EndExclusive", first).put("text", original.substring(0, first)));
                    if (last < original.length()) removals.add(json.createObjectNode().put("page", page+1).put("utf16Start", last).put("utf16EndExclusive", original.length()).put("text", original.substring(last)));
                    cleaned.add(original.substring(first, last));
                }
                pages = cleaned;
            }
            StringBuilder text = new StringBuilder();
            int[] ends = new int[pages.size()];
            for (int i = 0; i < pages.size(); i++) {
                text.append(pages.get(i));
                ends[i] = text.length();
            }
            ObjectNode location = location("pdf");
            location.put("pageStart", 1).put("pageEnd", pages.size());
            var warnings = new ArrayList<String>();
            warnings.add("PDF按文字几何位置提取，复杂多栏、图像正文及表格语义不保证恢复；页码始终保留原文件位置。");
            if (!removals.isEmpty()) warnings.add("已按显式配置删除多页首尾完全相同的重复行，删除内容及原页偏移已记录于来源映射。");
            return new Parsed(text.toString().isBlank() ? List.of() : List.of(new Source(text.toString(), location, ends, 0)), List.copyOf(warnings), List.copyOf(removals));
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source.bytes())).toString();
        if (text.startsWith("\ufeff")) text = text.substring(1);
        text = text.replace("\r\n", "\n").replace('\r', '\n');
        if (text.codePointCount(0, text.length()) > limits.maxExtractedCodePoints())
            throw new ApiFailure(422, "RESOURCE_LIMIT", "全文提取超过文字量限制。", null);
        if (text.isBlank()) return new Parsed(List.of(), List.of("资料正文为空，不能生成生效索引。"));
        if (format.equals("text")) return new Parsed(List.of(new Source(text, location("text"), null, 1)), List.of());
        if (config.path("strategy").asText().equals("fixed")) return new Parsed(List.of(new Source(text, location("markdown"), null, 1)), List.of());
        Parser parser = Parser.builder().includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
                .extensions(List.of(TablesExtension.create())).build();
        Node document = parser.parse(text);
        String[] lines = text.split("\n", -1);
        var result = new ArrayList<Source>();
        boolean[] covered = new boolean[lines.length];
        var titles = new ArrayList<String>();
        var levels = new ArrayList<Integer>();
        for (Node node = document.getFirstChild(); node != null; node = node.getNext()) {
            List<SourceSpan> spans = node.getSourceSpans();
            if (spans.isEmpty()) continue;
            int first = spans.getFirst().getLineIndex(), last = spans.getLast().getLineIndex();
            java.util.Arrays.fill(covered, first, last + 1, true);
            if (node instanceof Heading heading) {
                while (!levels.isEmpty() && levels.getLast() >= heading.getLevel()) {
                    levels.removeLast(); titles.removeLast();
                }
                levels.add(heading.getLevel());
                titles.add(label(node));
            }
            ObjectNode location = location("markdown");
            location.put("lineStart", first + 1).put("lineEnd", last + 1);
            location.set("titlePath", json.valueToTree(titles));
            String identity = identity(source.version().id(), node.getClass().getSimpleName(), first, last);
            location.put("structureId", identity).put("continuationIndex", 0);
            if (node instanceof Paragraph) location.put("paragraphId", identity);
            if (node instanceof FencedCodeBlock code) {
                String language = code.getInfo().strip().split("\\s+", 2)[0];
                if (!language.isEmpty()) location.put("codeLanguage", language);
                location.put("codeBlockId", identity);
            } else if (node instanceof IndentedCodeBlock) location.put("codeBlockId", identity);
            if (node instanceof org.commonmark.ext.gfm.tables.TableBlock) {
                int rows = 0, columns = 0;
                for (Node section = node.getFirstChild(); section != null; section = section.getNext())
                    for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                        rows++;
                        int count = 0;
                        for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) count++;
                        columns = Math.max(columns, count);
                    }
                if (rows > 0 && columns > 0) location.put("tableRowStart", 0).put("tableRowEnd", rows - 1)
                        .put("tableColumnStart", 0).put("tableColumnEnd", columns - 1);
            }
            if (node instanceof ListBlock) {
                addListItems(node, location, lines, source.version().id(), result);
                continue;
            }
            String block = String.join("\n", java.util.Arrays.copyOfRange(lines, first, last + 1));
            result.add(new Source(block, location, null, first + 1));
        }
        for (int line = 0; line < lines.length; line++) {
            if (!covered[line] && !lines[line].isBlank()) {
                ObjectNode location = location("markdown");
                location.put("lineStart", line + 1).put("lineEnd", line + 1);
                result.add(new Source(lines[line], location, null, line + 1));
            }
        }
        result.sort(java.util.Comparator.comparingInt(Source::lineStart));
        if (result.isEmpty()) return new Parsed(List.of(), List.of("Markdown没有可定位正文。"));
        return new Parsed(List.copyOf(result), List.of());
    }

    private void addListItems(Node node, ObjectNode container, String[] lines, String version, List<Source> result) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
            if (!(child instanceof ListItem)) continue;
            var spans = child.getSourceSpans();
            if (spans.isEmpty()) continue;
            int first = spans.getFirst().getLineIndex(), last = spans.getLast().getLineIndex();
            ObjectNode location = container.deepCopy();
            location.put("lineStart", first + 1).put("lineEnd", last + 1)
                    .put("paragraphId", identity(version, "list-item", first, last));
            result.add(new Source(String.join("\n", java.util.Arrays.copyOfRange(lines, first, last + 1)), location, null, first + 1));
        }
    }

    private String label(Node parent) {
        StringBuilder text = new StringBuilder();
        parent.accept(new AbstractVisitor() {
            @Override public void visit(Text node) { text.append(node.getLiteral()); }
            @Override public void visit(Code node) { text.append(node.getLiteral()); }
        });
        return text.toString();
    }

    private static String identity(String version, String kind, int first, int last) {
        return UUID.nameUUIDFromBytes((version + ":" + kind + ":" + first + ":" + last).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private ObjectNode location(String format) {
        ObjectNode location = json.createObjectNode().put("format", format);
        for (String field : List.of("pageStart", "pageEnd", "lineStart", "lineEnd", "paragraphId", "structureId", "continuationIndex", "tableRowStart", "tableRowEnd", "tableColumnStart", "tableColumnEnd", "codeLanguage", "codeBlockId"))
            location.putNull(field);
        location.set("titlePath", json.createArrayNode());
        return location;
    }

    JsonNode provenance(FileService.VersionContent source, Parsed parsed) {
        ObjectNode value = json.createObjectNode().put("fileId", source.file().id())
                .put("contentVersionId", source.version().id()).put("contentHash", source.version().contentHash())
                .put("ruleVersion", RULE_VERSION).put("normalization", "utf8-bom-crlf-1")
                .put("lengthMeter", "unicode-code-point-1");
        var units = value.putArray("sources");
        value.set("removedPageMargins", json.valueToTree(parsed.removals()));
        for (Source unit : parsed.sources()) {
            ObjectNode item = units.addObject();
            item.set("location", unit.location());
            item.put("utf16Length", unit.text().length()).put("text", unit.text());
            if (unit.pageEnds() != null) item.set("pageEnds", json.valueToTree(unit.pageEnds()));
        }
        return value;
    }
}
