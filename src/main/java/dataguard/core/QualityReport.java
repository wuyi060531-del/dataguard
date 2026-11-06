package dataguard.core;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Invalid records include records with either errors or warnings; issues are not records. */
public record QualityReport(String sourceName, Instant generatedAt, List<String> fields,
                            int totalRecords, int validRecords, int invalidRecords,
                            int totalIssues, BigDecimal qualityScore,
                            Map<String, Long> issuesByRule,
                            Map<Severity, Long> issuesBySeverity,
                            List<QualityIssue> issues) {
    public QualityReport {
        fields = List.copyOf(fields);
        issuesByRule = Collections.unmodifiableMap(new LinkedHashMap<>(issuesByRule));
        issuesBySeverity = Collections.unmodifiableMap(new LinkedHashMap<>(issuesBySeverity));
        issues = List.copyOf(issues);
    }
}
