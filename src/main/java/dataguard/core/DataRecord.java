package dataguard.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** An immutable CSV snapshot; raw cell text is retained for diagnosis. */
public record DataRecord(long recordNumber, long lineNumber, Map<String, String> values) {
    public DataRecord {
        values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public String value(String field) {
        return values.getOrDefault(field, "").strip();
    }

    public QualityIssue issue(String field, String rule, String message, Severity severity) {
        return new QualityIssue(recordNumber, lineNumber, value("order_id"), field,
                rule, message, severity, values);
    }
}
