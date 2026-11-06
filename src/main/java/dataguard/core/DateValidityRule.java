package dataguard.core;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class DateValidityRule implements QualityRule {
    private static final DateTimeFormatter FORMAT = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);
    private static final Pattern SHAPE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}");

    @Override
    public String name() {
        return "DATE_VALIDITY";
    }

    @Override
    public List<QualityIssue> validate(List<DataRecord> records) {
        List<QualityIssue> issues = new ArrayList<>();
        for (DataRecord record : records) {
            String date = record.value("order_time");
            if (date.isBlank()) {
                continue;
            }
            try {
                if (!SHAPE.matcher(date).matches()) {
                    throw new DateTimeParseException("Unexpected timestamp format", date, 0);
                }
                LocalDateTime.parse(date, FORMAT);
            } catch (DateTimeParseException invalidDate) {
                issues.add(record.issue("order_time", name(),
                        "Expected an existing local date and time in yyyy-MM-dd'T'HH:mm:ss format (no timezone).", Severity.ERROR));
            }
        }
        return issues;
    }
}
