package dataguard.service;

import dataguard.Main;
import dataguard.core.QualityReport;
import dataguard.persistence.InspectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.CannotCreateTransactionException;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TaskWriteRecoveryIntegrationTest {
    private static final String CSV = """
            order_id,customer_id,product_id,quantity,unit_price,order_time,status
            O1,C1,P1,0,5.00,2026-10-19T09:00:00,PAID
            """;

    @TempDir Path directory;

    @Test
    void h2RecoversAfterBothWritesFailWithoutRestartingOrStoppingAnActiveTask() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        verifyRecovery(source, null, "schema-h2.sql");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DATAGUARD_MYSQL_TEST_URL", matches = "jdbc:mysql:.*")
    void mysqlRecoversAfterBothWritesFailWithoutRestartingOrStoppingAnActiveTask() throws Exception {
        String url = System.getenv("DATAGUARD_MYSQL_TEST_URL");
        assertTrue(url.matches("jdbc:mysql://(?:127\\.0\\.0\\.1|localhost)(?::[0-9]+)?/[A-Za-z0-9_]+_test(?:\\?.*)?"),
                "Use a dedicated loopback MySQL test database");
        String username = System.getenv().getOrDefault("DATAGUARD_MYSQL_TEST_USERNAME", "dataguard");
        String password = System.getenv().getOrDefault("DATAGUARD_MYSQL_TEST_PASSWORD", "");
        var source = new DriverManagerDataSource(url, username, password);
        try (var nonListeningPort = new Socket()) {
            // Connector/J makes a real connection attempt, but this reserved port has no listener.
            nonListeningPort.bind(new InetSocketAddress("127.0.0.1", 0));
            String unavailableUrl = url.replaceFirst("jdbc:mysql://[^/]+",
                    "jdbc:mysql://127.0.0.1:" + nonListeningPort.getLocalPort());
            verifyRecovery(source, new DriverManagerDataSource(unavailableUrl, username, password), "schema-mysql.sql");
        }
    }

    private void verifyRecovery(DataSource source, DataSource unavailableSource, String schema) throws Exception {
        new ResourceDatabasePopulator(new ClassPathResource(schema)).execute(source);
        JdbcTemplate observer = new JdbcTemplate(source);
        assertEquals(0, observer.queryForObject("SELECT COUNT(*) FROM datasets", Integer.class));
        assertEquals(0, observer.queryForObject("SELECT COUNT(*) FROM inspection_tasks", Integer.class));
        var connections = new OutageDataSource(source, unavailableSource);
        var fault = new Faults();
        var app = new SpringApplication(Main.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setRegisterShutdownHook(false);
        app.addInitializers(context -> {
            var beans = (GenericApplicationContext) context;
            beans.registerBean("outageDataSource", DataSource.class, () -> connections);
            beans.registerBean("faultingRepository", FaultingRepository.class, () -> new FaultingRepository(
                    beans.getBean(JdbcTemplate.class), beans.getBean(ObjectMapper.class), connections, fault),
                    definition -> definition.setPrimary(true));
        });

        String brokenDataset = null;
        String activeDataset = null;
        try (ConfigurableApplicationContext context = app.run("--spring.sql.init.mode=never",
                "--dataguard.storage-root=" + directory, "--spring.main.banner-mode=off", "--logging.level.root=WARN")) {
            var datasets = context.getBean(DatasetService.class);
            var tasks = context.getBean(TaskService.class);
            brokenDataset = upload(datasets, "interrupted.csv");
            activeDataset = upload(datasets, "active.csv");
            final String brokenId = brokenDataset;
            final String activeId = activeDataset;
            try (var executor = Executors.newSingleThreadExecutor()) {
                var longTask = executor.submit(() -> tasks.startTask(activeId));
                try {
                    assertTrue(fault.activeEntered.await(5, TimeUnit.SECONDS), "The concurrent inspection must still be executing");
                    // Even an old active task must not be picked up by same-process recovery.
                    observer.update("UPDATE inspection_tasks SET created_at = ? WHERE id = ?",
                            Instant.now().minusSeconds(3600).toEpochMilli(), fault.activeTaskId);

                    assertThrows(CannotCreateTransactionException.class, () -> tasks.startTask(brokenId));
                    assertTrue(connections.rejected.get() >= 1, "FAILED write must encounter the connection outage");
                    assertEquals("RUNNING", status(observer, fault.interruptedTaskId));
                    assertNoReport(observer, fault.interruptedTaskId);
                    int rejectedAfterRequest = connections.rejected.get();
                    long retryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                    while (connections.rejected.get() == rejectedAfterRequest && System.nanoTime() < retryDeadline) {
                        Thread.sleep(30);
                    }
                    boolean retriedWhileUnavailable = connections.rejected.get() > rejectedAfterRequest;
                    connections.available = true;

                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                    while ("RUNNING".equals(status(observer, fault.interruptedTaskId)) && System.nanoTime() < deadline) {
                        Thread.sleep(30);
                    }
                    TaskInfo recovered = tasks.getTask(fault.interruptedTaskId);
                    assertEquals(TaskStatus.FAILED, recovered.status(), "Recover in the same running Spring context");
                    assertTrue(retriedWhileUnavailable, "An unsuccessful scheduled retry must retain the recovery intent");
                    assertEquals("PERSISTENCE_ERROR", recovered.errorCode());
                    assertNotNull(recovered.completedAt());
                    assertEquals("REPORT_NOT_AVAILABLE", assertThrows(ServiceException.class,
                            () -> tasks.getReport(recovered.id())).getCode());
                    assertNoReport(observer, recovered.id());
                    assertEquals(TaskStatus.RUNNING, tasks.getTask(fault.activeTaskId).status());
                    assertFalse(longTask.isDone());
                    fault.releaseActive.countDown();
                    TaskInfo completed = longTask.get(5, TimeUnit.SECONDS);
                    assertEquals(TaskStatus.SUCCEEDED, completed.status());
                    assertEquals(1, tasks.getReport(completed.id()).totalIssues());

                    // Reapplying the exact recovery update after a lost acknowledgement must be a no-op.
                    InspectionRepository repository = context.getBean(InspectionRepository.class);
                    repository.failTask(recovered.id(), Instant.now(), "RETRY_PROBE", "Repeated failure update");
                    repository.failTask(completed.id(), Instant.now(), "RETRY_PROBE", "Already committed");
                    assertEquals(recovered, tasks.getTask(recovered.id()));
                    assertEquals(completed, tasks.getTask(completed.id()));
                } finally {
                    connections.available = true;
                    fault.releaseActive.countDown();
                }
            }
        } finally {
            removeOwned(observer, brokenDataset);
            removeOwned(observer, activeDataset);
        }
    }

    private static String upload(DatasetService datasets, String name) {
        return datasets.upload(name, new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8))).id();
    }

    private static String status(JdbcTemplate jdbc, String id) {
        return jdbc.queryForObject("SELECT status FROM inspection_tasks WHERE id = ?", String.class, id);
    }

    private static void assertNoReport(JdbcTemplate jdbc, String taskId) {
        for (String table : new String[]{"inspection_reports", "quality_issues"}) {
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE task_id = ?", Integer.class, taskId));
        }
    }

    private static void removeOwned(JdbcTemplate jdbc, String datasetId) {
        if (datasetId == null) return;
        for (String table : new String[]{"quality_issues", "inspection_reports"}) {
            jdbc.update("DELETE FROM " + table + " WHERE task_id IN (SELECT id FROM inspection_tasks WHERE dataset_id = ?)", datasetId);
        }
        jdbc.update("DELETE FROM inspection_tasks WHERE dataset_id = ?", datasetId);
        jdbc.update("DELETE FROM datasets WHERE id = ?", datasetId);
    }

    private static final class Faults {
        private final CountDownLatch activeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseActive = new CountDownLatch(1);
        private volatile String activeTaskId;
        private String interruptedTaskId;
    }

    private static class FaultingRepository extends InspectionRepository {
        private final JdbcTemplate jdbc;
        private final OutageDataSource connections;
        private final Faults fault;

        FaultingRepository(JdbcTemplate jdbc, ObjectMapper mapper, OutageDataSource connections, Faults fault) {
            super(jdbc, mapper);
            this.jdbc = jdbc;
            this.connections = connections;
            this.fault = fault;
        }

        @Override
        public void insertReport(String taskId, QualityReport report) {
            if (report.sourceName().equals("active.csv")) {
                fault.activeTaskId = taskId;
                fault.activeEntered.countDown();
                try {
                    assertTrue(fault.releaseActive.await(15, TimeUnit.SECONDS));
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
            }
            super.insertReport(taskId, report);
            if (report.sourceName().equals("interrupted.csv")) {
                fault.interruptedTaskId = taskId;
                assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM quality_issues WHERE task_id = ?", Integer.class, taskId));
                connections.available = false;
                // Real CHECK violation on the already open report transaction, after writing report and detail.
                jdbc.update("UPDATE inspection_tasks SET status = 'INVALID' WHERE id = ?", taskId);
                fail("The database must reject the invalid status");
            }
        }
    }

    private static final class OutageDataSource extends AbstractDataSource {
        private final DataSource source;
        private final DataSource unavailable;
        private final AtomicInteger rejected = new AtomicInteger();
        private volatile boolean available = true;

        OutageDataSource(DataSource source, DataSource unavailable) {
            this.source = source;
            this.unavailable = unavailable;
        }

        private DataSource target() throws SQLException {
            if (available) return source;
            rejected.incrementAndGet();
            if (unavailable != null) return unavailable;
            throw new SQLTransientConnectionException("Isolated H2 connection outage");
        }

        @Override public Connection getConnection() throws SQLException { return target().getConnection(); }
        @Override public Connection getConnection(String user, String password) throws SQLException {
            return target().getConnection(user, password);
        }
    }
}
