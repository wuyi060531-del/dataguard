package dataguard.service;

import dataguard.Main;
import dataguard.core.QualityReport;
import dataguard.persistence.InspectionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Closes and recreates the real Spring/Hikari lifecycle against an isolated file database. */
class ApplicationRestartIntegrationTest {
    @TempDir Path directory;

    @Test
    void realContextRestartPreservesReportsAndRecoversPriorRunningTasks() throws Exception {
        Path imports = Files.createDirectory(directory.resolve("raw"));
        Path storage = directory.resolve("uploads");
        Path database = directory.resolve("history");
        String[] arguments = {
                "--spring.profiles.active=default",
                "--spring.datasource.url=jdbc:h2:file:" + database + ";DB_CLOSE_ON_EXIT=FALSE",
                "--spring.datasource.driver-class-name=org.h2.Driver",
                "--spring.datasource.username=sa", "--spring.datasource.password=",
                "--spring.sql.init.mode=always", "--spring.sql.init.schema-locations=classpath:schema-h2.sql",
                "--spring.datasource.hikari.maximum-pool-size=2",
                "--dataguard.storage-root=" + storage, "--dataguard.import-root=" + imports,
                "--spring.main.banner-mode=off", "--logging.level.root=WARN"
        };
        DatasetInfo dataset;
        TaskInfo succeeded;
        QualityReport report;
        String interrupted = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext first = start(arguments)) {
            DatasetService datasets = first.getBean(DatasetService.class);
            TaskService tasks = first.getBean(TaskService.class);
            dataset = datasets.upload("restart.csv", new ByteArrayInputStream((
                    "order_id,customer_id,product_id,quantity,unit_price,order_time,status\n"
                            + "O1,C1,P1,2,12.50,2026-10-19T09:00:00,PAID\n").getBytes(StandardCharsets.UTF_8)));
            succeeded = tasks.startTask(dataset.id());
            assertEquals(TaskStatus.SUCCEEDED, succeeded.status());
            report = tasks.getReport(succeeded.id());
            first.getBean(InspectionRepository.class).insertTask(interrupted, dataset.id(), Instant.now().minusSeconds(10));
            assertEquals(TaskStatus.RUNNING, tasks.getTask(interrupted).status());
        }
        assertTrue(Files.isRegularFile(directory.resolve("history.mv.db")));

        try (ConfigurableApplicationContext second = start(arguments)) {
            TaskService tasks = second.getBean(TaskService.class);
            assertEquals(dataset, second.getBean(DatasetService.class).getDataset(dataset.id()));
            assertEquals(succeeded, tasks.getTask(succeeded.id()));
            assertEquals(report, tasks.getReport(succeeded.id()));
            assertEquals(TaskStatus.FAILED, tasks.getTask(interrupted).status());
            assertEquals("INTERRUPTED", tasks.getTask(interrupted).errorCode());
            assertEquals(2, tasks.listTasks(10, 0, dataset.id()).size());
        }
    }

    private ConfigurableApplicationContext start(String[] arguments) {
        SpringApplication application = new SpringApplication(Main.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(arguments);
    }
}
