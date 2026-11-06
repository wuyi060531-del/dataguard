package dataguard.benchmark;

import tools.jackson.databind.json.JsonMapper;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;
import dataguard.persistence.InspectionRepository;
import dataguard.persistence.StoredDataset;
import dataguard.service.DatasetInfo;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 显式运行的隔离 MySQL 基准，不属于 Surefire 常规测试，也不修改业务规则。 */
public final class PersistenceBenchmark {
    private PersistenceBenchmark() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("需要输入 CSV 和新建结果 JSON 路径");
        }
        String url = System.getenv("DATAGUARD_MYSQL_TEST_URL");
        if (url == null || !url.startsWith("jdbc:mysql:")) {
            throw new IllegalArgumentException("只允许显式配置的独立 MySQL 测试库");
        }
        var source = new DriverManagerDataSource(url,
                System.getenv("DATAGUARD_MYSQL_TEST_USERNAME"),
                System.getenv("DATAGUARD_MYSQL_TEST_PASSWORD"));
        new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql")).execute(source);
        var jdbc = new JdbcTemplate(source);
        var mapper = JsonMapper.builder().build();
        var repository = new InspectionRepository(jdbc, mapper);
        var transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        QualityReport report = new QualityEngine().inspect(Path.of(args[0]));
        String datasetId = UUID.randomUUID().toString();
        repository.insertDataset(new StoredDataset(new DatasetInfo(datasetId, "benchmark.csv",
                Files.size(Path.of(args[0])), "b".repeat(64), Instant.now()), UUID.randomUUID() + ".csv"));
        List<Map<String, Object>> samples = new ArrayList<>();
        try {
            // 一轮预热加三轮计时；每轮独立任务，清理只限本次随机数据集。
            for (int run = 0; run < 4; run++) {
                String taskId = UUID.randomUUID().toString();
                repository.insertTask(taskId, datasetId, Instant.now());
                long started = System.nanoTime();
                Long insertStatements = transaction.execute(status -> {
                    long before = insertCount(jdbc);
                    repository.insertReport(taskId, report);
                    repository.completeTask(taskId, Instant.now());
                    return insertCount(jdbc) - before;
                });
                double elapsedMillis = (System.nanoTime() - started) / 1_000_000.0;
                int saved = jdbc.queryForObject("SELECT COUNT(*) FROM quality_issues WHERE task_id = ?",
                        Integer.class, taskId);
                if (saved != report.totalIssues()
                        || !repository.findReport(taskId).orElseThrow().equals(report)) {
                    throw new IllegalStateException("基准结果未完整持久化");
                }
                if (run > 0) {
                    samples.add(Map.of("run", run, "transactionMillis", elapsedMillis,
                            "serverInsertStatements", insertStatements, "savedIssues", saved));
                }
            }
            var result = new LinkedHashMap<String, Object>();
            result.put("databaseVersion", jdbc.queryForObject("SELECT VERSION()", String.class));
            result.put("records", report.totalRecords());
            result.put("issues", report.totalIssues());
            result.put("rewriteBatchedStatements", url.contains("rewriteBatchedStatements=true"));
            result.put("warmupRuns", 1);
            result.put("samples", samples);
            result.put("measurement", "Transaction begin, session counters, report/issues/status write and commit; excludes CSV parsing");
            // CREATE_NEW 保留之前的证据，不覆盖任意现有文件。
            Files.writeString(Path.of(args[1]), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result),
                    java.nio.file.StandardOpenOption.CREATE_NEW);
            System.out.println("Persistence benchmark complete: " + report.totalRecords()
                    + " records, " + report.totalIssues() + " issues, 3 measured runs");
        } finally {
            jdbc.update("DELETE FROM quality_issues WHERE task_id IN (SELECT id FROM inspection_tasks WHERE dataset_id = ?)", datasetId);
            jdbc.update("DELETE FROM inspection_reports WHERE task_id IN (SELECT id FROM inspection_tasks WHERE dataset_id = ?)", datasetId);
            jdbc.update("DELETE FROM inspection_tasks WHERE dataset_id = ?", datasetId);
            jdbc.update("DELETE FROM datasets WHERE id = ?", datasetId);
        }
    }

    private static long insertCount(JdbcTemplate jdbc) {
        return Long.parseLong(String.valueOf(jdbc.queryForMap("SHOW SESSION STATUS LIKE 'Com_insert'").get("Value")));
    }
}
