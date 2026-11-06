package dataguard.benchmark;

import tools.jackson.databind.json.JsonMapper;
import dataguard.core.InspectionException;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Explicit offline tool, never a Surefire test or a business endpoint. One input per JVM. */
public final class EngineBenchmarkHarness {
    private EngineBenchmarkHarness() { }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException("Usage: EngineBenchmarkHarness <csv>");
        }
        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long before = memory.getHeapMemoryUsage().getUsed();
        AtomicLong sampledPeak = new AtomicLong(before);
        ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(work -> {
            Thread thread = new Thread(work, "benchmark-heap-sampler");
            thread.setDaemon(true);
            return thread;
        });
        sampler.scheduleAtFixedRate(() -> sampledPeak.accumulateAndGet(memory.getHeapMemoryUsage().getUsed(), Math::max),
                0, 10, TimeUnit.MILLISECONDS);
        Map<String, Object> result = new LinkedHashMap<>();
        long started = System.nanoTime();
        try {
            QualityReport report = new QualityEngine().inspect(Path.of(arguments[0]));
            result.put("outcome", "INSPECTED");
            result.put("errorCode", null);
            result.put("lineNumber", null);
            result.put("totalRecords", report.totalRecords());
            result.put("validRecords", report.validRecords());
            result.put("invalidRecords", report.invalidRecords());
            result.put("totalIssues", report.totalIssues());
            result.put("qualityScore", report.qualityScore());
            result.put("issuesByRule", report.issuesByRule());
            result.put("issuesBySeverity", report.issuesBySeverity());
        } catch (InspectionException rejected) {
            result.put("outcome", "REJECTED");
            result.put("errorCode", rejected.getCode());
            result.put("lineNumber", rejected.getLineNumber());
        } finally {
            result.put("engineMillis", (System.nanoTime() - started) / 1_000_000.0);
            long after = memory.getHeapMemoryUsage().getUsed();
            sampledPeak.accumulateAndGet(after, Math::max);
            sampler.shutdownNow();
            result.put("heapUsedBeforeBytes", before);
            result.put("heapUsedAfterBytes", after);
            result.put("heapSampledPeakBytes", sampledPeak.get());
            result.put("heapCommittedAfterBytes", memory.getHeapMemoryUsage().getCommitted());
            result.put("heapMaxBytes", memory.getHeapMemoryUsage().getMax());
        }
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        result.put("heapSampleIntervalMillis", 10);
        result.put("limits", Map.of("fileBytes", QualityEngine.MAX_BYTES, "records", QualityEngine.MAX_RECORDS,
                "fieldCharacters", QualityEngine.MAX_FIELD_CHARACTERS, "issues", QualityEngine.MAX_ISSUES,
                "diagnosticCharacters", QualityEngine.MAX_REPORT_CHARACTERS));
        System.out.println(JsonMapper.builder().findAndAddModules().build().writeValueAsString(result));
    }
}
