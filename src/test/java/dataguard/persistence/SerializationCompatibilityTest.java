package dataguard.persistence;

import dataguard.api.ApiError;
import dataguard.core.QualityReport;
import dataguard.core.Severity;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The fixture is actual Boot 3.5/Jackson 2 report_json from an isolated legacy H2 database. */
class SerializationCompatibilityTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsActualJackson2ReportWithoutLosingCountersLocationsOrDecimalScale() throws Exception {
        QualityReport report = legacyReport();
        assertEquals(40, report.totalRecords());
        assertEquals(29, report.validRecords());
        assertEquals(11, report.invalidRecords());
        assertEquals(11, report.issues().size());
        assertEquals(new BigDecimal("72.50"), report.qualityScore());
        assertEquals(11L, report.issuesBySeverity().get(Severity.ERROR));
        assertEquals(4L, report.issuesByRule().get("REQUIRED_FIELD"));
        assertEquals(7L, report.issues().getFirst().recordNumber());
        assertEquals(8L, report.issues().getFirst().lineNumber());
        assertEquals("ORD0007", report.issues().getFirst().record().get("order_id"));
    }

    @Test
    void writesIsoInstantAndRoundTripsTheCompleteLegacyReport() throws Exception {
        QualityReport report = legacyReport();
        String json = mapper.writeValueAsString(report);
        assertTrue(mapper.readTree(json).path("generatedAt").isTextual());
        assertEquals(report.generatedAt(), Instant.parse(mapper.readTree(json).path("generatedAt").asString()));
        assertEquals(report, mapper.readValue(json, QualityReport.class));
    }

    @Test
    void errorEnvelopeKeepsStableFieldNamesUnicodeAndIsoTimestamp() {
        var error = new ApiError(Instant.parse("2026-11-06T00:00:00Z"), 400, "INVALID_REQUEST",
                "数量无效", "/api/tasks", Map.of("datasetId", "需要 UUID"));
        var tree = mapper.readTree(mapper.writeValueAsString(error));
        assertEquals(6, tree.size());
        assertEquals("2026-11-06T00:00:00Z", tree.path("timestamp").asString());
        assertEquals(400, tree.path("status").asInt());
        assertEquals("INVALID_REQUEST", tree.path("code").asString());
        assertEquals("数量无效", tree.path("message").asString());
        assertEquals("/api/tasks", tree.path("path").asString());
        assertEquals("需要 UUID", tree.path("details").path("datasetId").asString());
    }

    private QualityReport legacyReport() throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/boot35-report.json")) {
            assertNotNull(input);
            return mapper.readValue(new String(input.readAllBytes(), StandardCharsets.UTF_8), QualityReport.class);
        }
    }
}
