package dataguard.core;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stateless CSV inspection. Input files are read only; no record is silently skipped. */
public final class QualityEngine {
    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_RECORDS = 10_000;
    public static final int MAX_FIELD_CHARACTERS = 4096;
    public static final int MAX_ISSUES = 10_000;
    public static final int MAX_REPORT_CHARACTERS = 1_000_000;
    public static final List<String> REQUIRED_FIELDS = List.of("order_id", "customer_id", "product_id",
            "quantity", "unit_price", "order_time", "status");
    private static final CSVFormat FORMAT = CSVFormat.RFC4180.builder()
            .setIgnoreEmptyLines(false).setLenientEof(false).setTrailingData(false).get();
    private final QualityRule duplicateRule = new DuplicateOrderRule();
    private final List<QualityRule> rules = List.of(new RequiredFieldRule(),
            new FieldTypeRule(), new NumericRangeRule(), new DateValidityRule(),
            new StatusRule(), new TotalAmountRule());

    public QualityReport inspect(Path input) {
        return inspect(input, input == null || input.getFileName() == null ? "orders.csv" : input.getFileName().toString());
    }

    public QualityReport inspect(Path input, String sourceName) {
        if (input == null || sourceName == null || sourceName.isBlank()) {
            throw new InspectionException("INVALID_INPUT", "Input file and source name are required.");
        }
        String text = readUtf8(input);
        validateQuotePlacement(text);
        List<DataRecord> allRecords = new ArrayList<>();
        List<DataRecord> validShape = new ArrayList<>();
        List<QualityIssue> issues = new ArrayList<>();
        IssueBudget issueBudget = new IssueBudget(issues);
        List<String> fields;
        int total = 0;
        try (CSVParser parser = FORMAT.parse(new StringReader(text))) {
            Iterator<CSVRecord> iterator = parser.iterator();
            LineLocator locator = new LineLocator(text);
            try {
                if (!iterator.hasNext()) {
                    throw new InspectionException("EMPTY_FILE", "CSV is empty and has no header.");
                }
                CSVRecord header = iterator.next();
                validateFieldLengths(header, 1);
                fields = header.toList();
                validateHeader(fields);
                while (iterator.hasNext()) {
                    CSVRecord csv = iterator.next();
                    if (++total > MAX_RECORDS) {
                        throw new InspectionException("TOO_MANY_RECORDS", "CSV exceeds the 10000-record limit.",
                                locator.lineAt(csv.getCharacterPosition()), null);
                    }
                    long line = locator.lineAt(csv.getCharacterPosition());
                    validateFieldLengths(csv, line);
                    Map<String, String> values = snapshot(csv, fields, line);
                    DataRecord record = new DataRecord(total, line, values);
                    allRecords.add(record);
                    if (csv.size() != fields.size()) {
                        issueBudget.add(record.issue("*", "CSV_STRUCTURE",
                                "Expected 7 columns but found " + csv.size() + "; check blank lines or missing/extra cells.",
                                Severity.ERROR));
                    } else {
                        validShape.add(record);
                    }
                }
            } catch (UncheckedIOException malformed) {
                throw new InspectionException("MALFORMED_CSV", "Malformed CSV quoting or record syntax.",
                        parser.getCurrentLineNumber(), malformed);
            }
        } catch (IOException malformed) {
            throw new InspectionException("MALFORMED_CSV", "Unable to parse CSV content.", null, malformed);
        }
        // A ragged row still participates when its order ID is available. Otherwise a
        // duplicate of that row could incorrectly make an ordinary row appear valid.
        for (QualityIssue issue : duplicateRule.validate(List.copyOf(allRecords))) {
            issueBudget.add(issue);
        }
        List<DataRecord> records = List.copyOf(validShape);
        for (QualityRule rule : rules) {
            for (QualityIssue issue : rule.validate(records)) {
                issueBudget.add(issue);
            }
        }
        issues.sort(Comparator.comparingLong(QualityIssue::recordNumber)
                .thenComparing(QualityIssue::rule).thenComparing(QualityIssue::field));
        return summarize(sourceName, fields, total, issues);
    }

    private String readUtf8(Path input) {
        try {
            if (!Files.isRegularFile(input)) {
                if (!Files.exists(input)) {
                    throw new InspectionException("FILE_NOT_FOUND", "Input file does not exist.");
                }
                throw new InspectionException("INVALID_FILE", "Input must be a regular CSV file.");
            }
            if (Files.size(input) > MAX_BYTES) {
                throw new InspectionException("FILE_TOO_LARGE", "CSV exceeds the 5 MiB limit.");
            }
            byte[] bytes;
            try (InputStream stream = Files.newInputStream(input)) {
                bytes = stream.readNBytes(MAX_BYTES + 1);
            }
            if (bytes.length > MAX_BYTES) {
                throw new InspectionException("FILE_TOO_LARGE", "CSV exceeds the 5 MiB limit.");
            }
            String decoded = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            return decoded.startsWith("\uFEFF") ? decoded.substring(1) : decoded;
        } catch (CharacterCodingException invalidEncoding) {
            throw new InspectionException("INVALID_ENCODING", "CSV must contain valid UTF-8 text.", null, invalidEncoding);
        } catch (NoSuchFileException missing) {
            throw new InspectionException("FILE_NOT_FOUND", "Input file does not exist.", null, missing);
        } catch (IOException unreadable) {
            throw new InspectionException("FILE_UNREADABLE", "Unable to read the input CSV file.", null, unreadable);
        }
    }

