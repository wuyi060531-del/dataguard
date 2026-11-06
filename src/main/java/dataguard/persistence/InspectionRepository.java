package dataguard.persistence;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import dataguard.core.QualityIssue;
import dataguard.core.QualityReport;
import dataguard.service.DatasetInfo;
import dataguard.service.ServiceException;
import dataguard.service.TaskInfo;
import dataguard.service.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

/** Parameterized JDBC operations. The service owns transaction boundaries. */
@Repository
public class InspectionRepository {
    private static final int MAX_REPORT_BYTES = 16 * 1024 * 1024;
    private static final String TASK_SELECT = """
            SELECT t.id, t.dataset_id, t.status, t.created_at, t.completed_at,
                   t.error_code, t.error_message, r.total_records, r.valid_records,
                   r.invalid_records, r.total_issues, r.quality_score
            FROM inspection_tasks t LEFT JOIN inspection_reports r ON r.task_id = t.id
            """;

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public InspectionRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public void insertDataset(StoredDataset dataset) {
        DatasetInfo d = dataset.info();
        jdbc.update("""
                INSERT INTO datasets (id, file_name, storage_name, size_bytes, sha256, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, d.id(), d.fileName(), dataset.storageName(), d.sizeBytes(), d.sha256(), d.createdAt().toEpochMilli());
    }

    public Optional<StoredDataset> findDataset(String id) {
        return jdbc.query("SELECT * FROM datasets WHERE id = ?", datasetMapper(), id).stream().findFirst();
    }

    public List<DatasetInfo> listDatasets(int limit, int offset) {
        return jdbc.query("SELECT * FROM datasets ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?",
                datasetMapper(), limit, offset).stream().map(StoredDataset::info).toList();
    }

    public void insertTask(String id, String datasetId, Instant createdAt) {
        jdbc.update("""
                INSERT INTO inspection_tasks (id, dataset_id, status, created_at)
                VALUES (?, ?, 'RUNNING', ?)
                """, id, datasetId, createdAt.toEpochMilli());
    }

    public Optional<TaskInfo> findTask(String id) {
        return jdbc.query(TASK_SELECT + " WHERE t.id = ?", taskMapper(), id).stream().findFirst();
    }

    public List<TaskInfo> listTasks(int limit, int offset, String datasetId) {
        if (datasetId == null) {
            return jdbc.query(TASK_SELECT + " ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                    taskMapper(), limit, offset);
        }
        return jdbc.query(TASK_SELECT + " WHERE t.dataset_id = ? ORDER BY t.created_at DESC, t.id DESC LIMIT ? OFFSET ?",
                taskMapper(), datasetId, limit, offset);
    }

    /** Must run in the same transaction as completeTask. */
    public void insertReport(String taskId, QualityReport report) {
        String serializedReport = json(report);
        if (serializedReport.getBytes(StandardCharsets.UTF_8).length > MAX_REPORT_BYTES) {
            throw new ServiceException("REPORT_TOO_LARGE", 413, "检测报告超过 16 MiB 上限，未保存本次结果。");
        }
        jdbc.update("""
                INSERT INTO inspection_reports
                    (task_id, total_records, valid_records, invalid_records, total_issues, quality_score, report_json)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, taskId, report.totalRecords(), report.validRecords(), report.invalidRecords(),
                report.totalIssues(), report.qualityScore(), serializedReport);

        // Bounded batches avoid holding a second full copy of all serialized issues in memory.
        List<QualityIssue> issues = report.issues();
        for (int start = 0; start < issues.size(); start += 250) {
            List<Integer> batch = IntStream.range(start, Math.min(start + 250, issues.size())).boxed().toList();
            jdbc.batchUpdate("""
                    INSERT INTO quality_issues
                        (task_id, issue_index, record_number, line_number, order_id, field_name,
                         rule_name, message, severity, record_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, batch, batch.size(), (statement, index) -> {
                QualityIssue issue = issues.get(index);
                statement.setString(1, taskId);
                statement.setInt(2, index);
                statement.setLong(3, issue.recordNumber());
                statement.setLong(4, issue.lineNumber());
                statement.setString(5, issue.orderId());
                statement.setString(6, issue.field());
                statement.setString(7, issue.rule());
                statement.setString(8, issue.message());
                statement.setString(9, issue.severity().name());
                statement.setString(10, json(issue.record()));
            });
        }
    }

    public void completeTask(String taskId, Instant completedAt) {
        int changed = jdbc.update("""
                UPDATE inspection_tasks SET status = 'SUCCEEDED', completed_at = ?
                WHERE id = ? AND status = 'RUNNING'
                """, completedAt.toEpochMilli(), taskId);
        if (changed != 1) {
            throw new IllegalStateException("Task is no longer running");
        }
    }

    public void failTask(String taskId, Instant completedAt, String code, String message) {
        jdbc.update("""
                UPDATE inspection_tasks
                SET status = 'FAILED', completed_at = ?, error_code = ?, error_message = ?
                WHERE id = ? AND status = 'RUNNING'
                """, completedAt.toEpochMilli(), code, message, taskId);
    }

    public int interruptRunningTasks(Instant now, Instant startupCutoff) {
        return jdbc.update("""
                UPDATE inspection_tasks
                SET status = 'FAILED', completed_at = ?, error_code = 'INTERRUPTED',
                    error_message = '上次运行在任务完成前中断，请重新创建检测任务。'
                WHERE status = 'RUNNING' AND created_at < ?
                """, now.toEpochMilli(), startupCutoff.toEpochMilli());
    }

    public Optional<QualityReport> findReport(String taskId) {
        return jdbc.query("SELECT report_json FROM inspection_reports WHERE task_id = ?",
                (rs, row) -> parseReport(rs.getString(1)), taskId).stream().findFirst();
    }

    private RowMapper<StoredDataset> datasetMapper() {
        return (rs, row) -> new StoredDataset(new DatasetInfo(rs.getString("id"), rs.getString("file_name"),
                rs.getLong("size_bytes"), rs.getString("sha256"), instant(rs, "created_at")), rs.getString("storage_name"));
    }

    private RowMapper<TaskInfo> taskMapper() {
        return (rs, row) -> new TaskInfo(rs.getString("id"), rs.getString("dataset_id"),
                TaskStatus.valueOf(rs.getString("status")), instant(rs, "created_at"), instant(rs, "completed_at"),
                rs.getString("error_code"), rs.getString("error_message"), rs.getObject("total_records", Integer.class),
                rs.getObject("valid_records", Integer.class), rs.getObject("invalid_records", Integer.class),
                rs.getObject("total_issues", Integer.class), rs.getBigDecimal("quality_score"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        long timestamp = rs.getLong(column);
        return rs.wasNull() ? null : Instant.ofEpochMilli(timestamp);
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Cannot serialize inspection result", failure);
        }
    }

    private QualityReport parseReport(String value) {
        try {
            return mapper.readValue(value, QualityReport.class);
        } catch (JacksonException failure) {
            throw new IllegalStateException("Stored inspection report is invalid", failure);
        }
    }
}
