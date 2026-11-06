package dataguard.core;

import java.util.ArrayList;
import java.util.List;

public final class RequiredFieldRule implements QualityRule {
    @Override
    public String name() {
        return "REQUIRED_FIELD";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            for (String field : QualityEngine.REQUIRED_FIELDS) {
                if (record.value(field).isBlank()) {
                    issues.add(record.issue(field, name(), "Required field is blank.", Severity.ERROR));
                }
            }
        }
        return issues;
    }
}