    private void validateHeader(List<String> fields) {
        if (fields.size() != REQUIRED_FIELDS.size()
                || new HashSet<>(fields).size() != fields.size()
                || !new HashSet<>(fields).equals(new HashSet<>(REQUIRED_FIELDS))) {
            throw new InspectionException("INVALID_HEADER",
                    "CSV must contain each of the seven required headers exactly once, with no extra fields.", 1L, null);
        }
    }

    private void validateFieldLengths(CSVRecord record, long line) {
        for (String value : record) {
            if (value.length() > MAX_FIELD_CHARACTERS) {
                throw new InspectionException("INVALID_FIELD_LENGTH",
                        "Each CSV field must contain at most 4096 characters; no content was truncated.", line, null);
            }
        }
    }

    /** Commons CSV accepts quotes inside unquoted cells; this V1 contract deliberately rejects them. */
    private void validateQuotePlacement(String text) {
        boolean fieldStart = true;
        boolean quoted = false;
        boolean afterQuote = false;
        long line = 1;
        for (int index = 0; index < text.length(); index++) {
            char current = text.charAt(index);
            if (quoted) {
                if (current == '"') {
                    if (index + 1 < text.length() && text.charAt(index + 1) == '"') {
                        index++;
                    } else {
                        quoted = false;
                        afterQuote = true;
                    }
                }
            } else if (current == ',' || current == '\r' || current == '\n') {
                fieldStart = true;
                afterQuote = false;
            } else if (current == '"' && fieldStart && !afterQuote) {
                quoted = true;
                fieldStart = false;
            } else {
                if (current == '"' || afterQuote) {
                    throw new InspectionException("MALFORMED_CSV", "CSV contains a quote outside a quoted field or text after its closing quote.", line, null);
                }
                fieldStart = false;
            }
            if (current == '\r' || current == '\n' && (index == 0 || text.charAt(index - 1) != '\r')) {
                line++;
            }
        }
        if (quoted) {
            throw new InspectionException("MALFORMED_CSV", "CSV contains an unterminated quoted field.", line, null);
        }
    }

    private Map<String, String> snapshot(CSVRecord record, List<String> fields, long line) {
        // Check before allocating a map: a small file containing millions of empty extra columns
        // must not allocate a correspondingly huge diagnostic snapshot only to reject it later.
        long snapshotCharacters = 0;
        for (int index = 0; index < Math.max(record.size(), fields.size()); index++) {
            String key = index < fields.size() ? fields.get(index) : "_extra_" + (index - fields.size() + 1);
            snapshotCharacters += (long) key.length() + (index < record.size() ? record.get(index).length() : 0);
            if (snapshotCharacters > MAX_REPORT_CHARACTERS) {
                throw new InspectionException("REPORT_LIMIT_EXCEEDED",
                        "Record snapshot exceeds the 1000000-character diagnostic budget; reduce the input.", line, null);
            }
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int index = 0; index < fields.size(); index++) {
            values.put(fields.get(index), index < record.size() ? record.get(index) : "");
        }
        for (int index = fields.size(); index < record.size(); index++) {
            values.put("_extra_" + (index - fields.size() + 1), record.get(index));
        }
        return values;
    }

    private QualityReport summarize(String sourceName, List<String> fields, int total, List<QualityIssue> issues) {
        int invalid = (int) issues.stream().map(QualityIssue::recordNumber).distinct().count();
        Map<String, Long> byRule = new LinkedHashMap<>();
        Map<Severity, Long> bySeverity = new LinkedHashMap<>();
        bySeverity.put(Severity.ERROR, 0L);
        bySeverity.put(Severity.WARNING, 0L);
        for (QualityIssue issue : issues) {
            byRule.merge(issue.rule(), 1L, Long::sum);
            bySeverity.merge(issue.severity(), 1L, Long::sum);
        }
        BigDecimal score = total == 0 ? new BigDecimal("100.00")
                : BigDecimal.valueOf((long) (total - invalid) * 100).divide(BigDecimal.valueOf(total), 2, RoundingMode.HALF_UP);
        return new QualityReport(sourceName, Instant.now(), fields, total, total - invalid,
                invalid, issues.size(), score, byRule, bySeverity, issues);
    }

    /** Reject amplification as a file-level failure; a partial report must never appear complete. */
    private static final class IssueBudget {
        private final List<QualityIssue> issues;
        private long characters;

        private IssueBudget(List<QualityIssue> issues) {
            this.issues = issues;
        }

        private void add(QualityIssue issue) {
            long added = (long) issue.orderId().length() + issue.message().length() + issue.field().length();
            for (Map.Entry<String, String> entry : issue.record().entrySet()) {
                added += (long) entry.getKey().length() + entry.getValue().length();
            }
            if (issues.size() >= MAX_ISSUES || characters + added > MAX_REPORT_CHARACTERS) {
                throw new InspectionException("REPORT_LIMIT_EXCEEDED",
                        "Report exceeds 10000 issues or 1000000 diagnostic characters; reduce the input and inspect it in smaller batches.",
                        issue.lineNumber(), null);
            }
            characters += added;
            issues.add(issue);
        }
    }

    /** CSV character offsets increase monotonically, so physical line mapping needs only one scan. */
    private static final class LineLocator {
        private final String text;
        private int offset;
        private long line = 1;

        private LineLocator(String text) {
            this.text = text;
        }

        long lineAt(long position) {
            while (offset < position && offset < text.length()) {
                char current = text.charAt(offset);
                if (current == '\r' || current == '\n' && (offset == 0 || text.charAt(offset - 1) != '\r')) {
                    line++;
                }
                offset++;
            }
            return line;
        }
    }
}
