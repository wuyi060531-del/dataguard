package dataguard.service;

import tools.jackson.databind.json.JsonMapper;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;
import dataguard.persistence.InspectionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DatasetTaskIntegrationTest {
    private static final String HEADER = "order_id,customer_id,product_id,quantity,unit_price,order_time,status\n";
    private static final String VALID = HEADER + "O1,C1,P1,2,12.50,2026-10-19T09:00:00,PAID\n";
    @TempDir Path directory;
    private Path uploads;
    private Path imports;
    private JdbcTemplate jdbc;
    private InspectionRepository repository;
    private DatasetService datasets;
    private TaskService tasks;
    private FileStorage storage;
    private DriverManagerDataSource dataSource;

    @BeforeEach
    void setup() throws IOException {
        uploads = directory.resolve("uploads");
        imports = Files.createDirectories(directory.resolve("raw"));
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1", "sa", "");
        connectServices(dataSource);
    }

    private void connectServices(DriverManagerDataSource source) {
        new ResourceDatabasePopulator(new ClassPathResource("schema-h2.sql")).execute(source);
        jdbc = new JdbcTemplate(source);
        repository = new InspectionRepository(jdbc, JsonMapper.builder().build());
        storage = new FileStorage(uploads.toString(), imports.toString());
        datasets = new DatasetService(repository, storage);
        tasks = new TaskService(datasets, storage, repository, new QualityEngine(), new DataSourceTransactionManager(source));
    }

    @Test
    void storesReportIssuesAndRepeatedExecutionHistory() {
        DatasetInfo dataset = upload(HEADER + "O1,C1,P1,0,-2,not-a-date,INVALID\n");
        TaskInfo first = tasks.startTask(dataset.id());
        assertEquals(TaskStatus.SUCCEEDED, first.status(), "Quality failures belong in a successful inspection report");
        assertEquals(1, first.totalRecords());
        assertEquals(1, first.invalidRecords());
        assertTrue(first.totalIssues() >= 3);
        QualityReport report = tasks.getReport(first.id());
        assertEquals("orders.csv", report.sourceName());
        assertTrue(report.issues().stream().allMatch(issue -> issue.recordNumber() == 1 && issue.lineNumber() == 2));
        assertTrue(report.issues().stream().anyMatch(issue -> issue.field().equals("quantity")));
        assertEquals(report.totalIssues(), jdbc.queryForObject("SELECT COUNT(*) FROM quality_issues WHERE task_id = ?", Integer.class, first.id()));
        assertEquals(report, repository.findReport(first.id()).orElseThrow());
        TaskInfo second = tasks.startTask(dataset.id());
        assertNotEquals(first.id(), second.id());
        assertEquals(2, tasks.listTasks(10, 0, dataset.id()).size());
        assertEquals(dataset, datasets.getDataset(dataset.id()));
        assertEquals(1, datasets.listDatasets(10, 0).size());
    }

    @Test
    void keepsFatalFailureHistoryAndRejectsUnavailableReport() {
        DatasetInfo empty = upload("");
        TaskInfo failed = tasks.startTask(empty.id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("EMPTY_FILE", failed.errorCode());
        assertNotNull(failed.completedAt());
        assertNull(failed.totalRecords());
        assertFalse(failed.errorMessage().contains(uploads.toString()));
        assertEquals("REPORT_NOT_AVAILABLE", assertThrows(ServiceException.class, () -> tasks.getReport(failed.id())).getCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inspection_reports", Integer.class));
    }

    @Test
    void interruptedTasksArePersistedAsFailedOnRecovery() {
        DatasetInfo dataset = upload(VALID);
        String id = UUID.randomUUID().toString();
        repository.insertTask(id, dataset.id(), Instant.now().minusSeconds(60));
        String thisRun = UUID.randomUUID().toString();
        repository.insertTask(thisRun, dataset.id(), Instant.now().plusSeconds(1));
        tasks.recoverInterruptedTasks();
        assertEquals("INTERRUPTED", tasks.getTask(id).errorCode());
        assertEquals(TaskStatus.FAILED, tasks.getTask(id).status());
        assertEquals(TaskStatus.RUNNING, tasks.getTask(thisRun).status(), "Ready 事件不能中断本次启动期间新建的任务");
        tasks.recoverInterruptedTasks();
        assertEquals("INTERRUPTED", tasks.getTask(id).errorCode());
    }

    @Test
    void persistsAcrossClosedConnectionsAndNewServiceInstances() {
        DriverManagerDataSource disk = new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("history"), "sa", "");
        connectServices(disk);
        DatasetInfo dataset = upload(VALID);
        TaskInfo completed = tasks.startTask(dataset.id());
        QualityReport expected = tasks.getReport(completed.id());
        // DriverManagerDataSource closes each connection after an operation: no connection pool or in-memory cache survives.
        connectServices(new DriverManagerDataSource("jdbc:h2:file:" + directory.resolve("history"), "sa", ""));
        assertEquals(dataset, datasets.getDataset(dataset.id()));
        assertEquals(completed, tasks.getTask(completed.id()));
        assertEquals(expected, tasks.getReport(completed.id()));
    }

    @Test
    void copiesRawImportsAndDetectsManagedFileTampering() throws IOException {
        Path raw = Files.writeString(imports.resolve("orders.csv"), VALID);
        DatasetInfo dataset = datasets.register("orders.csv");
        Files.writeString(raw, "raw was changed after import");
        assertEquals(TaskStatus.SUCCEEDED, tasks.startTask(dataset.id()).status());
        String managedName = jdbc.queryForObject("SELECT storage_name FROM datasets WHERE id = ?", String.class, dataset.id());
        Path managed = uploads.resolve(managedName);
        byte[] old = Files.readAllBytes(managed);
        old[0] = 'X'; // Same size: a size-only check would miss this corruption.
        Files.write(managed, old);
        TaskInfo failed = tasks.startTask(dataset.id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("FILE_INTEGRITY_ERROR", failed.errorCode());
    }

    @Test
    void rejectsUnsafeImportPathsAndSymlinks() throws IOException {
        Path outside = Files.writeString(directory.resolve("outside.csv"), VALID);
        for (String path : new String[]{"../outside.csv", outside.toString(), "..\\outside.csv", "./outside.csv", "nested/orders.csv", "C:orders.csv", "C:\\orders.csv"}) {
            assertThrows(ServiceException.class, () -> datasets.register(path), path);
        }
        Files.createSymbolicLink(imports.resolve("link.csv"), outside);
        assertEquals("INVALID_IMPORT_PATH", assertThrows(ServiceException.class, () -> datasets.register("link.csv")).getCode());
        Path outsideDirectory = Files.createDirectory(directory.resolve("outside-directory"));
        Files.writeString(outsideDirectory.resolve("orders.csv"), VALID);
        Files.createSymbolicLink(imports.resolve("linked-directory"), outsideDirectory);
        assertThrows(ServiceException.class, () -> datasets.register("linked-directory/orders.csv"));
        assertEquals("IMPORT_FILE_NOT_FOUND", assertThrows(ServiceException.class, () -> datasets.register("missing.csv")).getCode());
        assertEquals(0, datasets.listDatasets(10, 0).size());
    }

    @Test
    void rejectsEvenOrdinaryImportSubdirectories() throws IOException {
        Files.createDirectory(imports.resolve("nested"));
        Files.writeString(imports.resolve("nested/orders.csv"), VALID);
        assertEquals("INVALID_IMPORT_PATH", assertThrows(ServiceException.class,
                () -> datasets.register("nested/orders.csv")).getCode());
        assertTrue(Files.exists(imports.resolve("nested/orders.csv")));
    }

    @Test
    void rejectsDangerousNamesAndStreamsBeyondLimitWithoutLeavingFiles() throws IOException {
        for (String name : new String[]{"../orders.csv", "C:\\orders.csv", "orders.csv\n", "orders.txt", "orders.csv\u0000"}) {
            assertThrows(ServiceException.class, () -> datasets.upload(name, new ByteArrayInputStream(new byte[0])));
        }
        InputStream tooLarge = new InputStream() {
            private long remaining = FileStorage.MAX_BYTES + 1;
            @Override public int read() { return remaining-- > 0 ? 'a' : -1; }
            @Override public int read(byte[] buffer, int offset, int length) {
                if (remaining == 0) return -1;
                int count = (int) Math.min(remaining, length);
                java.util.Arrays.fill(buffer, offset, offset + count, (byte) 'a');
                remaining -= count;
                return count;
            }
        };
        assertEquals(413, assertThrows(ServiceException.class, () -> datasets.upload("big.csv", tooLarge)).getStatus());
        try (var files = Files.list(uploads)) {
            assertEquals(0, files.count());
        }
        assertEquals(0, datasets.listDatasets(10, 0).size());
    }

    @Test
    void databaseFailureRemovesNewManagedFile() throws IOException {
        InspectionRepository broken = new InspectionRepository(jdbc, JsonMapper.builder().build()) {
            @Override public void insertDataset(dataguard.persistence.StoredDataset dataset) {
                throw new DataIntegrityViolationException("injected insert failure");
            }
        };
        DatasetService failing = new DatasetService(broken, storage);
        assertEquals("PERSISTENCE_ERROR", assertThrows(ServiceException.class,
                () -> failing.upload("orders.csv", bytes(VALID))).getCode());
        try (var files = Files.list(uploads)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void reportPersistenceFailureLeavesFailedTaskWithoutPartialResults() {
        InspectionRepository broken = new InspectionRepository(jdbc, JsonMapper.builder().build()) {
            @Override public void insertReport(String taskId, QualityReport report) {
                super.insertReport(taskId, report);
                throw new DataIntegrityViolationException("injected failure after report and issue writes");
            }
        };
        TaskService failing = new TaskService(datasets, storage, broken, new QualityEngine(), new DataSourceTransactionManager(dataSource));
        DatasetInfo dataset = upload(HEADER + "O1,C1,P1,0,5,2026-10-19T09:00:00,PAID\n");
        TaskInfo failed = failing.startTask(dataset.id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("PERSISTENCE_ERROR", failed.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inspection_reports", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM quality_issues", Integer.class));
    }

    @Test
    void oversizedReportFailureIsPersistedAsFailedTask() {
        InspectionRepository oversized = new InspectionRepository(jdbc, JsonMapper.builder().build()) {
            @Override public void insertReport(String taskId, QualityReport report) {
                throw new ServiceException("REPORT_TOO_LARGE", 413, "检测报告超过 16 MiB 上限，未保存本次结果。");
            }
        };
        TaskService failing = new TaskService(datasets, storage, oversized, new QualityEngine(), new DataSourceTransactionManager(dataSource));
        DatasetInfo dataset = upload(VALID);
        TaskInfo failed = failing.startTask(dataset.id());
        assertEquals(TaskStatus.FAILED, failed.status());
        assertEquals("REPORT_TOO_LARGE", failed.errorCode());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM inspection_reports", Integer.class));
    }

    @Test
    void readFailureCleansPartialUploads() throws IOException {
        InputStream broken = new InputStream() {
            private int reads;
            @Override public int read() throws IOException {
                if (++reads > 10) throw new IOException("injected input failure");
                return 'a';
            }
        };
        assertEquals("FILE_STORAGE_ERROR", assertThrows(ServiceException.class,
                () -> datasets.upload("broken.csv", broken)).getCode());
        try (var files = Files.list(uploads)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void rejectsInvalidPaginationAndUnknownIdentifiers() {
        assertThrows(ServiceException.class, () -> datasets.listDatasets(101, 0));
        assertThrows(ServiceException.class, () -> tasks.listTasks(10, -1, null));
        assertEquals("DATASET_NOT_FOUND", assertThrows(ServiceException.class,
                () -> tasks.startTask(UUID.randomUUID().toString())).getCode());
        assertEquals("INVALID_ID", assertThrows(ServiceException.class, () -> tasks.getTask("' OR 1=1")).getCode());
        assertEquals("TASK_NOT_FOUND", assertThrows(ServiceException.class,
                () -> tasks.getTask(UUID.randomUUID().toString())).getCode());
    }

    private DatasetInfo upload(String content) { return datasets.upload("orders.csv", bytes(content)); }
    private ByteArrayInputStream bytes(String text) { return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)); }
}
