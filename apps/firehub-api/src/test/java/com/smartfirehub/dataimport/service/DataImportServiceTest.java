package com.smartfirehub.dataimport.service;

import com.smartfirehub.dataimport.dto.ImportResponse;
import com.smartfirehub.dataimport.exception.UnsupportedFileTypeException;
import com.smartfirehub.dataset.dto.CreateDatasetRequest;
import com.smartfirehub.dataset.dto.DatasetColumnRequest;
import com.smartfirehub.dataset.dto.DatasetDetailResponse;
import com.smartfirehub.dataset.service.DatasetService;
import com.smartfirehub.support.IntegrationTestBase;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.smartfirehub.jooq.Tables.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Transactional
class DataImportServiceTest extends IntegrationTestBase {

    @Autowired
    private DataImportService dataImportService;

    @Autowired
    private DatasetService datasetService;

    @Autowired
    private DSLContext dsl;

    private Long testUserId;
    private Long testDatasetId;

    @BeforeEach
    void setUp() {
        // Create test user
        testUserId = dsl.insertInto(USER)
                .set(USER.USERNAME, "testuser")
                .set(USER.PASSWORD, "password")
                .set(USER.NAME, "Test User")
                .set(USER.EMAIL, "test@example.com")
                .returning(USER.ID)
                .fetchOne()
                .getId();

        // Create test dataset
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("name", "Name", "TEXT", false, false, null),
                new DatasetColumnRequest("age", "Age", "INTEGER", true, false, null),
                new DatasetColumnRequest("email", "Email", "TEXT", true, false, null)
        );

        DatasetDetailResponse dataset = datasetService.createDataset(new CreateDatasetRequest(
                "Import Test Dataset",
                "import_test_dataset",
                "Dataset for import testing",
                null,
                "SOURCE",
                columns
        ), testUserId);

        testDatasetId = dataset.id();
    }

    @Test
    void importFile_csvWithValidData_success() throws Exception {
        // Given
        String csvContent = """
                name,age,email
                Alice,30,alice@example.com
                Bob,25,bob@example.com
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        // When
        ImportResponse response = dataImportService.importFile(testDatasetId, file, testUserId);

        // Then
        assertThat(response.id()).isNotNull();
        assertThat(response.datasetId()).isEqualTo(testDatasetId);
        assertThat(response.fileName()).isEqualTo("test.csv");
        assertThat(response.fileType()).isEqualTo("CSV");
        assertThat(response.status()).isEqualTo("PENDING");

        // Verify import record created
        Long importCount = dsl.selectCount()
                .from(DATA_IMPORT)
                .where(DATA_IMPORT.ID.eq(response.id()))
                .fetchOne(0, Long.class);
        assertThat(importCount).isEqualTo(1);
    }

    @Test
    void importFile_unsupportedFileType_throwsException() {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.pdf",
                "application/pdf",
                "dummy content".getBytes(StandardCharsets.UTF_8)
        );

        // When/Then
        assertThatThrownBy(() -> dataImportService.importFile(testDatasetId, file, testUserId))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("Unsupported file type");
    }

    @Test
    void importFile_noFileName_throwsException() {
        // Given
        MockMultipartFile file = new MockMultipartFile(
                "file",
                null,
                "text/csv",
                "dummy".getBytes(StandardCharsets.UTF_8)
        );

        // When/Then
        assertThatThrownBy(() -> dataImportService.importFile(testDatasetId, file, testUserId))
                .isInstanceOf(UnsupportedFileTypeException.class)
                .hasMessageContaining("File name is required");
    }

    @Test
    void getImportsByDatasetId_returnsImports() throws Exception {
        // Given
        String csvContent = "name,age,email\nAlice,30,alice@example.com";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        ImportResponse created = dataImportService.importFile(testDatasetId, file, testUserId);

        // When
        List<ImportResponse> imports = dataImportService.getImportsByDatasetId(testDatasetId);

        // Then
        assertThat(imports).isNotEmpty();
        assertThat(imports).anyMatch(imp -> imp.id().equals(created.id()));
    }

    @Test
    void getImportById_returnsImport() throws Exception {
        // Given
        String csvContent = "name,age,email\nAlice,30,alice@example.com";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        ImportResponse created = dataImportService.importFile(testDatasetId, file, testUserId);

        // When
        ImportResponse retrieved = dataImportService.getImportById(testDatasetId, created.id());

        // Then
        assertThat(retrieved.id()).isEqualTo(created.id());
        assertThat(retrieved.fileName()).isEqualTo("test.csv");
    }

    @Test
    void getImportById_wrongDataset_throwsException() throws Exception {
        // Given
        String csvContent = "name,age,email\nAlice,30,alice@example.com";
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        ImportResponse created = dataImportService.importFile(testDatasetId, file, testUserId);

        // Create another dataset
        List<DatasetColumnRequest> columns = List.of(
                new DatasetColumnRequest("col1", "Col1", "TEXT", true, false, null)
        );
        DatasetDetailResponse anotherDataset = datasetService.createDataset(new CreateDatasetRequest(
                "Another Dataset",
                "another_dataset",
                null,
                null,
                "SOURCE",
                columns
        ), testUserId);

        // When/Then
        assertThatThrownBy(() -> dataImportService.getImportById(anotherDataset.id(), created.id()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to this dataset");
    }

    @Test
    void exportDatasetCsv_generatesValidCsv() throws Exception {
        // Given - Import some data first
        String csvContent = """
                name,age,email
                Alice,30,alice@example.com
                Bob,25,bob@example.com
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file",
                "test.csv",
                "text/csv",
                csvContent.getBytes(StandardCharsets.UTF_8)
        );

        dataImportService.importFile(testDatasetId, file, testUserId);

        // Wait a bit for async processing (in real tests, you'd use proper async testing)
        Thread.sleep(500);

        // When
        byte[] csvBytes = dataImportService.exportDatasetCsv(testDatasetId);

        // Then
        assertThat(csvBytes).isNotEmpty();
        String csvOutput = new String(csvBytes, StandardCharsets.UTF_8);

        // CSV should contain BOM + headers
        assertThat(csvOutput).contains("Name");
        assertThat(csvOutput).contains("Age");
        assertThat(csvOutput).contains("Email");
    }
}
