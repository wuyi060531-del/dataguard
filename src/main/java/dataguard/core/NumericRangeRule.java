package dataguard.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public final class NumericRangeRule implements QualityRule {
    @Override
    public String name() {
        return "NUMERIC_RANGE";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            BigInteger quantity = NumericValues.integer(record.value("quantity"));
            if (quantity != null && !NumericValues.validQuantity(quantity)) {
                issues.add(record.issue("quantity", name(), "Quantity must be between 1 and 100, inclusive.", Severity.ERROR));
            }
            BigDecimal price = NumericValues.decimal(record.value("unit_price"));
            if (price != null && !NumericValues.validPrice(price)) {
                issues.add(record.issue("unit_price", name(),
                        "Unit price must be between 0.01 and 10000.00, inclusive.", Severity.ERROR));
            }
        }
        return issues;
    }
}
