package dataguard.persistence;

import tools.jackson.databind.json.JsonMapper;
import dataguard.core.QualityIssue;
import dataguard.core.QualityReport;
import dataguard.core.Severity;
import dataguard.service.DatasetInfo;
import dataguard.service.TaskStatus;
import dataguard.service.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class InspectionRepositoryTest {
    private JdbcTemplate jdbc;
    private InspectionRepository repository;
    private TransactionTemplate transaction;

    @BeforeEach
    void setup() {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new InspectionRepository(jdbc, JsonMapper.builder().build());
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    @Test
    void foreignKeysRejectOrphanTasksAndReports() {
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertTask(UUID.randomUUID().toString(), UUID.randomUUID().toString(), Instant.now()));
        assertEquals(0, count("inspection_tasks"));
        assertThrows(DataIntegrityViolationException.class, () -> repository.insertReport(UUID.randomUUID().toString(), report(List.of())));
        assertEquals(0, count("inspection_reports"));
    }

    @Test
    void failedIssueBatchRollsBackReportAndSuccessTransition() {
        String dataset = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        repository.insertDataset(new StoredDataset(new DatasetInfo(dataset, "test.csv", 1, "a".repeat(64), Instant.now()), UUID.randomUUID() + ".csv"));
        repository.insertTask(task, dataset, Instant.now());
        QualityIssue valid = issue("REQUIRED");
        QualityIssue invalid = issue("x".repeat(129)); // Deliberately violate the rule-name SQL constraint after one valid issue.
        assertThrows(DataIntegrityViolationException.class, () -> transaction.executeWithoutResult(status -> {
            repository.insertReport(task, report(List.of(valid, invalid)));
            repository.completeTask(task, Instant.now());
        }));
        assertEquals(0, count("inspection_reports"));
        assertEquals(0, count("quality_issues"));
        assertEquals(TaskStatus.RUNNING, repository.findTask(task).orElseThrow().status());
    }

    @Test
    void rejectsImpossibleReportCounts() {
        String dataset = UUID.randomUUID().toString();
        String task = UUID.randomUUID().toString();
        repository.insertDataset(new StoredDataset(new DatasetInfo(dataset, "test.csv", 1, "b".repeat(64), Instant.now()), UUID.randomUUID() + ".csv"));
        repository.insertTask(task, dataset, Instant.now());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
                INSERT INTO inspection_reports
                    (task_id, total_records, valid_records, invalid_records, total_issues, quality_score, report_json)
                VALUES (?, 1, 2, 0, 0, 100.00, '{}')
                """, task));
        assertEquals(0, count("inspection_reports"));
    }

    @Test
    void rejectsReportAboveUtf8ByteLimitWithoutPersistingAnything() {
        QualityReport oversized = new QualityReport("界".repeat(6_000_000), Instant.now(), List.of("quantity"),
                0, 0, 0, 0, new BigDecimal("100.00"), Map.of(), Map.of(), List.of());
        ServiceException failure = assertThrows(ServiceException.class, () -> transaction.executeWithoutResult(status ->
                repository.insertReport(UUID.randomUUID().toString(), oversized)));
        assertEquals("REPORT_TOO_LARGE", failure.getCode());
        assertEquals(0, count("inspection_reports"));
        assertEquals(0, count("quality_issues"));
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private QualityIssue issue(String rule) {
        return new QualityIssue(1, 2, "O1", "quantity", rule, "invalid quantity", Severity.ERROR, Map.of("quantity", "0"));
    }

    private QualityReport report(List<QualityIssue> issues) {
        return new QualityReport("test.csv", Instant.now(), List.of("quantity"), 1, 0, 1,
                issues.size(), BigDecimal.ZERO.setScale(2), Map.of(), Map.of(), issues);
    }
}
