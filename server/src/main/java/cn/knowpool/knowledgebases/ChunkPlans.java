package cn.knowpool.knowledgebases;

import cn.knowpool.infrastructure.ApiFailure;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
final class ChunkPlans {
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private cn.knowpool.infrastructure.RuntimeSettings runtime;

    static final String RULE_VERSION = "structure-fallback-1";
    private static final int LIMIT = 2000;
    private final JsonMapper json;

    ChunkPlans(JsonMapper json) {
        this.json = json;
    }

    List<JsonNode> build(DocumentSources.Parsed document, JsonNode config, String index) {
        var chunks = new ArrayList<JsonNode>();
        var sequences = new java.util.HashMap<String, Integer>();
        int limit = (int) Math.min(
                Integer.MAX_VALUE,
                config.path("runtimeChunkHardLimitCodePoints")
                        .asLong(runtime == null ? LIMIT : runtime.number("chunkHardLimitCodePoints")));
        int unit = 0;
        for (DocumentSources.Source source : document.sources()) {
            String text = source.text();
            int start = 0, ordinal = 0, previousEnd = 0;
            while (start < text.length()) {
                var modes = new ArrayList<String>();
                boolean table = !source.location().path("tableRowStart").isNull();
                boolean code = !source.location().path("codeBlockId").isNull();
                boolean fixed = config.path("strategy").asText().equals("fixed");
                String prefix = "";
                if (table && start > 0) {
                    int headerEnd = text.indexOf('\n');
                    if (headerEnd >= 0) {
                        int separatorEnd = text.indexOf('\n', headerEnd + 1);
                        String header = text.substring(0, separatorEnd < 0 ? headerEnd + 1 : separatorEnd + 1);
                        if (header.codePointCount(0, header.length()) < limit / 2) prefix = header;
                    }
                }
                int available = limit - prefix.codePointCount(0, prefix.length());
                int end =
                        text.offsetByCodePoints(start, Math.min(available, text.codePointCount(start, text.length())));
                if (config.path("strategy").asText().equals("page") && source.pageEnds() != null) {
                    int pageEnd = text.length();
                    for (int boundary : source.pageEnds())
                        if (boundary > start) {
                            pageEnd = boundary;
                            break;
                        }
                    if (pageEnd < end) {
                        for (int boundary : Indexes.sentenceEnds(text)) {
                            if (boundary >= pageEnd && boundary <= end) {
                                end = boundary;
                                break;
                            }
                        }
                    }
                }
                if (end < text.length() && !fixed) {
                    if (table || code) {
                        int line = text.lastIndexOf('\n', end - 1) + 1;
                        modes.add(table ? "table_row" : "code_line");
                        if (line > start) {
                            end = line;
                        } else if (table) {
                            modes.add("table_cell");
                            int cell = text.lastIndexOf('|', end - 1) + 1;
                            if (cell > start) {
                                end = cell;
                            } else modes.add("hard");
                        } else modes.add("hard");
                    } else {
                        List<Integer> sentences = Indexes.sentenceEnds(text);
                        int boundary = start;
                        for (int candidate : sentences) if (candidate > start && candidate <= end) boundary = candidate;
                        if (boundary > start) {
                            end = boundary;
                            modes.add("sentence");
                        } else {
                            int delimiter = start;
                            for (int cursor = start; cursor < end; ) {
                                int cp = text.codePointAt(cursor);
                                cursor += Character.charCount(cp);
                                if ("，、；,;：:".indexOf(cp) >= 0) delimiter = cursor;
                            }
                            if (delimiter > start) {
                                end = delimiter;
                                modes.add("delimiter");
                            } else modes.add("hard");
                        }
                    }
                }
                if (end <= previousEnd) {
                    start = previousEnd;
                    continue;
                }
                if (end <= start) throw new ApiFailure(500, "INTEGRITY_FAILED", "切片未推进，已停止发布。", null);
                String body = text.substring(start, end);
                ObjectNode location = source.location().deepCopy();
                if (source.pageEnds() != null) {
                    int first = 0,
                            last = 0,
                            pageBase = location.path("pageStart").asInt(1);
                    while (first < source.pageEnds().length - 1 && source.pageEnds()[first] <= start) first++;
                    while (last < source.pageEnds().length - 1 && source.pageEnds()[last] < end) last++;
                    location.put("pageStart", pageBase + first).put("pageEnd", pageBase + last);
                } else {
                    int first = source.lineStart()
                            + (int) text.substring(0, start)
                                    .chars()
                                    .filter(c -> c == '\n')
                                    .count();
                    int last = first
                            + (int) body.substring(0, Math.max(0, body.length() - 1))
                                    .chars()
                                    .filter(c -> c == '\n')
                                    .count();
                    location.put("lineStart", first).put("lineEnd", last);
                    if (table) {
                        location.put("tableRowStart", tableRow(first - source.lineStart()))
                                .put("tableRowEnd", tableRow(last - source.lineStart()));
                        int lineStart = text.lastIndexOf('\n', Math.max(0, start - 1)) + 1;
                        int column = Math.max(
                                0,
                                (int) text.substring(lineStart, start)
                                                .chars()
                                                .filter(c -> c == '|')
                                                .count()
                                        - 1);
                        if (!body.contains("\n")) {
                            String line = text.substring(
                                    lineStart,
                                    text.indexOf('\n', lineStart) < 0 ? text.length() : text.indexOf('\n', lineStart));
                            if (line.contains("\\|") || line.contains("`")) {
                                location.putNull("tableColumnStart").putNull("tableColumnEnd");
                            } else {
                                int maximum =
                                        source.location().path("tableColumnEnd").asInt();
                                location.put("tableColumnStart", Math.min(maximum, column))
                                        .put(
                                                "tableColumnEnd",
                                                Math.min(
                                                        maximum,
                                                        column
                                                                + Math.max(
                                                                        0,
                                                                        (int) body.chars()
                                                                                        .filter(c -> c == '|')
                                                                                        .count()
                                                                                - 1)));
                            }
                        }
                    }
                }
                String structure = location.path("structureId").asText("");
                if (!structure.isEmpty()) {
                    int sequence = sequences.getOrDefault(structure, 0);
                    location.put("continuationIndex", sequence);
                    sequences.put(structure, sequence + 1);
                } else if (location.path("format").asText().equals("text")) location.put("continuationIndex", ordinal);
                ObjectNode chunk = json.createObjectNode()
                        .put(
                                "id",
                                UUID.nameUUIDFromBytes(
                                                (index + ":" + unit + ":" + ordinal++).getBytes(StandardCharsets.UTF_8))
                                        .toString())
                        .put("text", prefix + body);
                chunk.set("location", location);
                chunk.set("fallbackModes", json.valueToTree(modes));
                chunk.set(
                        "sourceRange",
                        json.createObjectNode()
                                .put("sourceOrdinal", unit)
                                .put("utf16Start", start)
                                .put("utf16EndExclusive", end)
                                .put("injectedPrefix", prefix)
                                .put("ruleVersion", RULE_VERSION));
                chunks.add(chunk);
                previousEnd = end;
                if (end == text.length()) break;
                int next = end;
                if (!table && !code) {
                    int overlap = Math.min(
                            config.path("overlapCodePoints").asInt(), Math.max(0, text.codePointCount(start, end) - 1));
                    if (fixed) next = text.offsetByCodePoints(end, -overlap);
                    else {
                        int earliest = text.offsetByCodePoints(end, -overlap);
                        for (int boundary : Indexes.sentenceEnds(text)) {
                            if (boundary >= earliest && boundary < end && boundary > start) {
                                next = boundary;
                                break;
                            }
                        }
                    }
                }
                start = next;
            }
            unit++;
        }
        return chunks;
    }

