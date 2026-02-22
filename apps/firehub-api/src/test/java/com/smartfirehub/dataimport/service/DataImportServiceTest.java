package com.smartfirehub.dataimport.service;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.smartfirehub.audit.service.AuditLogService;
import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.dto.ImportStartResponse;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

@Transactional
class DataImportServiceTest extends IntegrationTestBase {

  @Autowired private DataImportService dataImportService;

  @Autowired private DataExportService dataExportService;

  @Autowired private DatasetService datasetService;

  @Autowired private AuditLogService auditLogService;

  @Autowired private DSLContext dsl;

  private Long testUserId;
  private Long testDatasetId;

  @BeforeEach
  void setUp() {
    // Create test user
    testUserId =
        dsl.insertInto(USER)
            .set(USER.USERNAME, "testuser")
            .set(USER.PASSWORD, "password")
            .set(USER.NAME, "Test User")
            .set(USER.EMAIL, "test@example.com")
            .returning(USER.ID)
            .fetchOne()
            .getId();

    // Create test dataset
    List<DatasetColumnRequest> columns =
        List.of(
            new DatasetColumnRequest("name", "Name", "TEXT", null, false, false, null),
            new DatasetColumnRequest("age", "Age", "INTEGER", null, true, false, null),
            new DatasetColumnRequest("email", "Email", "TEXT", null, true, false, null));

    DatasetDetailResponse dataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Import Test Dataset",
                "import_test_dataset",
                "Dataset for import testing",
                null,
                "SOURCE",
                columns),
            testUserId);

    testDatasetId = dataset.id();
  }

  @Test
  void importFile_csvWithValidData_returnsResponse() throws Exception {
    // Given
    String csvContent =
        """
        name,age,email
        Alice,30,alice@example.com
        Bob,25,bob@example.com
        """;

    MockMultipartFile file =
        new MockMultipartFile(
            "file", "test.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

    // When
    ImportStartResponse response =
        dataImportService.importFile(
            testDatasetId, file, null, testUserId, "Test User", "127.0.0.1", "TestAgent");

    // Then
    assertThat(response.jobId()).isNotNull();
    assertThat(response.status()).isEqualTo("PENDING");
  }

  @Test
  void importFile_unsupportedFileType_throwsException() {
    // Given
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "test.pdf",
            "application/pdf",
            "dummy content".getBytes(StandardCharsets.UTF_8));

    // When/Then
    assertThatThrownBy(
            () ->
                dataImportService.importFile(
                    testDatasetId, file, null, testUserId, "Test User", null, null))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("Unsupported file type");
  }

  @Test
  void importFile_noFileName_throwsException() {
    // Given
    MockMultipartFile file =
        new MockMultipartFile("file", null, "text/csv", "dummy".getBytes(StandardCharsets.UTF_8));

    // When/Then
    assertThatThrownBy(
            () ->
                dataImportService.importFile(
                    testDatasetId, file, null, testUserId, "Test User", null, null))
        .isInstanceOf(UnsupportedFileTypeException.class)
        .hasMessageContaining("File name is required");
  }

  @Test
  void getImportsByDatasetId_withAuditLogs_returnsImports() {
    // Given - directly log an import audit entry
    Map<String, Object> metadata =
        Map.of(
            "fileName", "test.csv",
            "fileSize", 1024,
            "fileType", "CSV",
            "totalRows", 10,
            "successRows", 10,
            "errorRows", 0);

    auditLogService.log(
        testUserId,
        "Test User",
        "IMPORT",
        "dataset",
        String.valueOf(testDatasetId),
        "파일 임포트: test.csv",
        null,
        null,
        "SUCCESS",
        null,
        metadata);

    // When
    List<ImportResponse> imports = dataImportService.getImportsByDatasetId(testDatasetId);

    // Then
    assertThat(imports).hasSize(1);
    assertThat(imports.get(0).fileName()).isEqualTo("test.csv");
    assertThat(imports.get(0).status()).isEqualTo("COMPLETED");
    assertThat(imports.get(0).successRows()).isEqualTo(10);
  }

  @Test
  void getImportById_returnsImport() {
    // Given
    Map<String, Object> metadata =
        Map.of(
            "fileName", "test.csv",
            "fileSize", 2048,
            "fileType", "CSV",
            "totalRows", 5,
            "successRows", 5,
            "errorRows", 0);

    Long auditId =
        auditLogService.log(
            testUserId,
            "Test User",
            "IMPORT",
            "dataset",
            String.valueOf(testDatasetId),
            "파일 임포트: test.csv",
            null,
            null,
            "SUCCESS",
            null,
            metadata);

    // When
    ImportResponse retrieved = dataImportService.getImportById(testDatasetId, auditId);

    // Then
    assertThat(retrieved.id()).isEqualTo(auditId);
    assertThat(retrieved.fileName()).isEqualTo("test.csv");
    assertThat(retrieved.status()).isEqualTo("COMPLETED");
  }

  @Test
  void getImportById_wrongDataset_throwsException() {
    // Given
    Map<String, Object> metadata =
        Map.of("fileName", "test.csv", "fileSize", 100, "fileType", "CSV");

    Long auditId =
        auditLogService.log(
            testUserId,
            "Test User",
            "IMPORT",
            "dataset",
            String.valueOf(testDatasetId),
            "파일 임포트: test.csv",
            null,
            null,
            "SUCCESS",
            null,
            metadata);

    // Create another dataset
    List<DatasetColumnRequest> columns =
        List.of(new DatasetColumnRequest("col1", "Col1", "TEXT", null, true, false, null));
    DatasetDetailResponse anotherDataset =
        datasetService.createDataset(
            new CreateDatasetRequest(
                "Another Dataset", "another_dataset", null, null, "SOURCE", columns),
            testUserId);

    // When/Then
    assertThatThrownBy(() -> dataImportService.getImportById(anotherDataset.id(), auditId))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("does not belong to this dataset");
  }

  @Test
  void exportDatasetCsv_generatesValidCsv() throws Exception {
    // Given - directly process import synchronously for test
    String filePath =
        createTempCsvFile("name,age,email\nAlice,30,alice@example.com\nBob,25,bob@example.com");
    dataImportService.processImport(
        "test-job-id",
        testDatasetId,
        filePath,
        "",
        "",
        "test.csv",
        100L,
        "CSV",
        testUserId,
        "Test User",
        "",
        "",
        "APPEND");

    // When
    byte[] csvBytes = dataExportService.exportDatasetCsv(testDatasetId);

    // Then
    assertThat(csvBytes).isNotEmpty();
    String csvOutput = new String(csvBytes, StandardCharsets.UTF_8);
    assertThat(csvOutput).contains("Name");
    assertThat(csvOutput).contains("Age");
    assertThat(csvOutput).contains("Email");
  }

  private String createTempCsvFile(String content) throws Exception {
    java.nio.file.Path tempDir =
        java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "firehub-test");
    java.nio.file.Files.createDirectories(tempDir);
    java.nio.file.Path tempFile = java.nio.file.Files.createTempFile(tempDir, "test-", ".csv");
    java.nio.file.Files.writeString(tempFile, content, StandardCharsets.UTF_8);
    return tempFile.toString();
  }
}
