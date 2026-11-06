package dataguard.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class StatusRule implements QualityRule {
    private static final Set<String> ALLOWED = Set.of("PENDING", "PAID", "SHIPPED", "CANCELLED");

    @Override
    public String name() {
        return "STATUS_ALLOWED";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            String status = record.value("status");
            if (!status.isBlank() && !ALLOWED.contains(status)) {
                issues.add(record.issue("status", name(),
                        "Status must be PENDING, PAID, SHIPPED, or CANCELLED (case-sensitive).", Severity.ERROR));
            }
        }
        return issues;
    }
}