    private static int tableRow(int sourceLine) {
        return sourceLine <= 1 ? 0 : sourceLine - 1;
    }

    ObjectNode report(JsonNode task, List<JsonNode> chunks, List<String> warnings, boolean integrityPassed) {
        ObjectNode report = json.createObjectNode()
                .put("taskId", task.path("id").asText())
                .put("contentVersionId", task.path("targetContentVersionId").asText())
                .put("configVersionId", task.path("targetConfigVersionId").asText())
                .put("lengthMeterVersion", "unicode-code-points-1")
                .put("chunkCount", chunks.size())
                .put("emptyText", chunks.isEmpty())
                .put("integrityPassed", integrityPassed);
        var lengths = chunks.stream()
                .mapToInt(chunk -> chunk.path("text")
                        .asText()
                        .codePointCount(0, chunk.path("text").asText().length()))
                .sorted()
                .toArray();
        report.put("minimumLength", lengths.length == 0 ? 0 : lengths[0])
                .put("maximumLength", lengths.length == 0 ? 0 : lengths[lengths.length - 1]);
        report.put(
                        "medianLength",
                        lengths.length == 0
                                ? 0
                                : (lengths[(lengths.length - 1) / 2] + lengths[lengths.length / 2]) / 2.0)
                .put("p95Length", lengths.length == 0 ? 0 : lengths[(int) Math.ceil(lengths.length * .95) - 1]);
        ObjectNode counts = report.putObject("fallbackCounts");
        var samples = report.putArray("samples");
        for (JsonNode chunk : chunks) {
            for (JsonNode mode : chunk.path("fallbackModes"))
                counts.put(mode.asText(), counts.path(mode.asText()).asInt() + 1);
            if (samples.size() < 20) {
                ObjectNode sample = samples.addObject()
                        .put("chunkId", chunk.path("id").asText())
                        .put("text", chunk.path("text").asText())
                        .put(
                                "codePointCount",
                                chunk.path("text")
                                        .asText()
                                        .codePointCount(
                                                0, chunk.path("text").asText().length()));
                sample.set("location", chunk.path("location"));
                sample.set("fallbackModes", chunk.path("fallbackModes"));
            }
        }
        report.set("warnings", json.valueToTree(warnings));
        return report;
    }
}
