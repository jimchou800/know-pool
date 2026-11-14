package cn.knowpool.knowledgebases;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ChunkPlansTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final ChunkPlans plans = new ChunkPlans(json);

    @Test
    void fixedChunksPreserveSupplementaryCharactersAndOriginalContent() {
        String text = "𠀀中文".repeat(1800);
        List<JsonNode> chunks = plans.build(document(text), config("fixed", 0), "fixed");
        assertTrue(chunks.size() > 1);
        assertEquals(
                text,
                String.join(
                        "",
                        chunks.stream()
                                .map(chunk -> chunk.path("text").asText())
                                .toList()));
        for (JsonNode chunk : chunks) {
            String body = chunk.path("text").asText();
            assertTrue(body.codePointCount(0, body.length()) <= 2000);
            assertFalse(Character.isLowSurrogate(body.charAt(0)));
            assertFalse(Character.isHighSurrogate(body.charAt(body.length() - 1)));
        }
    }

    @Test
    void maximumOverlapStillAdvancesAndKeepsEverySourceCharacter() {
        String text = "𠀀".repeat(2004);
        List<JsonNode> chunks = plans.build(document(text), config("fixed", 1999), "overlap");
        assertEquals(5, chunks.size());
        int covered = 0;
        for (JsonNode chunk : chunks) {
            JsonNode range = chunk.path("sourceRange");
            int start = range.path("utf16Start").asInt();
            int end = range.path("utf16EndExclusive").asInt();
            assertTrue(start <= covered);
            assertTrue(end > covered);
            assertEquals(text.substring(start, end), chunk.path("text").asText());
            assertEquals(2000, chunk.path("text").asText().codePointCount(0, end - start));
            covered = end;
        }
        assertEquals(text.length(), covered);
    }

    @Test
    void sentenceFallbackRetainsPunctuationAndReportsActualModes() {
        String text = "这是完整句子。".repeat(700);
        List<JsonNode> chunks = plans.build(document(text), config("structure", 0), "sentences");
        assertEquals(
                text,
                String.join(
                        "",
                        chunks.stream()
                                .map(chunk -> chunk.path("text").asText())
                                .toList()));
        ObjectNode report = plans.report(task(), chunks, List.of("固定限制说明"), true);
        assertEquals(chunks.size(), report.path("chunkCount").asInt());
        assertEquals(chunks.size() - 1, report.at("/fallbackCounts/sentence").asInt());
        assertTrue(report.path("integrityPassed").asBoolean());
        assertEquals("固定限制说明", report.path("warnings").get(0).asText());
        assertTrue(report.path("maximumLength").asInt() <= 2000);
    }

    @Test
    void emptyReportKeepsExplicitZeroStatisticsAndIntegrityResult() {
        ObjectNode report = plans.report(task(), List.of(), List.of(), false);
        assertTrue(report.path("emptyText").asBoolean());
        assertFalse(report.path("integrityPassed").asBoolean());
        for (String field : List.of("chunkCount", "minimumLength", "maximumLength", "medianLength", "p95Length"))
            assertEquals(0.0, report.path(field).asDouble());
        assertEquals(0, report.path("samples").size());
        assertEquals(0, report.path("fallbackCounts").size());
    }

    @Test
    void largeTableRetainsEveryCellAndCountsRepeatedHeadersInTheLimit() {
        String header = "|名称|说明|\n|---|---|\n";
        String text = header + "|大单元格|" + "𠀀".repeat(2400) + "|\n|后续行|保留正文|\n";
        ObjectNode location = document("").sources().getFirst().location().deepCopy();
        location.put("format", "markdown")
                .put("tableRowStart", 0)
                .put("tableRowEnd", 2)
                .put("tableColumnStart", 0)
                .put("tableColumnEnd", 1)
                .put("structureId", "table");
        var source = new DocumentSources.Source(text, location, null, 1);
        List<JsonNode> chunks =
                plans.build(new DocumentSources.Parsed(List.of(source), List.of()), config("structure", 0), "table");
        var reconstructed = new StringBuilder();
        boolean repeated = false, hard = false;
        for (JsonNode chunk : chunks) {
            String value = chunk.path("text").asText();
            JsonNode range = chunk.path("sourceRange");
            String prefix = range.path("injectedPrefix").asText();
            assertTrue(value.codePointCount(0, value.length()) <= 2000);
            assertTrue(value.startsWith(prefix));
            String original = text.substring(
                    range.path("utf16Start").asInt(),
                    range.path("utf16EndExclusive").asInt());
            assertEquals(prefix + original, value);
            reconstructed.append(original);
            repeated |= prefix.equals(header);
            for (JsonNode mode : chunk.path("fallbackModes"))
                hard |= mode.asText().equals("hard");
            assertTrue(chunk.at("/location/tableRowEnd").asInt() <= 2);
        }
        assertEquals(text, reconstructed.toString());
        assertTrue(repeated);
        assertTrue(hard);
    }

    @Test
    void pageStrategyKeepsTheSentenceAcrossThePageBoundary() {
        String first = "跨页句子的前半部分", second = "与后半部分。", tail = "下一句。";
        String text = first + second + tail;
        ObjectNode location = document("").sources().getFirst().location().deepCopy();
        location.put("format", "pdf").put("pageStart", 1).put("pageEnd", 2);
        var source = new DocumentSources.Source(text, location, new int[] {first.length(), text.length()}, 0);
        List<JsonNode> chunks =
                plans.build(new DocumentSources.Parsed(List.of(source), List.of()), config("page", 0), "pages");
        assertEquals(first + second, chunks.getFirst().path("text").asText());
        assertEquals(1, chunks.getFirst().at("/location/pageStart").asInt());
        assertEquals(2, chunks.getFirst().at("/location/pageEnd").asInt());
        assertEquals(
                text,
                String.join(
                        "",
                        chunks.stream()
                                .map(chunk -> chunk.path("text").asText())
                                .toList()));
    }

    private DocumentSources.Parsed document(String text) {
        ObjectNode location = json.createObjectNode()
                .put("format", "text")
                .put("lineStart", 1)
                .put("lineEnd", 1);
        location.putNull("tableRowStart").putNull("codeBlockId").putNull("structureId");
        return new DocumentSources.Parsed(List.of(new DocumentSources.Source(text, location, null, 1)), List.of());
    }

    private ObjectNode config(String strategy, int overlap) {
        return json.createObjectNode().put("strategy", strategy).put("overlapCodePoints", overlap);
    }

    private ObjectNode task() {
        return json.createObjectNode()
                .put("id", "00000000-0000-4000-8000-000000000001")
                .put("targetContentVersionId", "00000000-0000-4000-8000-000000000002")
                .put("targetConfigVersionId", "00000000-0000-4000-8000-000000000003");
    }
}
