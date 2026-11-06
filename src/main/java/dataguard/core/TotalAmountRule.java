package dataguard.core;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/** A deliberately explicit sample business policy, not a universal e-commerce limit. */
public final class TotalAmountRule implements QualityRule {
    private static final BigDecimal MAX_TOTAL = new BigDecimal("100000.00");

    @Override
    public String name() {
        return "TOTAL_AMOUNT_POLICY";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            BigInteger quantity = NumericValues.integer(record.value("quantity"));
            BigDecimal price = NumericValues.decimal(record.value("unit_price"));
            if (NumericValues.validQuantity(quantity) && NumericValues.validPrice(price)
                    && new BigDecimal(quantity).multiply(price).compareTo(MAX_TOTAL) > 0) {
                issues.add(record.issue("quantity,unit_price", name(),
                        "Quantity multiplied by unit price exceeds the sample business policy of 100000.00; review required.",
                        Severity.WARNING));
            }
        }
        return issues;
    }
}
