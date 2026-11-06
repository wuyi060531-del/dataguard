package dataguard.cli;

import tools.jackson.databind.json.JsonMapper;
import dataguard.core.InspectionException;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;
import dataguard.core.ReportRenderer;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/** 无需数据库即可检测本地 CSV；Web 模式提供持久化任务。 */
public final class InspectionCli {
    public int run(String[] arguments, PrintStream output, PrintStream error) {
        if (arguments.length > 3) {
            error.println("用法：java -jar dataguard-1.1.0.jar inspect [CSV路径] [报告目录]");
            return 2;
        }
        try {
            Path input = Path.of(arguments.length > 1 ? arguments[1] : "data/raw/orders.csv");
            Path destination = Path.of(arguments.length > 2 ? arguments[2] : "reports");
            QualityReport report = new QualityEngine().inspect(input);
            Files.createDirectories(destination);
            String reportName = "inspection-" + UUID.randomUUID();
            Path json = destination.resolve(reportName + ".json");
            Path html = destination.resolve(reportName + ".html");
            var mapper = JsonMapper.builder().findAndAddModules()
                    .build();
            Files.writeString(json, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Files.writeString(html, new ReportRenderer().toHtml(report),
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            output.println("===== DataGuard 1.1 =====");
            output.println("File: " + report.sourceName());
            output.println("Records: " + report.totalRecords());
            output.println("Valid: " + report.validRecords());
            output.println("Invalid: " + report.invalidRecords());
            output.println("Issues: " + report.totalIssues());
            output.println("Quality: " + report.qualityScore() + "%");
            output.println("JSON: " + json);
            output.println("HTML: " + html);
            return 0;
        } catch (InspectionException failure) {
            String location = failure.getLineNumber() == null ? "" : "（文件行 " + failure.getLineNumber() + "）";
            error.println(failure.getCode() + ": " + failure.getMessage() + location);
            return 1;
        } catch (java.nio.file.InvalidPathException failure) {
            error.println("INVALID_PATH: 文件路径无效。");
            return 2;
        } catch (IOException failure) {
            error.println("REPORT_IO_ERROR: 无法写入报告，请检查输出目录权限。");
            return 1;
        }
    }
}
