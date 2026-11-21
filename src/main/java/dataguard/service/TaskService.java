package dataguard.service;

import dataguard.core.InspectionException;
import dataguard.core.QualityEngine;
import dataguard.core.QualityReport;
import dataguard.persistence.InspectionRepository;
import dataguard.persistence.StoredDataset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/** Synchronous V1 tasks keep lifecycle and failure recovery explicit and easy to debug. */
@Service
public class TaskService {
    private static final Logger LOG = LoggerFactory.getLogger(TaskService.class);
    private final DatasetService datasets;
    private final FileStorage storage;
    private final InspectionRepository repository;
    private final QualityEngine engine;
    private final TransactionTemplate transaction;
    private final TransactionTemplate recoveryTransaction;
    private final ConcurrentMap<String, PendingFailure> pendingFailures = new ConcurrentHashMap<>();
    // Captured when the service is constructed, before HTTP requests can create tasks.
    // A task created during the ApplicationReadyEvent window belongs to this run and must not be interrupted.
    private final Instant startupCutoff = Instant.now();

    public TaskService(DatasetService datasets, FileStorage storage, InspectionRepository repository,
                       QualityEngine engine, PlatformTransactionManager transactionManager) {
        this.datasets = datasets;
        this.storage = storage;
        this.repository = repository;
        this.engine = engine;
        transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        recoveryTransaction = new TransactionTemplate(transactionManager, transaction);
        recoveryTransaction.setTimeout(5);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedTasks() {
        Integer count = transaction.execute(status -> repository.interruptRunningTasks(Instant.now(), startupCutoff));
        if (count != null && count > 0) {
            LOG.warn("Recovered {} interrupted inspection tasks", count);
        }
    }

    @Scheduled(fixedDelay = 1, timeUnit = TimeUnit.SECONDS)
    void retryFailedStatusWrites() {
        for (var entry : pendingFailures.entrySet()) {
            String taskId = entry.getKey();
            PendingFailure pending = entry.getValue();
            try {
                persistFailure(recoveryTransaction, taskId, pending);
                pendingFailures.remove(taskId, pending);
            } catch (CannotCreateTransactionException | DataAccessResourceFailureException unavailable) {
                // The shared database is unavailable. Try this set again on the next tick.
                return;
            } catch (DataAccessException | TransactionException failure) {
                // An individual row failure must not block recovery of other tasks.
                LOG.debug("Could not retry the failure status for inspection task {} ({})",
                        taskId, failure.getClass().getSimpleName());
            }
        }
    }

    public TaskInfo startTask(String datasetId) {
        StoredDataset dataset = datasets.stored(datasetId);
        String taskId = UUID.randomUUID().toString();
        transaction.executeWithoutResult(status -> repository.insertTask(taskId, datasetId, Instant.now()));
        try (FileStorage.VerifiedFile snapshot = storage.verifiedSnapshot(dataset)) {
            QualityReport report = engine.inspect(snapshot.path(), dataset.info().fileName());
            transaction.executeWithoutResult(status -> {
                repository.insertReport(taskId, report);
                repository.completeTask(taskId, Instant.now());
            });
        } catch (InspectionException failure) {
            String message = safeInspectionMessage(failure);
            fail(taskId, failure.getCode(), message);
        } catch (ServiceException failure) {
            fail(taskId, failure.getCode(), failure.getMessage());
        } catch (DataAccessException | TransactionException failure) {
            LOG.error("Could not save inspection task {}", taskId, failure);
            fail(taskId, "PERSISTENCE_ERROR", "检测结果无法保存，请重新创建检测任务。");
        } catch (RuntimeException failure) {
            LOG.error("Inspection task {} failed", taskId, failure);
            fail(taskId, "INSPECTION_FAILED", "检测未能完成；请查看服务日志或重新创建任务。");
        }
        return getTask(taskId);
    }

    public TaskInfo getTask(String id) {
        DatasetService.validateId(id, "任务");
        return repository.findTask(id).orElseThrow(() -> new ServiceException("TASK_NOT_FOUND", 404, "检测任务不存在。"));
    }

    public List<TaskInfo> listTasks(int limit, int offset, String datasetId) {
        DatasetService.validatePagination(limit, offset);
        if (datasetId != null) {
            datasets.getDataset(datasetId);
        }
        return repository.listTasks(limit, offset, datasetId);
    }

    public QualityReport getReport(String taskId) {
        TaskInfo task = getTask(taskId);
        if (task.status() != TaskStatus.SUCCEEDED) {
            throw new ServiceException("REPORT_NOT_AVAILABLE", 409, "该任务尚无成功生成的报告。");
        }
        return repository.findReport(taskId).orElseThrow(() ->
                new ServiceException("REPORT_NOT_FOUND", 500, "任务报告缺失，请检查数据存储。"));
    }

    private void fail(String taskId, String code, String message) {
        PendingFailure pending = new PendingFailure(code, message);
        try {
            persistFailure(transaction, taskId, pending);
        } catch (DataAccessException | TransactionException failure) {
            // Inspection has exited. Only this known-ended task is eligible for same-process recovery.
            pendingFailures.put(taskId, pending);
            LOG.warn("Will retry saving the failure status for inspection task {}", taskId);
            throw failure;
        }
    }

    private void persistFailure(TransactionTemplate template, String taskId, PendingFailure pending) {
        template.executeWithoutResult(status ->
                repository.failTask(taskId, Instant.now(), pending.code(), pending.message()));
    }

    private record PendingFailure(String code, String message) {}

    private String safeInspectionMessage(InspectionException failure) {
        // Engine messages never contain paths. Keep a stable fallback if a future implementation changes that contract.
        String message = failure.getMessage();
        if (message == null || message.isBlank() || message.contains("/") || message.contains("\\")) {
            message = "CSV 文件无法完成检测。";
        }
        return failure.getLineNumber() == null ? message : message + "（文件行 " + failure.getLineNumber() + "）";
    }
}
