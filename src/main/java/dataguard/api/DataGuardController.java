package dataguard.api;

import dataguard.core.QualityReport;
import dataguard.core.ReportRenderer;
import dataguard.service.DatasetInfo;
import dataguard.service.DatasetService;
import dataguard.service.TaskInfo;
import dataguard.service.TaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api")
@Validated
public class DataGuardController {
    private final DatasetService datasets;
    private final TaskService tasks;
    private final ReportRenderer renderer;

    public DataGuardController(DatasetService datasets, TaskService tasks, ReportRenderer renderer) {
        this.datasets = datasets;
        this.tasks = tasks;
        this.renderer = renderer;
    }

    @GetMapping("/datasets")
    public List<DatasetInfo> datasets(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                      @RequestParam(defaultValue = "0") @Min(0) int offset) {
        return datasets.listDatasets(limit, offset);
    }

    @GetMapping("/datasets/{id}")
    public DatasetInfo dataset(@PathVariable String id) {
        return datasets.getDataset(id);
    }

    @PostMapping(value = "/datasets", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DatasetInfo> upload(@RequestPart("file") MultipartFile file) throws IOException {
        DatasetInfo dataset;
        try (var input = file.getInputStream()) {
            dataset = datasets.upload(file.getOriginalFilename(), input);
        }
        return ResponseEntity.created(URI.create("/api/datasets/" + dataset.id())).body(dataset);
    }

    @PostMapping(value = "/datasets/import", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<DatasetInfo> importDataset(@Valid @RequestBody ImportDatasetRequest request) {
        DatasetInfo dataset = datasets.register(request.path());
        return ResponseEntity.created(URI.create("/api/datasets/" + dataset.id())).body(dataset);
    }

    /** 同步执行：返回已持久化的终态。解析失败也是可查询的 FAILED 任务。 */
    @PostMapping(value = "/tasks", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<TaskInfo> start(@Valid @RequestBody CreateTaskRequest request) {
        TaskInfo task = tasks.startTask(request.datasetId());
        return ResponseEntity.created(URI.create("/api/tasks/" + task.id())).body(task);
    }

    @GetMapping("/tasks")
    public List<TaskInfo> tasks(@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
                                @RequestParam(defaultValue = "0") @Min(0) int offset,
                                @RequestParam(required = false) String datasetId) {
        return tasks.listTasks(limit, offset, datasetId);
    }

    @GetMapping("/tasks/{id}")
    public TaskInfo task(@PathVariable String id) {
        return tasks.getTask(id);
    }

    @GetMapping("/tasks/{id}/report")
    public QualityReport report(@PathVariable String id) {
        return tasks.getReport(id);
    }

    @GetMapping(value = "/tasks/{id}/report.html", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> html(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .body(renderer.toHtml(tasks.getReport(id)));
    }
}
