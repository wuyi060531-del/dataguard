package dataguard.core;

import java.util.List;

/** Each rule inspects immutable records within its input scope and returns its own findings. */
public interface QualityRule {
    String name();

    List<QualityIssue> validate(List<DataRecord> records);
}
