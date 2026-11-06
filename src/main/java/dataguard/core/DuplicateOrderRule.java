package dataguard.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** All parsed rows with an available order ID participate, including ragged records. */
public final class DuplicateOrderRule implements QualityRule {
    @Override
    public String name() {
        return "DUPLICATE_ORDER";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        Map<String, Long> counts = new HashMap<>();
        for (DataRecord record : records) {
            String orderId = record.value("order_id");
            if (!orderId.isBlank()) {
                counts.merge(orderId, 1L, Long::sum);
            }
        }
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            long count = counts.getOrDefault(record.value("order_id"), 0L);
            if (count > 1) {
                issues.add(record.issue("order_id", name(),
                        "Order ID occurs " + count + " times; every occurrence requires review.", Severity.ERROR));
            }
        }
        return issues;
    }
}
