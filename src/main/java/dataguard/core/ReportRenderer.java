package dataguard.core;

import java.util.Map;

/** Self-contained readable report; all values, including snapshots and filenames, are escaped. */
public final class ReportRenderer {
    public String toHtml(QualityReport report) {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>DataGuard Quality Report</title>
                <style>body{font:16px system-ui,sans-serif;max-width:1100px;margin:2rem auto;padding:0 1rem;color:#172b4d}
                table{border-collapse:collapse;width:100%;margin:1rem 0}th,td{border:1px solid #d9e2ec;padding:.6rem;text-align:left;vertical-align:top}
                th{background:#f0f4f8}code,pre{white-space:pre-wrap;overflow-wrap:anywhere}small{color:#52606d}.summary{background:#f0f4f8;padding:1rem}</style>
                </head><body><h1>DataGuard — Data Quality Report</h1>
                """);
        html.append("<p>Source: <strong>").append(escape(report.sourceName())).append("</strong><br>Generated: ")
                .append(escape(report.generatedAt().toString())).append("</p><div class=\"summary\">")
                .append("Records: ").append(report.totalRecords()).append(" · Valid: ").append(report.validRecords())
                .append(" · Invalid: ").append(report.invalidRecords()).append(" · Issues: ").append(report.totalIssues())
                .append(" · Quality: ").append(escape(report.qualityScore().toPlainString())).append("%</div>")
                .append("<p>Quality = valid records / total records × 100. A record with any ERROR or WARNING is invalid; ")
                .append("a header-only dataset has quality 100.00%. Issue counts can exceed invalid record counts.</p>")
                .append("<p>Fields: ").append(escape(String.join(", ", report.fields()))).append("</p>")
                .append("<h2>Rules</h2><table><thead><tr><th>Rule</th><th>Issues</th></tr></thead><tbody>");
        for (Map.Entry<String, Long> entry : report.issuesByRule().entrySet()) {
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(entry.getValue()).append("</td></tr>");
        }
        html.append("</tbody></table><p>Errors: ").append(report.issuesBySeverity().getOrDefault(Severity.ERROR, 0L))
                .append(" · Warnings: ").append(report.issuesBySeverity().getOrDefault(Severity.WARNING, 0L))
                .append("</p><h2>Detailed findings</h2>");
        if (report.issues().isEmpty()) {
            html.append("<p>No quality issues found.</p>");
        } else {
            html.append("<table><thead><tr><th>Record / line</th><th>Order / field</th><th>Severity / rule</th><th>Reason and record snapshot</th></tr></thead><tbody>");
            for (QualityIssue issue : report.issues()) {
                html.append("<tr><td>").append(issue.recordNumber()).append(" / ").append(issue.lineNumber())
                        .append("</td><td>").append(escape(issue.orderId())).append("<br>").append(escape(issue.field()))
                        .append("</td><td>").append(escape(issue.severity().name())).append("<br>").append(escape(issue.rule()))
                        .append("</td><td>").append(escape(issue.message())).append("<pre>");
                issue.record().forEach((key, value) -> html.append(escape(key)).append(": ").append(escape(value)).append('\n'));
                html.append("</pre></td></tr>");
            }
            html.append("</tbody></table>");
        }
        return html.append("<small>TOTAL_AMOUNT_POLICY is an example business policy: quantity × unit_price must not exceed 100000.00. ")
                .append("Only individually valid quantities and prices are checked. This report does not modify source data.</small></body></html>")
                .toString();
    }

    private String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&#39;");
    }
}
