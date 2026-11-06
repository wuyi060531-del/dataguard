package dataguard.core;

import java.util.ArrayList;
import java.util.List;

public final class FieldTypeRule implements QualityRule {
    @Override
    public String name() {
        return "FIELD_TYPE";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            String quantity = record.value("quantity");
            if (!quantity.isBlank() && NumericValues.integer(quantity) == null) {
                issues.add(record.issue("quantity", name(),
                        "Expected a base-10 integer of at most 100 characters.", Severity.ERROR));
            }
            String price = record.value("unit_price");
            if (!price.isBlank() && NumericValues.decimal(price) == null) {
                issues.add(record.issue("unit_price", name(),
                        "Expected a decimal of at most 100 characters; exponents and non-finite values are not accepted.",
                        Severity.ERROR));
            }
        }
        return issues;
    }
}
