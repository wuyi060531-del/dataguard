package dataguard.persistence;

import tools.jackson.databind.json.JsonMapper;
import dataguard.Main;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;
import dataguard.service.DatasetInfo;
import dataguard.service.DatasetService;
import dataguard.service.FileStorage;
import dataguard.service.ServiceException;
import dataguard.service.TaskInfo;
import dataguard.service.TaskService;
import dataguard.service.TaskStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Opt in against an EMPTY, dedicated MySQL 8 test database; never use a personal or production database. */
@EnabledIfEnvironmentVariable(named = "DATAGUARD_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
class MySqlIntegrationTest {
    private static final String HEADER = "order_id,customer_id,product_id,quantity,unit_price,order_time,status\n";
    private static final String VALID = HEADER + "O1,C1,P1,2,12.50,2026-10-19T10:00:00,PAID\n";
    @TempDir Path directory;
    private final List<String> ownedDatasets = new ArrayList<>();
    private DriverManagerDataSource source;
    private JdbcTemplate jdbc;
    private JsonMapper mapper;
    private InspectionRepository repository;
    private FileStorage storage;
    private DatasetService datasets;
    private TaskService tasks;
    private Path raw;

    @BeforeEach
    void setup() throws Exception {
        source = new DriverManagerDataSource(testUrl(), testUsername(), testPassword());
        new ResourceDatabasePopulator(new ClassPathResource("schema-mysql.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        // Recovery updates RUNNING rows by design. Refuse to touch a database containing anybody else's data.
        assertEquals(0, count("datasets"), "MySQL integration tests require an empty dedicated database");
        assertEquals(0, count("inspection_tasks"), "MySQL integration tests require an empty dedicated database");
        mapper = JsonMapper.builder().build();
        repository = new InspectionRepository(jdbc, mapper);
        raw = Files.createDirectory(directory.resolve("raw"));
        storage = new FileStorage(directory.resolve("uploads").toString(), raw.toString());
        datasets = new DatasetService(repository, storage);
        tasks = taskService(repository);
    }

    @AfterEach
    void cleanupOwnedRows() {
        if (jdbc == null) return;
        for (String id : ownedDatasets) {
            // Only rows created by this test are removed; never DROP/TRUNCATE a caller-provided database.
            jdbc.update("DELETE FROM quality_issues WHERE task_id IN (SELECT id FROM inspection_tasks WHERE dataset_id = ?)", id);
            jdbc.update("DELETE FROM inspection_reports WHERE task_id IN (SELECT id FROM inspection_tasks WHERE dataset_id = ?)", id);
            jdbc.update("DELETE FROM inspection_tasks WHERE dataset_id = ?", id);
            jdbc.update("DELETE FROM datasets WHERE id = ?", id);
        }
    }

    @Test
    void mysqlSchemaTaskReportAndReconnectRoundTrip() {
        DatasetInfo dataset = upload(HEADER + "O1,C1,P1,0,5.00,2026-10-19T10:00:00,PAID\n");
        TaskInfo task = tasks.startTask(dataset.id());
        assertEquals(TaskStatus.SUCCEEDED, task.status());
        assertEquals(1, task.invalidRecords());
        assertTrue(task.totalIssues() > 0);
        assertEquals(2L, tasks.getReport(task.id()).issues().getFirst().lineNumber());
        var reconnected = new InspectionRepository(new JdbcTemplate(source), mapper);
        assertEquals(task, reconnected.findTask(task.id()).orElseThrow());
        assertEquals(tasks.getReport(task.id()), reconnected.findReport(task.id()).orElseThrow());
        assertEquals(task.totalIssues(), jdbc.queryForObject("SELECT COUNT(*) FROM quality_issues WHERE task_id = ?", Integer.class, task.id()));
        TaskInfo repeated = tasks.startTask(dataset.id());
        assertNotEquals(task.id(), repeated.id());
        assertEquals(2, tasks.listTasks(10, 0, dataset.id()).size());
        assertEquals("订单-mysql-test.csv", datasets.getDataset(dataset.id()).fileName());
    }

    @Test
    void mysqlForeignKeysAndChecksRejectInvalidState() {
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertTask(
                UUID.randomUUID().toString(), UUID.randomUUID().toString(), Instant.now()));
        assertEquals(0, count("inspection_tasks"));
        DatasetInfo dataset = upload(VALID);
        String id = UUID.randomUUID().toString();
        repository.insertTask(id, dataset.id(), Instant.now());
        assertCheckViolation(() -> jdbc.update(
                "UPDATE inspection_tasks SET status = 'SUCCEEDED' WHERE id = ?", id));
        assertCheckViolation(() -> jdbc.update("""
                INSERT INTO inspection_reports
                    (task_id, total_records, valid_records, invalid_records, total_issues, quality_score, report_json)
                VALUES (?, 1, 2, 0, 0, 100.00, '{}')
                """, id));
        assertEquals(TaskStatus.RUNNING, tasks.getTask(id).status());
        assertEquals(0, count("inspection_reports"));
    }

    @Test
    void mysqlReportAndIssueWritesRollbackTogetherBeforeFailedTaskIsCommitted() {
        StringBuilder csv = new StringBuilder(HEADER);
        for (int index = 0; index < 300; index++) {
            csv.append("O").append(index).append(",C1,P1,0,5,2026-10-19T10:00:00,PAID\n");
        }
        DatasetInfo dataset = upload(csv.toString());
        InspectionRepository broken = new InspectionRepository(jdbc, mapper) {
            @Override public void insertReport(String taskId, QualityReport report) {
                super.insertReport(taskId, report);
                assertEquals(300, report.totalIssues());
                assertEquals(300, count("quality_issues"), "Both 250-row batch and remainder must be written before rollback");
                // A real MySQL FK violation occurs AFTER valid report and issue rows have been written.
                jdbc.update("""
                        INSERT INTO quality_issues
                            (task_id, issue_index, record_number, line_number, order_id, field_name,
                             rule_name, message, severity, record_json)
                        VALUES (?, 0, 1, 2, 'O1', 'quantity', 'INJECTED', 'FK failure', 'ERROR', '{}')
                        """, UUID.randomUUID().toString());
            }
        };
        TaskInfo failed = taskService(broken).startTask(dataset.id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("PERSISTENCE_ERROR", failed.errorCode());
        assertEquals(0, count("inspection_reports"));
        assertEquals(0, count("quality_issues"));
        assertEquals(1, count("inspection_tasks"));
        assertEquals("REPORT_NOT_AVAILABLE", assertThrows(ServiceException.class,
                () -> tasks.getReport(failed.id())).getCode());
    }

    @Test
    void mysqlFatalCsvFailureRemainsQueryableWithoutPartialReport() {
        DatasetInfo dataset = upload("");
        TaskInfo task = tasks.startTask(dataset.id());
        assertEquals(TaskStatus.FAILED, task.status());
        assertEquals("EMPTY_FILE", task.errorCode());
        assertNotNull(task.completedAt());
        assertNull(task.totalRecords());
        assertEquals(task, new InspectionRepository(new JdbcTemplate(source), mapper).findTask(task.id()).orElseThrow());
        assertEquals(0, count("inspection_reports"));
        assertEquals(0, count("quality_issues"));
    }

    @Test
    void mysqlRecoveryOnlyInterruptsPriorRunTasks() {
        DatasetInfo dataset = upload(VALID);
        String previousRun = UUID.randomUUID().toString();
        String currentRun = UUID.randomUUID().toString();
        repository.insertTask(previousRun, dataset.id(), Instant.now().minusSeconds(60));
        repository.insertTask(currentRun, dataset.id(), Instant.now().plusSeconds(60));
        tasks.recoverInterruptedTasks();
        assertEquals(TaskStatus.FAILED, tasks.getTask(previousRun).status());
        assertEquals("INTERRUPTED", tasks.getTask(previousRun).errorCode());
        assertEquals(TaskStatus.RUNNING, tasks.getTask(currentRun).status());
    }

    @Test
    void mysqlSpringContextRestartPreservesReportsAndRecoversUnfinishedTasks() {
        DatasetInfo dataset = upload(VALID);
        TaskInfo task;
        QualityReport report;
        String interrupted = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = context()) {
            TaskService service = first.getBean(TaskService.class);
            task = service.startTask(dataset.id());
            report = service.getReport(task.id());
            first.getBean(InspectionRepository.class).insertTask(interrupted, dataset.id(), Instant.now().minusSeconds(10));
        }
        try (ConfigurableApplicationContext second = context()) {
            TaskService service = second.getBean(TaskService.class);
            assertEquals(TaskStatus.SUCCEEDED, task.status());
            assertEquals(task, service.getTask(task.id()));
            assertEquals(report, service.getReport(task.id()));
            assertEquals("INTERRUPTED", service.getTask(interrupted).errorCode());
            assertEquals(dataset, second.getBean(DatasetService.class).getDataset(dataset.id()));
        }
    }

    private DatasetInfo upload(String csv) {
        DatasetInfo dataset = datasets.upload("订单-mysql-test.csv", new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        ownedDatasets.add(dataset.id());
        return dataset;
    }

    private TaskService taskService(InspectionRepository target) {
        return new TaskService(datasets, storage, target, new QualityEngine(), new DataSourceTransactionManager(source));
    }

    private ConfigurableApplicationContext context() {
        SpringApplication app = new SpringApplication(Main.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        return app.run("--spring.profiles.active=mysql", "--spring.datasource.url=" + testUrl(),
                "--spring.datasource.username=" + testUsername(), "--spring.datasource.password=" + testPassword(),
                "--spring.datasource.hikari.maximum-pool-size=2", "--spring.sql.init.mode=always",
                "--dataguard.storage-root=" + directory.resolve("uploads"), "--dataguard.import-root=" + raw,
                "--spring.main.banner-mode=off", "--logging.level.root=WARN");
    }

    private int count(String table) { return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private void assertCheckViolation(Executable operation) {
        // MySQL 8 reports CHECK failures as vendor 3819 / HY000; Spring may not classify it as integrity violation.
        DataAccessException failure = assertThrows(DataAccessException.class, operation);
        SQLException cause = assertInstanceOf(SQLException.class, failure.getRootCause());
        assertEquals(3819, cause.getErrorCode(), "The actual MySQL CHECK constraint must reject the write");
    }
    private String testUrl() { return System.getenv("DATAGUARD_MYSQL_TEST_URL"); }
    private String testUsername() { return System.getenv().getOrDefault("DATAGUARD_MYSQL_TEST_USERNAME", "dataguard"); }
    private String testPassword() { return System.getenv().getOrDefault("DATAGUARD_MYSQL_TEST_PASSWORD", ""); }
}
