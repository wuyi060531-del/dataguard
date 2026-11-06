package dataguard.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Record numbers exclude the header; line numbers are physical CSV start lines. */
public record QualityIssue(long recordNumber, long lineNumber, String orderId,
                           String field, String rule, String message,
                           Severity severity, Map<String, String> record) {
    public QualityIssue {
        record = Collections.unmodifiableMap(new LinkedHashMap<>(record));
    }
}
