package dataguard.service;

import java.time.Instant;

/** Public dataset metadata. Managed filesystem paths are deliberately not exposed. */
public record DatasetInfo(String id, String fileName, long sizeBytes, String sha256, Instant createdAt) {
}
