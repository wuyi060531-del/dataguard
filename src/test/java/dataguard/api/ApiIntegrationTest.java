package dataguard.api;

import tools.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** 真正 HTTP + 隔离 JDBC 数据库；不以 Mock 替代核心服务与文件访问。 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiIntegrationTest {
    private static final Path ROOT = temporaryRoot();
    private static final String HEADER = "order_id,customer_id,product_id,quantity,unit_price,order_time,status\n";
    @Autowired
    TestRestTemplate http;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:api-" + ROOT.getFileName() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        registry.add("dataguard.storage-root", () -> ROOT.resolve("uploads").toString());
        registry.add("dataguard.import-root", () -> ROOT.resolve("imports").toString());
    }

    @Test
    void importsInspectsAndReadsPersistedReportAndHistory() {
        ResponseEntity<JsonNode> dataset = jsonPost("/api/datasets/import", Map.of("path", "orders.csv"));
        assertThat(dataset.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String datasetId = dataset.getBody().path("id").asText();
        assertThat(dataset.getHeaders().getLocation().getPath()).isEqualTo("/api/datasets/" + datasetId);
        assertThat(dataset.getBody().has("storagePath")).isFalse();

        ResponseEntity<JsonNode> execution = jsonPost("/api/tasks", Map.of("datasetId", datasetId));
        assertThat(execution.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(execution.getBody().path("status").asText()).isEqualTo("SUCCEEDED");
        String taskId = execution.getBody().path("id").asText();
        JsonNode report = http.getForObject("/api/tasks/" + taskId + "/report", JsonNode.class);
        assertThat(report.path("totalRecords").asInt()).isEqualTo(40);
        assertThat(report.path("validRecords").asInt()).isEqualTo(29);
        assertThat(report.path("invalidRecords").asInt()).isEqualTo(11);
        assertThat(report.path("totalIssues").asInt()).isEqualTo(11);
        assertThat(report.path("qualityScore").decimalValue()).isEqualByComparingTo("72.50");
        assertThat(report.path("issues").findValuesAsString("lineNumber")).isNotEmpty();
        // The web UI shows each issue's actual CSV value from `record`; keep that contract in the API.
        for (JsonNode issue : report.path("issues")) {
            assertThat(issue.path("record").isObject()).isTrue();
            String issueField = issue.path("field").asText();
            if (!"*".equals(issueField)) {
                for (String name : issueField.split(",")) {
                    assertThat(issue.path("record").has(name)).as("raw value of %s", name).isTrue();
                }
            }
        }
        JsonNode tasks = http.getForObject("/api/tasks?datasetId=" + datasetId, JsonNode.class);
        assertThat(tasks.isArray()).isTrue();
        assertThat(tasks.findValuesAsString("id")).contains(taskId);
        ResponseEntity<String> html = http.getForEntity("/api/tasks/" + taskId + "/report.html", String.class);
        assertThat(html.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(html.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML)).isTrue();
        assertThat(html.getBody()).contains("72.50", "REQUIRED_FIELD");
        assertThat(html.getHeaders().getFirst("Content-Security-Policy")).contains("default-src 'none'");
    }

    @Test
    void uploadsQuotedCsvAndEscapesReportHtml() {
        String csv = HEADER + "\"<script>alert(1)</script>\",C1,P1,2,5.00,2026-01-01T01:00:00,PAID\n";
        ResponseEntity<JsonNode> dataset = upload("quoted.csv", csv.getBytes(StandardCharsets.UTF_8));
        assertThat(dataset.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode task = jsonPost("/api/tasks", Map.of("datasetId", dataset.getBody().path("id").asText())).getBody();
        assertThat(task.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(task.path("validRecords").asInt()).isEqualTo(1);
        assertThat(http.getForEntity("/api/tasks/" + task.path("id").asText() + "/report.html", String.class).getBody())
                .doesNotContain("<script>");
    }

    @Test
    void malformedCsvProducesFailedTaskWithHistoryAndNoReport() {
        JsonNode dataset = upload("malformed.csv", "wrong,header\n1,2\n".getBytes(StandardCharsets.UTF_8)).getBody();
        ResponseEntity<JsonNode> result = jsonPost("/api/tasks", Map.of("datasetId", dataset.path("id").asText()));
        assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(result.getBody().path("status").asText()).isEqualTo("FAILED");
        assertThat(result.getBody().path("errorCode").asText()).isNotBlank();
        String id = result.getBody().path("id").asText();
        assertThat(http.getForObject("/api/tasks/" + id, JsonNode.class).path("status").asText()).isEqualTo("FAILED");
        ResponseEntity<JsonNode> report = http.getForEntity("/api/tasks/" + id + "/report", JsonNode.class);
        assertThat(report.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(report.getBody().path("code").asText()).isEqualTo("REPORT_NOT_AVAILABLE");
        assertThat(report.getBody().toString()).doesNotContain(ROOT.toString(), "stackTrace", "jdbc:");
    }

    @Test
    void rejectsTraversalNamesUnsafeImportsAndInvalidPagination() {
        for (String path : new String[]{"../orders.csv", "/etc/passwd", "nested/orders.csv", "nested\\orders.csv"}) {
            assertThat(jsonPost("/api/datasets/import", Map.of("path", path)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        }
        assertThat(upload("../../escape.csv", "a,b".getBytes(StandardCharsets.UTF_8)).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/tasks?limit=0", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/datasets?offset=-1", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/tasks?limit=no", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void validatesJsonAndReturnsNotFoundWithoutInternalDetails() {
        assertThat(jsonPost("/api/tasks", Map.of("datasetId", "")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jsonPost("/api/datasets/import", Map.of("path", "")).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(jsonPost("/api/tasks", Map.of("datasetId", UUID.randomUUID().toString())).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON);
        ResponseEntity<JsonNode> invalid = http.postForEntity("/api/tasks", new HttpEntity<>("{bad json", headers), JsonNode.class);
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(invalid.getBody().path("status").asInt()).isEqualTo(400);
        assertThat(http.getForEntity("/api/unknown", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void rejectsOversizedUploadsAndUnsupportedMethodsAndFormats() {
        assertThat(upload("large.csv", new byte[5 * 1024 * 1024 + 1]).getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(http.exchange("/api/datasets", HttpMethod.DELETE, HttpEntity.EMPTY, JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(http.postForEntity("/api/tasks", "text", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        HttpHeaders headers = new HttpHeaders(); headers.setAccept(java.util.List.of(MediaType.APPLICATION_XML));
        ResponseEntity<String> unacceptable = http.exchange("/api/datasets", HttpMethod.GET, new HttpEntity<>(headers), String.class);
        assertThat(unacceptable.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(unacceptable.getBody()).contains("NOT_ACCEPTABLE");
    }

    @Test
    void servesMinimalUiAndRejectsCrossSiteOrigin() {
        ResponseEntity<String> page = http.getForEntity("/", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getBody()).contains("DataGuard", "upload-form");
        assertThat(page.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        HttpHeaders headers = new HttpHeaders(); headers.setOrigin("https://untrusted.example");
        ResponseEntity<JsonNode> rejected = http.exchange("/api/datasets", HttpMethod.GET, new HttpEntity<>(headers), JsonNode.class);
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(rejected.getBody().path("code").asText()).isEqualTo("ORIGIN_NOT_ALLOWED");
    }

    private ResponseEntity<JsonNode> jsonPost(String path, Object value) {
        return http.postForEntity(path, value, JsonNode.class);
    }

    private ResponseEntity<JsonNode> upload(String filename, byte[] content) {
        MultiValueMap<String, Object> multipart = new LinkedMultiValueMap<>();
        multipart.add("file", new ByteArrayResource(content) {
            @Override public String getFilename() { return filename; }
        });
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.postForEntity("/api/datasets", new HttpEntity<>(multipart, headers), JsonNode.class);
    }

    private static Path temporaryRoot() {
        try {
            Path root = Files.createTempDirectory("dataguard-api-");
            Files.createDirectories(root.resolve("imports"));
            Files.copy(Path.of("data/raw/orders.csv"), root.resolve("imports/orders.csv"));
            return root;
        } catch (IOException failure) { throw new ExceptionInInitializerError(failure); }
    }
}
