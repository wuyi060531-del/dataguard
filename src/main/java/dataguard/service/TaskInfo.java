package dataguard.service;

import java.math.BigDecimal;
import java.time.Instant;

public record TaskInfo(String id, String datasetId, TaskStatus status, Instant createdAt,
                       Instant completedAt, String errorCode, String errorMessage,
                       Integer totalRecords, Integer validRecords, Integer invalidRecords,
                       Integer totalIssues, BigDecimal qualityScore) {
}
