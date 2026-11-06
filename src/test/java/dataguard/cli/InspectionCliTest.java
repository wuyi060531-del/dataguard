package dataguard.cli;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class InspectionCliTest {
    @TempDir Path temporary;

    @Test
    void writesRealJsonAndHtmlWithoutOverwritingExistingReports() throws Exception {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        InspectionCli cli = new InspectionCli();
        String[] args = {"inspect", "data/raw/orders.csv", temporary.toString()};
        try (PrintStream stream = new PrintStream(console, true, StandardCharsets.UTF_8)) {
            assertThat(cli.run(args, stream, stream)).isZero();
            assertThat(cli.run(args, stream, stream)).isZero();
        }
        assertThat(console.toString(StandardCharsets.UTF_8)).contains("Records: 40", "Valid: 29", "Quality: 72.50%");
        try (var files = Files.list(temporary)) { assertThat(files.count()).isEqualTo(4); }
        Path json;
        try (var files = Files.list(temporary)) { json = files.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
        JsonNode report = JsonMapper.builder().build().readTree(Files.readString(json));
        assertThat(report.path("totalIssues").asInt()).isEqualTo(11);
    }

    @Test
    void missingFilesAndUsageReturnNonzeroWithoutReports() {
        ByteArrayOutputStream console = new ByteArrayOutputStream();
        try (PrintStream stream = new PrintStream(console, true, StandardCharsets.UTF_8)) {
            assertThat(new InspectionCli().run(new String[]{"inspect", temporary.resolve("missing.csv").toString(), temporary.toString()}, stream, stream)).isEqualTo(1);
            assertThat(new InspectionCli().run(new String[]{"inspect", "a", "b", "c"}, stream, stream)).isEqualTo(2);
        }
    }
}
