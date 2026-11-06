package dataguard.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QualityEngineTest {
    private static final String HEADER = String.join(",", QualityEngine.REQUIRED_FIELDS);
    private final QualityEngine engine = new QualityEngine();
    @TempDir
    Path directory;

    @Test
    void existingSampleHasIndependentlyCountedFindings() {
        QualityReport report = engine.inspect(Path.of("data/raw/orders.csv"));
        assertAll(
                () -> assertEquals(40, report.totalRecords()),
                () -> assertEquals(29, report.validRecords()),
                () -> assertEquals(11, report.invalidRecords()),
                () -> assertEquals(11, report.totalIssues()),
                () -> assertEquals(new BigDecimal("72.50"), report.qualityScore()),
                () -> assertEquals(Map.of("REQUIRED_FIELD", 4L, "DUPLICATE_ORDER", 2L,
                        "NUMERIC_RANGE", 3L, "DATE_VALIDITY", 1L, "STATUS_ALLOWED", 1L), report.issuesByRule()),
                () -> assertEquals(11L, report.issuesBySeverity().get(Severity.ERROR)),
                () -> assertEquals(0L, report.issuesBySeverity().get(Severity.WARNING)),
                () -> assertEquals(List.of(7L, 11L, 12L, 16L, 23L, 27L, 31L, 35L, 38L, 39L, 40L),
                        report.issues().stream().map(QualityIssue::recordNumber).toList())
        );
        QualityIssue invalidDate = report.issues().stream().filter(issue -> issue.recordNumber() == 31).findFirst().orElseThrow();
        assertEquals(32L, invalidDate.lineNumber());
        assertEquals("ORD0031", invalidDate.orderId());
        assertEquals("2026-02-30T16:30:00", invalidDate.record().get("order_time"));
    }

    @Test
    void headerOnlyIsAnEmptyDatasetNotAnEmptyFile() throws IOException {
        QualityReport report = inspect(HEADER + "\n");
        assertEquals(0, report.totalRecords());
        assertEquals(0, report.totalIssues());
        assertEquals(new BigDecimal("100.00"), report.qualityScore());
        assertEquals(QualityEngine.REQUIRED_FIELDS, report.fields());
    }

    @Test
    void acceptsBomAndReorderedHeaderWithoutLosingFieldMapping() throws IOException {
        QualityReport report = inspect("\uFEFFstatus,unit_price,quantity,order_time,product_id,customer_id,order_id\r\n"
                + "PAID,0.01,1,2024-02-29T23:59:59,P1,C1,O1\r\n");
        assertEquals(1, report.validRecords());
        assertEquals("status", report.fields().getFirst());
        assertTrue(report.issues().isEmpty());
    }

    @ParameterizedTest
    @MethodSource("badHeaders")
    void rejectsMissingDuplicateExtraOrMisspelledHeaders(String header) throws IOException {
        InspectionException failure = assertThrows(InspectionException.class, () -> inspect(header));
        assertEquals("INVALID_HEADER", failure.getCode());
        assertEquals(1L, failure.getLineNumber());
    }

    static Stream<String> badHeaders() {
        return Stream.of("order_id", HEADER + ",extra", HEADER.replace("status", "quantity"),
                HEADER.replace("status", "Status"), HEADER.replace("status", " status"), "\n");
    }

    @Test
    void completelyEmptyOrBomOnlyFileFailsExplicitly() throws IOException {
        for (String text : List.of("", "\uFEFF")) {
            InspectionException failure = assertThrows(InspectionException.class, () -> inspect(text));
            assertEquals("EMPTY_FILE", failure.getCode());
        }
    }

    @Test
    void missingFileAndDirectoryHaveSafeErrors() {
        InspectionException missing = assertThrows(InspectionException.class,
                () -> engine.inspect(directory.resolve("secret-directory/no-file.csv")));
        assertEquals("FILE_NOT_FOUND", missing.getCode());
        assertFalse(missing.getMessage().contains(directory.toString()));
        assertEquals("INVALID_FILE", assertThrows(InspectionException.class, () -> engine.inspect(directory)).getCode());
        assertEquals("INVALID_INPUT", assertThrows(InspectionException.class, () -> engine.inspect(null)).getCode());
    }

    @Test
    void rejectsInvalidUtf8RatherThanReplacingBytes() throws IOException {
        Path file = directory.resolve("invalid.csv");
        Files.write(file, new byte[]{(byte) 0xC3, (byte) 0x28});
        assertEquals("INVALID_ENCODING", assertThrows(InspectionException.class, () -> engine.inspect(file)).getCode());
    }

    @ParameterizedTest
    @MethodSource("malformedCsv")
    void rejectsMalformedQuotesWithLineLocation(String data) throws IOException {
        InspectionException failure = assertThrows(InspectionException.class, () -> inspect(HEADER + "\n" + data));
        assertEquals("MALFORMED_CSV", failure.getCode());
        assertNotNull(failure.getLineNumber());
        assertTrue(failure.getLineNumber() >= 2);
    }

    static Stream<String> malformedCsv() {
        return Stream.of("\"unterminated", "O1,C\"1,P1,1,1,2026-01-01T00:00:00,PAID",
                "\"O1\"bad,C1,P1,1,1,2026-01-01T00:00:00,PAID");
    }

    @Test
    void quotedCommasEscapedQuotesAndMultilineValuesRetainPhysicalStartLines() throws IOException {
        String csv = HEADER + "\r\n"
                + "O1,\"customer,\r\nname\"" + ",\"P\"\"1\",1,0.01,2026-02-30T00:00:00,PAID\r\n"
                + "O2,C2,P2,abc,1,2026-01-01T00:00:00,PAID\r\n";
        QualityReport report = inspect(csv);
        assertEquals(2, report.totalRecords());
        assertEquals(2, report.totalIssues());
        assertEquals(1L, report.issues().getFirst().recordNumber());
        assertEquals(2L, report.issues().getFirst().lineNumber());
        assertEquals("customer,\r\nname", report.issues().getFirst().record().get("customer_id"));
        assertEquals("P\"1", report.issues().getFirst().record().get("product_id"));
        assertEquals(4L, report.issues().getLast().lineNumber());
        assertEquals(2L, report.issues().getLast().recordNumber());
    }

    @Test
    void blankAndRaggedRowsRemainVisibleAndExtraValuesAreRetained() throws IOException {
        QualityReport report = inspect(HEADER + "\n\nshort,row\n" + row(Map.of()) + ",EXTRA\n"
                + row(Map.of("order_id", "O2")) + "\n");
        assertEquals(4, report.totalRecords());
        assertEquals(3, report.invalidRecords());
        assertEquals(1, report.validRecords());
        assertEquals(Map.of("CSV_STRUCTURE", 3L), report.issuesByRule());
        assertEquals(List.of(2L, 3L, 4L), report.issues().stream().map(QualityIssue::lineNumber).toList());
        assertEquals("EXTRA", report.issues().getLast().record().get("_extra_1"));
        assertEquals("short", report.issues().get(1).record().get("order_id"));
    }

    @Test
    void requiredValuesReportOncePerFieldAndWhitespaceIsMissing() throws IOException {
        QualityReport report = inspect(HEADER + "\n, ,\t,,,,\n");
        assertEquals(1, report.invalidRecords());
        assertEquals(7, report.totalIssues());
        assertEquals(Map.of("REQUIRED_FIELD", 7L), report.issuesByRule());
    }

    @Test
    void duplicateIdentifiersFlagEveryOccurrenceIncludingTheFirst() throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of()) + "\n"
                + row(Map.of("customer_id", "C2")) + "\n" + row(Map.of("order_id", "O2")));
        assertEquals(3, report.totalRecords());
        assertEquals(2, report.invalidRecords());
        assertEquals(Map.of("DUPLICATE_ORDER", 2L), report.issuesByRule());
        assertEquals(List.of(1L, 2L), report.issues().stream().map(QualityIssue::recordNumber).toList());
        assertEquals(new BigDecimal("33.33"), report.qualityScore());
    }

    @Test
    void duplicateCheckIncludesRaggedRowsWithoutLosingTheirStructureFindings() throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of()) + "\n"
                + row(Map.of("customer_id", "C2")) + ",EXTRA\nO1,C3\n");
        assertEquals(3, report.totalRecords());
        assertEquals(0, report.validRecords());
        assertEquals(3, report.invalidRecords());
        assertEquals(5, report.totalIssues());
        assertEquals(Map.of("DUPLICATE_ORDER", 3L, "CSV_STRUCTURE", 2L), report.issuesByRule());
        List<QualityIssue> duplicates = report.issues().stream()
                .filter(issue -> issue.rule().equals("DUPLICATE_ORDER")).toList();
        assertEquals(List.of(1L, 2L, 3L), duplicates.stream().map(QualityIssue::recordNumber).toList());
        assertEquals(List.of(2L, 3L, 4L), duplicates.stream().map(QualityIssue::lineNumber).toList());
        assertEquals("EXTRA", duplicates.get(1).record().get("_extra_1"));
        assertEquals("C3", duplicates.get(2).record().get("customer_id"));
        assertEquals(1, report.issues().stream().filter(issue -> issue.recordNumber() == 1).count());
        assertEquals(2, report.issues().stream().filter(issue -> issue.recordNumber() == 2).count());
        assertEquals(2, report.issues().stream().filter(issue -> issue.recordNumber() == 3).count());
    }

    @Test
    void raggedRecordWithoutAnAvailableReorderedOrderIdDoesNotInventADuplicate() throws IOException {
        QualityReport report = inspect("status,customer_id,product_id,quantity,unit_price,order_time,order_id\n"
                + "PAID,C1,P1,1,1,2026-01-01T00:00:00,O1\n"
                + "PAID,C1,P1,1,1,2026-01-01T00:00:00\n");
        assertEquals(1, report.validRecords());
        assertEquals(1, report.invalidRecords());
        assertEquals(Map.of("CSV_STRUCTURE", 1L), report.issuesByRule());
        assertEquals("", report.issues().getFirst().orderId());
        assertEquals(3L, report.issues().getFirst().lineNumber());
    }

    @ParameterizedTest
    @MethodSource("invalidNumbers")
    void invalidTypesDoNotAlsoProduceRangeFindings(String field, String value) throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of(field, value)));
        assertEquals(Map.of("FIELD_TYPE", 1L), report.issuesByRule());
        assertEquals(field, report.issues().getFirst().field());
    }

    static Stream<Arguments> invalidNumbers() {
        return Stream.of(Arguments.of("quantity", "1.5"), Arguments.of("quantity", "one"),
                Arguments.of("quantity", "9".repeat(101)), Arguments.of("unit_price", "NaN"),
                Arguments.of("unit_price", "Infinity"), Arguments.of("unit_price", "1e5"),
                Arguments.of("unit_price", "1e999999999"), Arguments.of("unit_price", "9".repeat(101)));
    }

    @ParameterizedTest
    @MethodSource("rangeCases")
    void numericBoundsUseExactDecimalArithmetic(String field, String value, boolean invalid) throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of(field, value)));
        assertEquals(invalid ? 1 : 0, report.invalidRecords());
        assertEquals(invalid ? Map.of("NUMERIC_RANGE", 1L) : Map.of(), report.issuesByRule());
    }

    static Stream<Arguments> rangeCases() {
        return Stream.of(Arguments.of("quantity", "0", true), Arguments.of("quantity", "1", false),
                Arguments.of("quantity", "100", false), Arguments.of("quantity", "101", true),
                Arguments.of("quantity", "999999999999999999999", true),
                Arguments.of("unit_price", "-0.01", true), Arguments.of("unit_price", "0.009999999999999999999", true),
                Arguments.of("unit_price", "0.01", false), Arguments.of("unit_price", "10000.00", false),
                Arguments.of("unit_price", "10000.000000000000001", true));
    }

    @ParameterizedTest
    @MethodSource("dateCases")
    void datesMustExistAndUseExactTimestampSyntax(String date, boolean invalid) throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of("order_time", date)));
        assertEquals(invalid ? Map.of("DATE_VALIDITY", 1L) : Map.of(), report.issuesByRule());
    }

    static Stream<Arguments> dateCases() {
        return Stream.of(Arguments.of("2024-02-29T23:59:59", false),
                Arguments.of("2026-02-29T00:00:00", true), Arguments.of("2026-02-30T00:00:00", true),
                Arguments.of("2026-01-01T24:00:00", true), Arguments.of("2026-01-01T00:00", true),
                Arguments.of("2026-01-01T00:00:00Z", true), Arguments.of("2026-1-01T00:00:00", true));
    }

    @Test
    void statusesAreCaseSensitiveAndWarningPolicyHasInclusiveBoundary() throws IOException {
        QualityReport invalidStatus = inspect(HEADER + "\n" + row(Map.of("status", "paid")));
        assertEquals(Map.of("STATUS_ALLOWED", 1L), invalidStatus.issuesByRule());
        for (String status : List.of("PAID", "PENDING", "SHIPPED", "CANCELLED")) {
            assertEquals(0, inspect(HEADER + "\n" + row(Map.of("status", status))).totalIssues());
        }
        QualityReport boundary = inspect(HEADER + "\n" + row(Map.of("quantity", "10", "unit_price", "10000")));
        assertEquals(0, boundary.totalIssues());
        QualityReport warning = inspect(HEADER + "\n" + row(Map.of("quantity", "11", "unit_price", "10000")));
        assertEquals(Map.of("TOTAL_AMOUNT_POLICY", 1L), warning.issuesByRule());
        assertEquals(Severity.WARNING, warning.issues().getFirst().severity());
        assertEquals(1, warning.invalidRecords());
        assertEquals(new BigDecimal("0.00"), warning.qualityScore());
    }

    @Test
    void severalIssuesOnOneRecordAreNotCountedAsSeveralInvalidRecords() throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of("customer_id", "", "quantity", "0", "status", "BAD")));
        assertEquals(1, report.totalRecords());
        assertEquals(1, report.invalidRecords());
        assertEquals(3, report.totalIssues());
        assertEquals(0, report.validRecords());
    }

    @Test
    void recordLimitIsEnforcedWithoutTruncatingTheReport() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        for (int i = 0; i < QualityEngine.MAX_RECORDS; i++) {
            csv.append(row(Map.of("order_id", "O" + i))).append('\n');
        }
        assertEquals(10_000, inspect(csv.toString()).validRecords());
        csv.append(row(Map.of("order_id", "EXCESS")));
        InspectionException failure = assertThrows(InspectionException.class, () -> inspect(csv.toString()));
        assertEquals("TOO_MANY_RECORDS", failure.getCode());
        assertEquals(10_002L, failure.getLineNumber());
    }

    @Test
    void fileSizeLimitIsEnforcedBeforeParsing() throws IOException {
        Path file = directory.resolve("oversized.csv");
        Files.write(file, new byte[QualityEngine.MAX_BYTES + 1]);
        assertEquals("FILE_TOO_LARGE", assertThrows(InspectionException.class, () -> engine.inspect(file)).getCode());
    }

    @Test
    void fieldLengthBoundaryIsInclusiveAndRejectsWithoutTruncation() throws IOException {
        String maximum = "C".repeat(QualityEngine.MAX_FIELD_CHARACTERS);
        QualityReport accepted = inspect(HEADER + "\n" + row(Map.of("customer_id", maximum, "status", "BAD")));
        assertEquals(maximum, accepted.issues().getFirst().record().get("customer_id"));
        InspectionException failure = assertThrows(InspectionException.class,
                () -> inspect(HEADER + "\n" + row(Map.of("customer_id", maximum + "X"))));
        assertEquals("INVALID_FIELD_LENGTH", failure.getCode());
        assertEquals(2L, failure.getLineNumber());
    }

    @Test
    void fieldLengthLimitAlsoCoversHeadersExtraColumnsAndQuotedMultilineCells() throws IOException {
        String oversized = "X".repeat(QualityEngine.MAX_FIELD_CHARACTERS + 1);
        InspectionException header = assertThrows(InspectionException.class,
                () -> inspect(HEADER.replace("order_id", oversized)));
        assertEquals("INVALID_FIELD_LENGTH", header.getCode());
        assertEquals(1L, header.getLineNumber());
        InspectionException extra = assertThrows(InspectionException.class,
                () -> inspect(HEADER + "\n" + row(Map.of()) + "," + oversized));
        assertEquals("INVALID_FIELD_LENGTH", extra.getCode());
        String multiline = HEADER + "\n" + row(Map.of()) + "\nO2,\"" + oversized + "\ntext\",P1,1,1,2026-01-01T00:00:00,PAID\n";
        InspectionException quoted = assertThrows(InspectionException.class, () -> inspect(multiline));
        assertEquals("INVALID_FIELD_LENGTH", quoted.getCode());
        assertEquals(3L, quoted.getLineNumber());
    }

    @Test
    void diagnosticCharacterBudgetFailsTheWholeInspectionInsteadOfReturningPartialIssues() throws IOException {
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        String largeCustomer = "C".repeat(QualityEngine.MAX_FIELD_CHARACTERS);
        for (int index = 0; index < 100; index++) {
            csv.append(row(Map.of("order_id", "O" + index, "customer_id", largeCustomer, "quantity", "0"))).append('\n');
        }
        QualityReport withinBudget = inspect(csv.toString());
        assertEquals(100, withinBudget.totalIssues());
        for (int index = 100; index < 300; index++) {
            csv.append(row(Map.of("order_id", "O" + index, "customer_id", largeCustomer, "quantity", "0"))).append('\n');
        }
        assertTrue(csv.toString().getBytes(StandardCharsets.UTF_8).length < QualityEngine.MAX_BYTES);
        InspectionException failure = assertThrows(InspectionException.class, () -> inspect(csv.toString()));
        assertEquals("REPORT_LIMIT_EXCEEDED", failure.getCode());
        assertNotNull(failure.getLineNumber());
        assertTrue(failure.getLineNumber() > 100);
    }

    @Test
    void manyIssuesAreRejectedRatherThanSilentlyLimitedOrDropped() throws IOException {
        String csv = HEADER + "\n" + ",,,,,,\n".repeat(2000);
        InspectionException failure = assertThrows(InspectionException.class, () -> inspect(csv));
        assertEquals("REPORT_LIMIT_EXCEEDED", failure.getCode());
        assertTrue(failure.getLineNumber() >= 2);
    }

    @Test
    void extremelyWideMalformedRecordIsRejectedBeforeBuildingItsHugeSnapshot() throws IOException {
        InspectionException failure = assertThrows(InspectionException.class,
                () -> inspect(HEADER + "\n" + ",".repeat(100_000)));
        assertEquals("REPORT_LIMIT_EXCEEDED", failure.getCode());
        assertEquals(2L, failure.getLineNumber());
    }

    @Test
    void reportAndSnapshotsAreImmutable() throws IOException {
        QualityReport report = inspect(HEADER + "\n" + row(Map.of("status", "BAD")));
        assertThrows(UnsupportedOperationException.class, () -> report.fields().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issuesByRule().clear());
        assertThrows(UnsupportedOperationException.class, () -> report.issues().getFirst().record().clear());
    }

    private QualityReport inspect(String csv) throws IOException {
        Path file = directory.resolve("orders.csv");
        Files.writeString(file, csv, StandardCharsets.UTF_8);
        return engine.inspect(file, "orders.csv");
    }

    private static String row(Map<String, String> overrides) {
        Map<String, String> values = new LinkedHashMap<>(Map.of("order_id", "O1", "customer_id", "C1",
                "product_id", "P1", "quantity", "1", "unit_price", "1.00",
                "order_time", "2026-01-01T00:00:00", "status", "PAID"));
        values.putAll(overrides);
        List<String> cells = new ArrayList<>();
        for (String field : QualityEngine.REQUIRED_FIELDS) {
            cells.add(values.get(field));
        }
        return String.join(",", cells);
    }
}
