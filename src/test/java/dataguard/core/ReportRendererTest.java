package dataguard.core;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReportRendererTest {
    @Test
    void escapesEveryUntrustedStringIncludingFilenameRuleReasonAndSnapshot() {
        String attack = "<script>alert(\"x\" & 'y')</script>";
        QualityIssue issue = new QualityIssue(1, 2, attack, attack, attack, attack,
                Severity.ERROR, Map.of(attack, attack));
        QualityReport report = new QualityReport(attack, Instant.parse("2026-10-29T00:00:00Z"),
                List.of(attack), 1, 0, 1, 1, new BigDecimal("0.00"), Map.of(attack, 1L),
                Map.of(Severity.ERROR, 1L), List.of(issue));
        String html = new ReportRenderer().toHtml(report);
        assertFalse(html.contains("<script>"));
        assertFalse(html.contains(attack));
        assertTrue(html.contains("&lt;script&gt;alert(&quot;x&quot; &amp; &#39;y&#39;)&lt;/script&gt;"));
        assertTrue(html.contains("1 / 2"));
        assertTrue(html.contains("Records: 1"));
    }

    @Test
    void rendersNoIssuesAndExplainsScoreAndBusinessPolicy() {
        QualityReport report = new QualityReport("empty.csv", Instant.EPOCH, List.of("order_id"),
                0, 0, 0, 0, new BigDecimal("100.00"), Map.of(), Map.of(), List.of());
        String html = new ReportRenderer().toHtml(report);
        assertTrue(html.startsWith("<!doctype html>"));
        assertTrue(html.contains("Quality: 100.00%"));
        assertTrue(html.contains("No quality issues found."));
        assertTrue(html.contains("100000.00"));
        assertTrue(html.contains("header-only dataset"));
        assertTrue(html.contains("无数据可评估"));
        assertFalse(html.contains("通过当前规则检查"));
        assertTrue(html.contains("执行：检测完成"));
    }

    @Test
    void separatesSuccessfulExecutionFromAnErrorOrWarningOutcome() {
        for (Severity severity : Severity.values()) {
            QualityIssue issue = new QualityIssue(1, 2, "O1", "quantity", "RANGE", "Review value",
                    severity, Map.of("quantity", "100"));
            QualityReport report = new QualityReport("orders.csv", Instant.EPOCH, List.of("quantity"),
                    1, 0, 1, 1, BigDecimal.ZERO, Map.of("RANGE", 1L), Map.of(severity, 1L), List.of(issue));
            String html = new ReportRenderer().toHtml(report);
            assertTrue(html.contains("执行：检测完成"));
            assertTrue(html.contains("检测完成不代表数据合格"));
            assertTrue(html.contains(severity == Severity.ERROR ? "发现数据问题" : "存在待复核警告"));
            assertFalse(html.contains("通过当前规则检查"));
            assertTrue(html.contains("Quality: 0%"));
        }
    }

    @Test
    void onlyNonemptyCleanDataIsPresentedAsPassingCurrentRules() {
        QualityReport report = new QualityReport("clean.csv", Instant.EPOCH, List.of("order_id"),
                1, 1, 0, 0, new BigDecimal("100.00"), Map.of(), Map.of(), List.of());
        String html = new ReportRenderer().toHtml(report);
        assertTrue(html.contains("通过当前规则检查"));
        assertFalse(html.contains("无数据可评估"));
        assertTrue(html.contains("Quality: 100.00%"));
    }
}
