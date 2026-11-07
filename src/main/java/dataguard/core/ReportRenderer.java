package dataguard.core;

import java.util.Map;

/** Self-contained readable report; all values, including snapshots and filenames, are escaped. */
public final class ReportRenderer {
    public String toHtml(QualityReport report) {
        StringBuilder html = new StringBuilder("""
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width,initial-scale=1">
                <title>DataGuard Quality Report</title>
                <style>
                *{box-sizing:border-box}body{font:15px/1.6 system-ui,sans-serif;max-width:1100px;width:calc(100% - 32px);margin:24px auto;color:#172b4d;overflow-wrap:anywhere}
                h1{font-size:1.6rem;line-height:1.3}h2{font-size:1.1rem;margin-top:24px}
                .table-wrap{max-width:100%;overflow-x:auto;margin:1rem 0}
                table{border-collapse:collapse;width:100%;font-size:14px}table.findings{min-width:780px;table-layout:fixed}
                .findings th:nth-child(1){width:12%}.findings th:nth-child(2){width:19%}.findings th:nth-child(3){width:23%}
                th,td{border:1px solid #d8e1e8;padding:.6rem;text-align:left;vertical-align:top;overflow-wrap:anywhere}
                th{background:#f6f8fa}code,pre{white-space:pre-wrap;overflow-wrap:anywhere}pre{font-size:13px}
                small,.table-hint{color:#526477}.summary{background:#f3f6f8;padding:1rem;border-radius:6px}
                .execution{color:#334e68}.quality-status{padding:10px 14px;border-left:3px solid #697b8c;background:#f3f6f8;font-weight:600}
                .quality-status.error{color:#8c241c;background:#fff4f1;border-color:#b42318}
                .quality-status.warning{color:#784008;background:#fff8eb;border-color:#8a4b08}
                .quality-status.clear{color:#12554f;background:#edf7f4;border-color:#146965}
                .table-wrap:focus-visible{outline:3px solid #146965;outline-offset:3px}
                @media(max-width:600px){body{margin:16px auto}h1{font-size:1.3rem}}
                </style>
                </head><body><h1>DataGuard — Data Quality Report</h1>
                """);
        // Presentation only: the underlying report and its score are never changed.
        boolean empty = report.totalRecords() == 0;
        String tone = empty ? "empty" : report.invalidRecords() == 0 ? "clear"
                : report.issuesBySeverity().getOrDefault(Severity.ERROR, 0L) > 0 ? "error" : "warning";
        String outcome = empty ? "无数据可评估：文件只有表头，没有订单记录。"
                : report.invalidRecords() == 0 ? "通过当前规则检查：本次未发现异常。"
                : tone.equals("error") ? "发现数据问题：检测完成不代表数据合格。" : "存在待复核警告：检测完成不代表数据合格。";
        html.append("<p class=\"execution\" lang=\"zh-CN\">执行：检测完成 · SUCCEEDED</p>")
                .append("<p class=\"quality-status ").append(tone).append("\" lang=\"zh-CN\">").append(outcome).append("</p>");
        html.append("<p>Source: <strong>").append(escape(report.sourceName())).append("</strong><br>Generated: ")
                .append(escape(report.generatedAt().toString())).append("</p><div class=\"summary\">")
                .append("Records: ").append(report.totalRecords()).append(" · Valid: ").append(report.validRecords())
                .append(" · Invalid: ").append(report.invalidRecords()).append(" · Issues: ").append(report.totalIssues())
                .append(" · Quality: ").append(escape(report.qualityScore().toPlainString())).append("%</div>")
                .append("<p>Quality = valid records / total records × 100. A record with any ERROR or WARNING is invalid; ")
                .append("a header-only dataset has quality 100.00%. Issue counts can exceed invalid record counts.</p>")
                .append("<p>Fields: ").append(escape(String.join(", ", report.fields()))).append("</p>")
                .append("<h2>Rules</h2><div class=\"table-wrap\"><table><thead><tr><th>Rule</th><th>Issues</th></tr></thead><tbody>");
        for (Map.Entry<String, Long> entry : report.issuesByRule().entrySet()) {
            html.append("<tr><td>").append(escape(entry.getKey())).append("</td><td>").append(entry.getValue()).append("</td></tr>");
        }
        html.append("</tbody></table></div><p>Errors: ").append(report.issuesBySeverity().getOrDefault(Severity.ERROR, 0L))
                .append(" · Warnings: ").append(report.issuesBySeverity().getOrDefault(Severity.WARNING, 0L))
                .append("</p><h2>Detailed findings</h2>");
        if (report.issues().isEmpty()) {
            html.append("<p>No quality issues found.</p>");
            if (empty) {
                html.append("<p lang=\"zh-CN\">无数据可评估。评分 100.00% 是零记录的系统约定，不代表数据已通过验证。</p>");
            }
        } else {
            html.append("<p class=\"table-hint\" lang=\"zh-CN\">窄屏可在下方表格内左右滑动，查看完整问题与原始记录。</p>")
                    .append("<div class=\"table-wrap\" tabindex=\"0\" role=\"region\" aria-label=\"Detailed findings, horizontally scrollable\">")
                    .append("<table class=\"findings\"><thead><tr><th>Record / line</th><th>Order / field</th><th>Severity / rule</th><th>Reason and record snapshot</th></tr></thead><tbody>");
            for (QualityIssue issue : report.issues()) {
                html.append("<tr><td>").append(issue.recordNumber()).append(" / ").append(issue.lineNumber())
                        .append("</td><td>").append(escape(issue.orderId())).append("<br>").append(escape(issue.field()))
                        .append("</td><td>").append(escape(issue.severity().name())).append("<br>").append(escape(issue.rule()))
                        .append("</td><td>").append(escape(issue.message())).append("<pre>");
                issue.record().forEach((key, value) -> html.append(escape(key)).append(": ").append(escape(value)).append('\n'));
                html.append("</pre></td></tr>");
            }
            html.append("</tbody></table></div>");
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
